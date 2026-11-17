package com.expensetracker.creditcard;

import com.expensetracker.account.AccountService;
import com.expensetracker.account.AccountType;
import com.expensetracker.account.FinancialAccount;
import com.expensetracker.analytics.InrFormat;
import com.expensetracker.audit.AuditService;
import com.expensetracker.common.ApiException;
import com.expensetracker.common.TimeConfig;
import com.expensetracker.notification.NotificationService;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.IsoFields;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Credit cards. Purchases on a card are expenses when they happen; paying the card from a bank account is a
 * transfer (both sides), so the bill payment is never counted as spending a second time.
 */
@Service
public class CreditCardService {

    public record CardRequest(BigDecimal creditLimit, Integer statementDay, Integer dueDaysAfter, BigDecimal minDuePct, BigDecimal minDueFloor,
                              BigDecimal rewardPoints, BigDecimal rewardRate, BigDecimal rewardPointValue, BigDecimal annualFee, String notes) {
    }

    public record Payment(UUID transactionId, LocalDate date, BigDecimal amount, String description, boolean linkedTransfer) {
    }

    public record CardDto(UUID accountId, String name, String institution, String last4, boolean configured,
                          BigDecimal creditLimit, BigDecimal outstanding, BigDecimal available, BigDecimal utilisationPct,
                          Integer statementDay, Integer dueDaysAfter, BigDecimal minDuePct, BigDecimal minDueFloor,
                          CardCycle.Cycle currentCycle, BigDecimal currentCycleSpend, CardCycle.Cycle lastStatement, CardCycle.Due due,
                          List<Payment> payments, BigDecimal rewardPoints, BigDecimal rewardRate, BigDecimal rewardPointValue,
                          BigDecimal estimatedPointsThisCycle, BigDecimal estimatedRewardValue, BigDecimal annualFee, String notes, String explanation) {
    }

    public record Overview(BigDecimal totalOutstanding, BigDecimal totalLimit, BigDecimal overallUtilisationPct, BigDecimal totalDue, List<CardDto> cards) {
    }

    private static final String STATEMENT_SQL = """
            SELECT a.opening_balance
                     + COALESCE(SUM(CASE WHEN t.direction = 'CREDIT' THEN t.amount ELSE -t.amount END) FILTER (WHERE t.txn_at < :statementEnd), 0) AS balance_at_statement,
                   COALESCE(SUM(t.amount) FILTER (WHERE t.direction = 'CREDIT' AND t.txn_at >= :statementEnd), 0) AS credits_since,
                   COALESCE(SUM(t.amount) FILTER (WHERE t.direction = 'DEBIT' AND t.kind = 'EXPENSE' AND t.txn_at >= :cycleStart), 0)
                     - COALESCE(SUM(t.amount) FILTER (WHERE t.direction = 'CREDIT' AND t.kind = 'REFUND' AND t.txn_at >= :cycleStart), 0) AS cycle_spend
            FROM financial_accounts a
            LEFT JOIN transactions t ON t.account_id = a.id AND t.status = 'POSTED' AND t.duplicate_status = 'NONE'
            WHERE a.id = :accountId AND a.user_id = :userId
            GROUP BY a.opening_balance
            """;

    private static final String PAYMENTS_SQL = """
            SELECT t.id, t.txn_at, t.amount, COALESCE(t.notes, t.description, t.counterparty) AS d, t.transfer_group_id
            FROM transactions t
            WHERE t.account_id = :accountId AND t.user_id = :userId AND t.direction = 'CREDIT' AND t.status = 'POSTED'
              AND t.duplicate_status = 'NONE' AND t.kind IN ('TRANSFER', 'INCOME', 'EXCLUDED')
            ORDER BY t.txn_at DESC LIMIT 12
            """;

    private final CreditCardDetailsRepository repository;
    private final AccountService accounts;
    private final NamedParameterJdbcTemplate jdbc;
    private final NotificationService notifications;
    private final AuditService audit;
    private final Clock clock;

    public CreditCardService(CreditCardDetailsRepository repository, AccountService accounts, NamedParameterJdbcTemplate jdbc,
                             NotificationService notifications, AuditService audit, Clock clock) {
        this.repository = repository;
        this.accounts = accounts;
        this.jdbc = jdbc;
        this.notifications = notifications;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Overview overview(UUID userId) {
        LocalDate today = TimeConfig.today(clock);
        Map<UUID, BigDecimal> balances = accounts.balanceMap(userId);
        List<CardDto> cards = accounts.list(userId).stream()
                .filter(a -> a.type() == AccountType.CREDIT_CARD && !a.archived())
                .map(a -> card(userId, a.id(), today, balances)).toList();
        BigDecimal out = BigDecimal.ZERO, limit = BigDecimal.ZERO, due = BigDecimal.ZERO;
        for (CardDto c : cards) {
            out = out.add(c.outstanding().max(BigDecimal.ZERO));
            if (c.creditLimit() != null) limit = limit.add(c.creditLimit());
            if (c.due() != null) due = due.add(c.due().remaining());
        }
        return new Overview(out, limit, CardCycle.utilisationPct(out, limit), due, cards);
    }

    @Transactional(readOnly = true)
    public CardDto get(UUID userId, UUID accountId) {
        return card(userId, accountId, TimeConfig.today(clock), accounts.balanceMap(userId));
    }

    @Transactional
    public CardDto configure(UUID userId, UUID accountId, CardRequest r) {
        FinancialAccount a = accounts.require(userId, accountId);
        if (a.getType() != AccountType.CREDIT_CARD) throw ApiException.badRequest("This account isn't a credit card");
        CreditCardDetails d = repository.findByAccountIdAndUserId(accountId, userId).orElse(null);
        if (d == null) {
            if (r.creditLimit() == null || r.statementDay() == null) throw ApiException.badRequest("Enter the credit limit and statement day");
            d = new CreditCardDetails(accountId, userId, positive(r.creditLimit(), "Credit limit"), statementDay(r.statementDay()));
        }
        if (r.creditLimit() != null) d.setCreditLimit(positive(r.creditLimit(), "Credit limit"));
        if (r.statementDay() != null) d.setStatementDay(statementDay(r.statementDay()));
        if (r.dueDaysAfter() != null) {
            if (r.dueDaysAfter() < 10 || r.dueDaysAfter() > 30) throw ApiException.badRequest("Payment due must be 10 to 30 days after the statement");
            d.setDueDaysAfter(r.dueDaysAfter());
        }
        if (r.minDuePct() != null) {
            if (r.minDuePct().signum() < 0 || r.minDuePct().compareTo(BigDecimal.valueOf(100)) > 0) throw ApiException.badRequest("Minimum due % must be 0 to 100");
            d.setMinDuePct(r.minDuePct());
        }
        if (r.minDueFloor() != null) d.setMinDueFloor(r.minDueFloor().max(BigDecimal.ZERO));
        if (r.rewardPoints() != null) d.setRewardPoints(r.rewardPoints().max(BigDecimal.ZERO));
        if (r.rewardRate() != null) d.setRewardRate(r.rewardRate().max(BigDecimal.ZERO));
        if (r.rewardPointValue() != null) d.setRewardPointValue(r.rewardPointValue().max(BigDecimal.ZERO));
        if (r.annualFee() != null) d.setAnnualFee(r.annualFee().max(BigDecimal.ZERO));
        if (r.notes() != null) d.setNotes(r.notes().isBlank() ? null : r.notes().trim());
        repository.saveAndFlush(d);
        audit.record(userId, "CARD_CONFIGURED", "financial_account", accountId, null);
        return get(userId, accountId);
    }

    @Transactional
    public void removeDetails(UUID userId, UUID accountId) {
        repository.findByAccountIdAndUserId(accountId, userId).ifPresent(repository::delete);
    }

    @Transactional
    public int remind(UUID userId) {
        int n = 0;
        LocalDate today = TimeConfig.today(clock);
        for (CardDto c : overview(userId).cards()) {
            if (!c.configured()) continue;
            CardCycle.Due due = c.due();
            if (due != null && due.remaining().signum() > 0) {
                String key = "card-due:" + c.accountId() + ":" + c.lastStatement().statementDate();
                if (due.state() == CardCycle.DueState.OVERDUE) {
                    if (notifications.notify(userId, NotificationService.Topic.CARD, "CARD_OVERDUE", "CRITICAL", c.name() + " payment is overdue",
                            InrFormat.format(due.remaining()) + " of the " + InrFormat.format(due.statementAmount()) + " bill was due on " + c.lastStatement().dueDate()
                                    + ". Paying late usually means a late fee and interest on the whole balance.", "/credit-cards", key + ":overdue")) n++;
                } else if (due.daysToDue() <= 5) {
                    if (notifications.notify(userId, NotificationService.Topic.CARD, "CARD_DUE", due.daysToDue() <= 2 ? "WARNING" : "INFO",
                            c.name() + " bill due " + (due.daysToDue() == 0 ? "today" : "in " + due.daysToDue() + " days"),
                            "Pay " + InrFormat.format(due.remaining()) + " by " + c.lastStatement().dueDate() + " to avoid interest (minimum "
                                    + InrFormat.format(due.minimumRemaining()) + ").", "/credit-cards", key + ":soon")) n++;
                }
            }
            if (c.utilisationPct() != null && c.utilisationPct().compareTo(BigDecimal.valueOf(30)) >= 0) {
                String week = today.getYear() + "-W" + today.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
                if (notifications.notify(userId, NotificationService.Topic.CARD, "CARD_UTILISATION", "INFO", c.name() + " is " + c.utilisationPct() + "% used",
                        "Using more than 30% of your limit can lower your credit score. Outstanding " + InrFormat.format(c.outstanding()) + " of "
                                + InrFormat.format(c.creditLimit()) + ".", "/credit-cards", "card-util:" + c.accountId() + ":" + week)) n++;
            }
        }
        return n;
    }

    private CardDto card(UUID userId, UUID accountId, LocalDate today, Map<UUID, BigDecimal> balances) {
        FinancialAccount a = accounts.require(userId, accountId);
        BigDecimal outstanding = balances.getOrDefault(accountId, BigDecimal.ZERO).negate().setScale(2, RoundingMode.HALF_UP);
        CreditCardDetails d = repository.findByAccountIdAndUserId(accountId, userId).orElse(null);
        List<Payment> payments = jdbc.query(PAYMENTS_SQL, new MapSqlParameterSource("accountId", accountId).addValue("userId", userId), (rs, i) ->
                new Payment((UUID) rs.getObject("id"), rs.getTimestamp("txn_at").toInstant().atZone(TimeConfig.IST).toLocalDate(),
                        rs.getBigDecimal("amount"), rs.getString("d"), rs.getObject("transfer_group_id") != null));
        if (d == null) {
            return new CardDto(accountId, a.getName(), a.getInstitution(), a.getLast4(), false, null, outstanding, null, null, null, null, null, null,
                    null, null, null, null, payments, null, null, null, null, null, null, null,
                    "Add the credit limit and statement day to see dues, cycles and utilisation.");
        }
        CardCycle.Cycle cur = CardCycle.current(d.getStatementDay(), d.getDueDaysAfter(), today);
        CardCycle.Cycle last = CardCycle.lastStatement(d.getStatementDay(), d.getDueDaysAfter(), today);
        MapSqlParameterSource p = new MapSqlParameterSource("accountId", accountId).addValue("userId", userId)
                .addValue("statementEnd", java.sql.Timestamp.from(TimeConfig.startOfDay(last.statementDate().plusDays(1))))
                .addValue("cycleStart", java.sql.Timestamp.from(TimeConfig.startOfDay(cur.start())));
        Map<String, Object> row = jdbc.queryForMap(STATEMENT_SQL, p);
        BigDecimal statementAmount = ((BigDecimal) row.get("balance_at_statement")).negate();
        BigDecimal creditsSince = (BigDecimal) row.get("credits_since");
        BigDecimal cycleSpend = ((BigDecimal) row.get("cycle_spend")).setScale(2, RoundingMode.HALF_UP);
        CardCycle.Due due = CardCycle.due(statementAmount, creditsSince, d.getMinDuePct(), d.getMinDueFloor(), last.dueDate(), today);
        BigDecimal pts = d.getRewardRate() == null ? null : cycleSpend.max(BigDecimal.ZERO).divide(BigDecimal.valueOf(100), 0, RoundingMode.FLOOR).multiply(d.getRewardRate()).setScale(2, RoundingMode.HALF_UP);
        BigDecimal value = d.getRewardPointValue() == null || d.getRewardPoints() == null ? null : d.getRewardPoints().multiply(d.getRewardPointValue()).setScale(2, RoundingMode.HALF_UP);
        return new CardDto(accountId, a.getName(), a.getInstitution(), a.getLast4(), true, d.getCreditLimit(), outstanding,
                d.getCreditLimit().subtract(outstanding.max(BigDecimal.ZERO)), CardCycle.utilisationPct(outstanding, d.getCreditLimit()),
                d.getStatementDay(), d.getDueDaysAfter(), d.getMinDuePct(), d.getMinDueFloor(), cur, cycleSpend, last, due, payments,
                d.getRewardPoints(), d.getRewardRate(), d.getRewardPointValue(), pts, value, d.getAnnualFee(), d.getNotes(),
                "Statement amount = what you owed at the end of " + last.statementDate() + " based on the transactions in Arth. "
                        + "Paid since = payments and refunds after that date. Minimum due = " + d.getMinDuePct().stripTrailingZeros().toPlainString()
                        + "% of the statement (at least " + InrFormat.format(d.getMinDueFloor()) + "). Check against your actual bank statement; missing transactions change these numbers.");
    }

    private static int statementDay(int v) {
        if (v < 1 || v > 28) throw ApiException.badRequest("Statement day must be 1 to 28 (use 28 for month-end statements)");
        return v;
    }

    private static BigDecimal positive(BigDecimal v, String what) {
        if (v.signum() <= 0) throw ApiException.badRequest(what + " must be more than zero");
        return v.setScale(2, RoundingMode.HALF_UP);
    }
}
