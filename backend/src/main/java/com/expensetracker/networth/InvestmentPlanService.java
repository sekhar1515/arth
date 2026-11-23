package com.expensetracker.networth;

import com.expensetracker.account.AccountService;
import com.expensetracker.common.ApiException;
import com.expensetracker.common.TimeConfig;
import com.expensetracker.ingestion.IngestOutcome;
import com.expensetracker.ingestion.IngestionService;
import com.expensetracker.ingestion.RawTransaction;
import com.expensetracker.transaction.Direction;
import com.expensetracker.transaction.PaymentMethod;
import com.expensetracker.transaction.SourceType;
import com.expensetracker.transaction.Transaction;
import com.expensetracker.transaction.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * SIPs and regular contributions. On each due date a SIP adds units to its fund at that day's NAV (as soon as AMFI
 * publishes it) and a contribution adds to the balance (PF/EPF: your part plus your employer's). When a bank account
 * is chosen, the debit is recorded there too, so the bank balance falls as the investment grows and net worth stays
 * right. If that bank's statement already has the debit, the installment is linked to it instead; if the statement
 * comes later, its line merges into the recorded debit.
 *
 * <p>Installments start from the day a plan is added: the units or balance you enter already include earlier ones.
 */
@Service
public class InvestmentPlanService {

    public record PlanRequest(UUID holdingId, InvestmentPlan.Kind kind, BigDecimal amount, BigDecimal employerAmount, Integer dayOfMonth,
                              LocalDate startDate, LocalDate endDate, UUID accountId, Boolean active, Long version) {
    }

    public record InstallmentDto(LocalDate dueDate, BigDecimal amount, BigDecimal nav, LocalDate navDate, BigDecimal units, UUID transactionId) {
    }

    public record PlanDto(UUID id, InvestmentPlan.Kind kind, UUID holdingId, String holdingName, AssetClass assetClass, BigDecimal amount,
                          BigDecimal employerAmount, BigDecimal total, int dayOfMonth, LocalDate startDate, LocalDate endDate, UUID accountId,
                          String accountName, boolean active, LocalDate nextDue, LocalDate lastRunDate, String waiting,
                          List<InstallmentDto> installments, long version) {
    }

    /** Statement lines up to this far from the due date count as the same debit (banks run SIPs a day or two late). */
    static final Duration MATCH_WINDOW = Duration.ofDays(4);
    static final Set<AssetClass> CONTRIBUTION_CLASSES = Set.of(AssetClass.EPF, AssetClass.PPF, AssetClass.NPS, AssetClass.RECURRING_DEPOSIT,
            AssetClass.OTHER, AssetClass.GOLD, AssetClass.SGB, AssetClass.BOND, AssetClass.FIXED_DEPOSIT);

    private static final Logger log = LoggerFactory.getLogger(InvestmentPlanService.class);

    private final InvestmentPlanRepository plans;
    private final HoldingRepository holdings;
    private final PriceService prices;
    private final AccountService accounts;
    private final IngestionService ingestion;
    private final TransactionRepository transactions;
    private final com.expensetracker.category.CategoryService categories;
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;

    public InvestmentPlanService(InvestmentPlanRepository plans, HoldingRepository holdings, PriceService prices, AccountService accounts,
                                 IngestionService ingestion, TransactionRepository transactions,
                                 com.expensetracker.category.CategoryService categories, NamedParameterJdbcTemplate jdbc,
                                 PlatformTransactionManager txManager, Clock clock) {
        this.plans = plans;
        this.holdings = holdings;
        this.prices = prices;
        this.accounts = accounts;
        this.ingestion = ingestion;
        this.transactions = transactions;
        this.categories = categories;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
        this.clock = clock;
    }

    // ------------------------------------------------------------------ CRUD

    public List<PlanDto> list(UUID userId) {
        runDue(userId);
        return tx.execute(s -> plans.findByUserIdOrderByCreatedAt(userId).stream().map(this::toDto).toList());
    }

    @Transactional
    public PlanDto create(UUID userId, PlanRequest r) {
        if (r.holdingId() == null) throw ApiException.badRequest("Choose the investment");
        Holding h = holdings.findByIdAndUserId(r.holdingId(), userId).orElseThrow(() -> ApiException.notFound("Investment"));
        InvestmentPlan.Kind kind = r.kind() != null ? r.kind() : h.getAssetClass() == AssetClass.MUTUAL_FUND ? InvestmentPlan.Kind.SIP : InvestmentPlan.Kind.CONTRIBUTION;
        checkHolding(h, kind);
        InvestmentPlan p = new InvestmentPlan(userId, h.getId(), kind);
        apply(userId, p, r, true);
        // installments start today: what you hold already includes earlier ones
        p.setLastRunDate(TimeConfig.today(clock).minusDays(1).isBefore(p.getStartDate()) ? p.getStartDate().minusDays(1) : TimeConfig.today(clock).minusDays(1));
        return toDto(plans.save(p));
    }

    @Transactional
    public PlanDto update(UUID userId, UUID id, PlanRequest r) {
        InvestmentPlan p = require(userId, id);
        if (r.version() != null && r.version() != p.getVersion()) throw ApiException.conflict("This plan changed elsewhere. Reload and try again.");
        apply(userId, p, r, false);
        plans.flush();
        return toDto(p);
    }

    @Transactional
    public void delete(UUID userId, UUID id) {
        plans.delete(require(userId, id)); // installments already applied stay in the investment and the bank account
    }

    private void apply(UUID userId, InvestmentPlan p, PlanRequest r, boolean creating) {
        if (r.amount() != null || creating) {
            if (r.amount() == null || r.amount().signum() <= 0) throw ApiException.badRequest("Enter the amount");
            p.setAmount(r.amount().setScale(2, RoundingMode.HALF_UP));
        }
        if (r.employerAmount() != null) {
            if (r.employerAmount().signum() < 0) throw ApiException.badRequest("Employer amount can't be negative");
            p.setEmployerAmount(r.employerAmount().signum() == 0 ? null : r.employerAmount().setScale(2, RoundingMode.HALF_UP));
        }
        if (r.dayOfMonth() != null || creating) {
            int d = r.dayOfMonth() == null ? 5 : r.dayOfMonth();
            if (d < 1 || d > 28) throw ApiException.badRequest("Pick a day between 1 and 28");
            p.setDayOfMonth(d);
        }
        if (r.startDate() != null || creating) p.setStartDate(r.startDate() != null ? r.startDate() : TimeConfig.today(clock));
        if (r.endDate() != null) p.setEndDate(r.endDate());
        if (p.getEndDate() != null && p.getEndDate().isBefore(p.getStartDate())) throw ApiException.badRequest("The end date is before the start date");
        if (r.accountId() != null) {
            accounts.require(userId, r.accountId()); // ownership
            p.setAccountId(r.accountId());
        } else if (creating) {
            p.setAccountId(null);
        }
        if (r.active() != null) p.setActive(r.active());
    }

    private static void checkHolding(Holding h, InvestmentPlan.Kind kind) {
        if (kind == InvestmentPlan.Kind.SIP) {
            if (h.getAssetClass() != AssetClass.MUTUAL_FUND || h.getValuationMethod() != ValuationMethod.MARKET_PRICE) {
                throw ApiException.badRequest("A SIP needs a mutual fund valued by NAV");
            }
            if (h.getSchemeCode() == null) {
                throw ApiException.unprocessable("Link this fund to its AMFI scheme first (⋯ → Get latest price), so each SIP can be priced at that day's NAV.");
            }
        } else if (h.getValuationMethod() != ValuationMethod.MANUAL) {
            throw ApiException.badRequest("Regular contributions work for PF/EPF, PPF, NPS and other investments whose value you enter");
        }
    }

    private InvestmentPlan require(UUID userId, UUID id) {
        return plans.findByIdAndUserId(id, userId).orElseThrow(() -> ApiException.notFound("Plan"));
    }

    // ------------------------------------------------------------------ running installments

    /** After each NAV check (2:30 am, 7:30 am, 9:30 pm, 11:30 pm), apply every installment that has become due. */
    @Scheduled(cron = "0 50 2,7,21,23 * * *", zone = "Asia/Kolkata")
    public void runAllDue() {
        for (InvestmentPlan p : plans.findByActiveTrue()) {
            try {
                runPlan(p.getId());
            } catch (RuntimeException e) {
                log.warn("Investment plan {} failed: {}", p.getId(), e.toString());
            }
        }
    }

    public void runDue(UUID userId) {
        for (InvestmentPlan p : plans.findByUserIdAndActiveTrue(userId)) {
            try {
                runPlan(p.getId());
            } catch (RuntimeException e) {
                log.warn("Investment plan {} failed: {}", p.getId(), e.toString());
            }
        }
    }

    /** Applies this plan's due installments in order, each in its own database transaction. */
    void runPlan(UUID planId) {
        LocalDate today = TimeConfig.today(clock);
        while (true) {
            Boolean more = tx.execute(s -> {
                InvestmentPlan p = plans.findById(planId).orElse(null);
                if (p == null || !p.isActive()) return false;
                LocalDate due = nextDue(p, p.getLastRunDate());
                if (due == null || due.isAfter(today)) return false;
                Holding h = holdings.findById(p.getHoldingId()).orElse(null);
                if (h == null || h.isArchived()) return false;
                return applyInstallment(p, h, due);
            });
            if (!Boolean.TRUE.equals(more)) return;
        }
    }

    /** Returns false when it has to wait (NAV for the date not out yet). */
    private boolean applyInstallment(InvestmentPlan p, Holding h, LocalDate due) {
        BigDecimal nav = null;
        LocalDate navDate = null;
        BigDecimal units = null;
        LocalDate allotted = due;
        if (p.getKind() == InvestmentPlan.Kind.SIP) {
            var scheme = h.getSchemeCode() == null ? null : prices.byCode(h.getSchemeCode()).orElse(null);
            if (scheme == null || scheme.nav() == null || scheme.navDate() == null || scheme.navDate().isBefore(due)) return false; // NAV not out yet
            // allotted at the first NAV on or after the due date: the next working day's if it fell on a weekend or holiday
            var first = prices.firstNavOnOrAfter(h.getSchemeCode(), due)
                    .orElse(new PriceService.DatedNav(scheme.prevNavDate() != null && !scheme.prevNavDate().isBefore(due) ? scheme.prevNavDate() : scheme.navDate(),
                            scheme.prevNavDate() != null && !scheme.prevNavDate().isBefore(due) ? scheme.prevNav() : scheme.nav()));
            nav = first.nav();
            navDate = first.date();
            allotted = navDate;
            units = p.getAmount().divide(nav, 3, RoundingMode.DOWN); // funds allot units to 3 decimals
            h.setUnits((h.getUnits() == null ? BigDecimal.ZERO : h.getUnits()).add(units).setScale(6, RoundingMode.HALF_UP));
            h.setInvestedAmount((h.getInvestedAmount() == null ? BigDecimal.ZERO : h.getInvestedAmount()).add(p.getAmount()));
            if (h.getPriceAsOf() == null || scheme.navDate().atTime(23, 0).atZone(TimeConfig.IST).toInstant().isAfter(h.getPriceAsOf())) {
                h.applyPrice(scheme.nav(), scheme.navDate().atTime(23, 0).atZone(TimeConfig.IST).toInstant(), Holding.PriceSource.AMFI);
            }
        } else {
            BigDecimal add = p.total();
            h.setManualValue((h.getManualValue() == null ? BigDecimal.ZERO : h.getManualValue()).add(add).setScale(2, RoundingMode.HALF_UP));
            h.setInvestedAmount((h.getInvestedAmount() == null ? BigDecimal.ZERO : h.getInvestedAmount()).add(add));
        }
        UUID txnId = p.getAccountId() == null ? null : recordDebit(p, h, due);
        transactions.flush(); // the debit must be in the database before the installment row points at it
        UUID installmentId = UUID.randomUUID();
        int added = jdbc.update("""
                INSERT INTO investment_installments (id, plan_id, user_id, due_date, amount, nav, nav_date, units, transaction_id, allotted_on)
                VALUES (:id, :plan, :user, :due, :amount, :nav, :navDate, :units, :txn, :allotted)
                ON CONFLICT (plan_id, due_date) DO NOTHING
                """, new MapSqlParameterSource().addValue("id", installmentId).addValue("plan", p.getId()).addValue("user", p.getUserId())
                .addValue("due", due).addValue("amount", p.total()).addValue("nav", nav).addValue("navDate", navDate).addValue("units", units)
                .addValue("txn", txnId).addValue("allotted", allotted));
        if (added > 0) {
            // each installment is also a purchase in the holding's history (exact XIRR, per-purchase returns)
            jdbc.update("""
                    INSERT INTO holding_lots (id, user_id, holding_id, trade_date, kind, amount, units, price, source, installment_id)
                    VALUES (:id, :u, :h, :d, 'BUY', :a, :units, :price, 'SIP', :inst)
                    """, new MapSqlParameterSource().addValue("id", UUID.randomUUID()).addValue("u", p.getUserId()).addValue("h", h.getId())
                    .addValue("d", allotted).addValue("a", p.total()).addValue("units", units).addValue("price", nav).addValue("inst", installmentId));
        }
        p.setLastRunDate(due);
        return true;
    }

    /** The bank debit for this installment: an existing statement line if there is one, otherwise a recorded one. */
    private UUID recordDebit(InvestmentPlan p, Holding h, LocalDate due) {
        var at = due.atTime(LocalTime.NOON).atZone(TimeConfig.IST).toInstant();
        List<UUID> alreadyLinked = jdbc.queryForList("SELECT transaction_id FROM investment_installments WHERE user_id = :u AND transaction_id IS NOT NULL",
                new MapSqlParameterSource("u", p.getUserId()), UUID.class);
        for (Transaction t : transactions.findByUserIdAndDirectionAndAmountAndTxnAtBetween(p.getUserId(), Direction.DEBIT, p.getAmount(),
                at.minus(MATCH_WINDOW), at.plus(MATCH_WINDOW))) {
            if (p.getAccountId().equals(t.getAccountId()) && !alreadyLinked.contains(t.getId())) return t.getId();
        }
        String path = p.getKind() == InvestmentPlan.Kind.SIP ? "Investments > SIP & Mutual Funds" : "Investments > Deposits & Schemes";
        UUID category = categories.resolvePath(p.getUserId(), path).map(com.expensetracker.category.Category::getId).orElse(null);
        String label = (p.getKind() == InvestmentPlan.Kind.SIP ? "SIP: " : "Contribution: ") + h.getName();
        IngestOutcome out = ingestion.ingest(p.getUserId(), RawTransaction.builder()
                .account(p.getAccountId()).direction(Direction.DEBIT).amount(p.getAmount()).txnAt(at).timeKnown(false)
                .counterparty(h.getName()).description(label).method(PaymentMethod.NACH).category(category)
                .notes(p.getKind() == InvestmentPlan.Kind.SIP ? "sip" : "investment")
                .source(SourceType.SCHEDULED, "plan:" + p.getId() + ":" + due)
                .raw("Recorded by Arth for your plan on " + due + "; it merges with the line from your bank statement when you import it")
                .build());
        return out.transactionId();
    }

    /** The first due date after {@code after}, within the plan's start and end dates. */
    static LocalDate nextDue(InvestmentPlan p, LocalDate after) {
        LocalDate from = after == null || after.isBefore(p.getStartDate().minusDays(1)) ? p.getStartDate().minusDays(1) : after;
        LocalDate candidate = from.plusDays(1).withDayOfMonth(1).withDayOfMonth(Math.min(p.getDayOfMonth(), 28));
        if (!candidate.isAfter(from)) candidate = candidate.plusMonths(1);
        if (p.getEndDate() != null && candidate.isAfter(p.getEndDate())) return null;
        return candidate;
    }

    // ------------------------------------------------------------------ views

    private PlanDto toDto(InvestmentPlan p) {
        Holding h = holdings.findById(p.getHoldingId()).orElse(null);
        String accountName = p.getAccountId() == null ? null : jdbc.query("SELECT name FROM financial_accounts WHERE id = :id",
                new MapSqlParameterSource("id", p.getAccountId()), rs -> rs.next() ? rs.getString(1) : null);
        List<InstallmentDto> recent = new ArrayList<>();
        jdbc.query("""
                SELECT due_date, amount, nav, nav_date, units, transaction_id FROM investment_installments
                WHERE plan_id = :p ORDER BY due_date DESC LIMIT 6
                """, new MapSqlParameterSource("p", p.getId()), rs -> {
            recent.add(new InstallmentDto(rs.getObject("due_date", LocalDate.class), rs.getBigDecimal("amount"), rs.getBigDecimal("nav"),
                    rs.getObject("nav_date", LocalDate.class), rs.getBigDecimal("units"), rs.getObject("transaction_id", UUID.class)));
        });
        LocalDate next = p.isActive() ? nextDue(p, p.getLastRunDate()) : null;
        String waiting = null;
        if (next != null && !next.isAfter(TimeConfig.today(clock)) && p.getKind() == InvestmentPlan.Kind.SIP) {
            waiting = "Waiting for the " + InrDates.shortDate(next) + " NAV from AMFI (most funds by 10 pm, international funds after midnight).";
        }
        return new PlanDto(p.getId(), p.getKind(), p.getHoldingId(), h == null ? "(deleted)" : h.getName(), h == null ? null : h.getAssetClass(),
                p.getAmount(), p.getEmployerAmount(), p.total(), p.getDayOfMonth(), p.getStartDate(), p.getEndDate(), p.getAccountId(), accountName,
                p.isActive(), next, p.getLastRunDate(), waiting, recent, p.getVersion());
    }
}
