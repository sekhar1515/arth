package com.expensetracker.account;

import com.expensetracker.audit.AuditService;
import com.expensetracker.common.ApiException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class AccountService {

    /**
     * Balance = opening balance + credits − debits, counting only POSTED transactions that are not suspected
     * duplicates. Transfers move money between accounts, so they DO count here (unlike income/expense totals).
     */
    static final String BALANCES_SQL = """
            SELECT a.id,
                   a.opening_balance + COALESCE(SUM(CASE WHEN t.direction = 'CREDIT' THEN t.amount ELSE -t.amount END), 0) AS balance,
                   COUNT(t.id) AS txn_count,
                   MAX(t.txn_at) AS last_txn_at
            FROM financial_accounts a
            LEFT JOIN transactions t ON t.account_id = a.id
                 AND t.status = 'POSTED' AND t.duplicate_status = 'NONE'
            WHERE a.user_id = :userId
            GROUP BY a.id, a.opening_balance
            """;

    public record AccountDto(UUID id, String name, AccountType type, String institution, String last4,
                             BigDecimal openingBalance, LocalDate openingDate, BigDecimal balance, long transactionCount,
                             boolean archived, String balanceSource) {
    }

    public record AccountRequest(String name, AccountType type, String institution, String last4,
                                 BigDecimal openingBalance, LocalDate openingDate) {
    }

    private record BalanceRow(BigDecimal balance, long count) {
    }

    private final FinancialAccountRepository repository;
    private final NamedParameterJdbcTemplate jdbc;
    private final AuditService audit;

    public AccountService(FinancialAccountRepository repository, NamedParameterJdbcTemplate jdbc, AuditService audit) {
        this.repository = repository;
        this.jdbc = jdbc;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<AccountDto> list(UUID userId) {
        Map<UUID, BalanceRow> balances = balances(userId);
        return repository.findByUserIdOrderByArchivedAscCreatedAtAsc(userId).stream()
                .map(a -> toDto(a, balances.getOrDefault(a.getId(), new BalanceRow(a.getOpeningBalance(), 0))))
                .toList();
    }

    @Transactional(readOnly = true)
    public FinancialAccount require(UUID userId, UUID accountId) {
        return repository.findByIdAndUserId(accountId, userId).orElseThrow(() -> ApiException.notFound("Account"));
    }

    @Transactional(readOnly = true)
    public Optional<FinancialAccount> findByLast4(UUID userId, String last4) {
        if (last4 == null) return Optional.empty();
        String key = last4.length() > 4 ? last4.substring(last4.length() - 4) : last4;
        List<FinancialAccount> matches = repository.findByUserIdAndLast4AndArchivedFalse(userId, key);
        if (matches.isEmpty() && key.length() == 4) {
            // some banks show 3 digits ("XX234"): fall back to suffix match
            matches = repository.findByUserIdOrderByArchivedAscCreatedAtAsc(userId).stream()
                    .filter(a -> !a.isArchived() && a.getLast4() != null && a.getLast4().endsWith(key.substring(1)))
                    .toList();
        }
        return matches.size() == 1 ? Optional.of(matches.get(0)) : Optional.empty();
    }

    @Transactional
    public AccountDto create(UUID userId, AccountRequest req) {
        validate(req);
        FinancialAccount a = new FinancialAccount(userId, req.name().trim(), req.type());
        apply(a, req);
        repository.save(a);
        audit.record(userId, "ACCOUNT_CREATED", "financial_account", a.getId(), a.getName());
        return toDto(a, new BalanceRow(a.getOpeningBalance(), 0));
    }

    @Transactional
    public FinancialAccount createCashAccount(UUID userId) {
        return repository.save(new FinancialAccount(userId, "Cash", AccountType.CASH));
    }

    @Transactional
    public AccountDto update(UUID userId, UUID id, AccountRequest req) {
        validate(req);
        FinancialAccount a = require(userId, id);
        a.setName(req.name().trim());
        a.setType(req.type());
        apply(a, req);
        repository.flush(); // balances are computed in SQL; make the new opening balance visible to it
        audit.record(userId, "ACCOUNT_UPDATED", "financial_account", a.getId(), a.getName());
        return list(userId).stream().filter(d -> d.id().equals(id)).findFirst().orElseThrow();
    }

    @Transactional
    public void setArchived(UUID userId, UUID id, boolean archived) {
        FinancialAccount a = require(userId, id);
        a.setArchived(archived);
        audit.record(userId, archived ? "ACCOUNT_ARCHIVED" : "ACCOUNT_RESTORED", "financial_account", id, null);
    }

    public Map<UUID, BigDecimal> balanceMap(UUID userId) {
        Map<UUID, BigDecimal> out = new HashMap<>();
        balances(userId).forEach((k, v) -> out.put(k, v.balance()));
        return out;
    }

    private Map<UUID, BalanceRow> balances(UUID userId) {
        Map<UUID, BalanceRow> map = new HashMap<>();
        jdbc.query(BALANCES_SQL, new MapSqlParameterSource("userId", userId), rs -> {
            map.put(rs.getObject("id", UUID.class), new BalanceRow(rs.getBigDecimal("balance"), rs.getLong("txn_count")));
        });
        return map;
    }

    private static void apply(FinancialAccount a, AccountRequest req) {
        a.setInstitution(blankToNull(req.institution()));
        a.setLast4(blankToNull(req.last4()));
        a.setOpeningBalance(req.openingBalance() == null ? BigDecimal.ZERO : req.openingBalance().setScale(2, java.math.RoundingMode.HALF_UP));
        a.setOpeningDate(req.openingDate());
    }

    private static void validate(AccountRequest req) {
        if (req.name() == null || req.name().isBlank() || req.name().length() > 100) throw ApiException.badRequest("Account name is required (max 100 characters)");
        if (req.type() == null) throw ApiException.badRequest("Account type is required");
        if (req.last4() != null && !req.last4().isBlank() && !req.last4().matches("[0-9]{3,4}")) {
            throw ApiException.badRequest("Last digits must be 3–4 numbers (never enter a full account or card number)");
        }
        if (req.openingBalance() != null && req.openingBalance().scale() > 2) throw ApiException.badRequest("Opening balance has more than 2 decimal places");
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static AccountDto toDto(FinancialAccount a, BalanceRow b) {
        String source = b.count() == 0 ? "OPENING_BALANCE" : "COMPUTED_FROM_TRANSACTIONS";
        return new AccountDto(a.getId(), a.getName(), a.getType(), a.getInstitution(), a.getLast4(), a.getOpeningBalance(),
                a.getOpeningDate(), b.balance(), b.count(), a.isArchived(), source);
    }
}
