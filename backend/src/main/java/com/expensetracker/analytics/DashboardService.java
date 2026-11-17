package com.expensetracker.analytics;

import com.expensetracker.account.AccountService;
import com.expensetracker.account.AccountType;
import com.expensetracker.category.CategoryService;
import com.expensetracker.common.TimeConfig;
import com.expensetracker.networth.NetWorthService;
import com.expensetracker.transaction.TransactionRepository;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class DashboardService {

    public record Period(LocalDate from, LocalDate to) {
    }

    public record CategorySlice(UUID categoryId, String name, String color, String icon, BigDecimal amount, BigDecimal share, long count) {
    }

    public record MerchantSlice(UUID merchantId, String name, BigDecimal amount, long count) {
    }

    public record PaymentSlice(String method, String provider, BigDecimal amount, long count) {
    }

    public record Insight(String type, String tone, String message) {
    }

    /** netWorth includes investments, property and loans (see /api/networth); totalBalance is bank + cash only. */
    public record AccountsOverview(BigDecimal totalBalance, BigDecimal creditCardOutstanding, BigDecimal netWorth,
                                   BigDecimal investments, BigDecimal loans, List<AccountService.AccountDto> accounts, String note) {
    }

    /**
     * expenses = spending (money out minus refunds, excluding money moved into investment categories);
     * invested = confirmed money moved into investment categories; savings = income − spending.
     */
    public record Summary(Period period, BigDecimal income, BigDecimal expenses, BigDecimal invested, BigDecimal grossExpenses, BigDecimal refunds,
                          BigDecimal savings, BigDecimal savingsRate, BigDecimal pendingCategorisationAmount,
                          long pendingCategorisationCountInPeriod, long needsReviewCount, long suspectedDuplicates,
                          BigDecimal transfersOut, List<CategorySlice> expenseByCategory, List<CategorySlice> investedByCategory,
                          List<CategorySlice> incomeByCategory,
                          List<MerchantSlice> topMerchants, List<PaymentSlice> paymentMethods, AccountsOverview accounts,
                          Period previousPeriod, BigDecimal previousIncome, BigDecimal previousExpenses, List<Insight> insights) {
    }

    public record MonthPoint(String month, BigDecimal income, BigDecimal expenses, BigDecimal invested, BigDecimal savings) {
    }

    public record DayPoint(LocalDate date, BigDecimal expenses, BigDecimal income) {
    }

    private record Totals(BigDecimal income, BigDecimal gross, BigDecimal refunds, BigDecimal pendingExpense, long pendingCount,
                          BigDecimal transfersOut) {
        BigDecimal expenses() {
            return gross.subtract(refunds);
        }
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final AccountService accounts;
    private final CategoryService categories;
    private final TransactionRepository transactions;
    private final NetWorthService netWorth;
    private final Clock clock;

    public DashboardService(NamedParameterJdbcTemplate jdbc, AccountService accounts, CategoryService categories,
                            TransactionRepository transactions, NetWorthService netWorth, Clock clock) {
        this.jdbc = jdbc;
        this.netWorth = netWorth;
        this.accounts = accounts;
        this.categories = categories;
        this.transactions = transactions;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Summary summary(UUID userId, LocalDate from, LocalDate to) {
        LocalDate today = TimeConfig.today(clock);
        if (from == null) from = today.withDayOfMonth(1);
        if (to == null) to = today;
        if (to.isBefore(from)) throw com.expensetracker.common.ApiException.badRequest("'to' must not be before 'from'");
        long days = ChronoUnit.DAYS.between(from, to) + 1;
        // previous period: the same number of days immediately before; month-to-date compares with the same days last month
        LocalDate prevTo = from.minusDays(1);
        LocalDate prevFrom = prevTo.minusDays(days - 1);
        if (from.getDayOfMonth() == 1 && YearMonth.from(from).equals(YearMonth.from(to))) {
            prevFrom = from.minusMonths(1);
            prevTo = prevFrom.plusDays(Math.min(days, YearMonth.from(prevFrom).lengthOfMonth()) - 1);
        }

        MapSqlParameterSource p = params(userId, from, to);
        Totals curAll = totals(p);
        Totals prevAll = totals(params(userId, prevFrom, prevTo));
        var catInfo = categories.infoMap(userId);
        List<CategorySlice> rawCur = breakdown(p, "DEBIT", List.of("EXPENSE", "REFUND"), BigDecimal.ZERO, catInfo);
        List<CategorySlice> rawPrev = breakdown(params(userId, prevFrom, prevTo), "DEBIT", List.of("EXPENSE", "REFUND"), BigDecimal.ZERO, catInfo);
        BigDecimal invested = sumWhere(rawCur, catInfo, true);
        BigDecimal prevInvested = sumWhere(rawPrev, catInfo, true);
        // money moved into investments is saved, not spent
        Totals cur = withoutInvested(curAll, invested);
        Totals prev = withoutInvested(prevAll, prevInvested);
        List<CategorySlice> expenseCats = reshare(rawCur, catInfo, false, cur.expenses());
        List<CategorySlice> investedCats = reshare(rawCur, catInfo, true, invested);
        List<CategorySlice> prevExpenseCats = reshare(rawPrev, catInfo, false, prev.expenses());
        List<CategorySlice> incomeCats = breakdown(p, "CREDIT", List.of("INCOME"), cur.income(), catInfo);

        List<MerchantSlice> merchants = new ArrayList<>();
        jdbc.query(DashboardQueries.TOP_MERCHANTS, p, rs -> {
            merchants.add(new MerchantSlice(rs.getObject("id", UUID.class), rs.getString("name"), rs.getBigDecimal("total"), rs.getLong("n")));
        });
        List<PaymentSlice> methods = new ArrayList<>();
        jdbc.query(DashboardQueries.PAYMENT_METHODS, p, rs -> {
            methods.add(new PaymentSlice(rs.getString("payment_method"), rs.getString("payment_provider"), rs.getBigDecimal("total"), rs.getLong("n")));
        });
        Long suspected = jdbc.queryForObject(DashboardQueries.SUSPECTED_COUNT, p, Long.class);
        long needsReview = transactions.countNeedsReview(userId);

        BigDecimal savings = cur.income().subtract(cur.expenses());
        BigDecimal savingsRate = cur.income().signum() > 0
                ? savings.multiply(BigDecimal.valueOf(100)).divide(cur.income(), 1, RoundingMode.HALF_UP) : null;

        AccountsOverview overview = accountsOverview(userId);
        List<Insight> insights = new ArrayList<>(insights(cur, prev, savingsRate, expenseCats, prevExpenseCats, needsReview,
                suspected == null ? 0 : suspected, days));
        if (invested.signum() > 0) {
            String share = cur.income().signum() > 0 ? " (" + invested.multiply(BigDecimal.valueOf(100)).divide(cur.income(), 0, RoundingMode.HALF_UP) + "% of income)" : "";
            insights.add(Math.min(2, insights.size()), new Insight("INVESTED", "positive", "You put " + InrFormat.format(invested) + " into investments" + share
                    + ". That counts as saved, not spent."));
        }

        return new Summary(new Period(from, to), cur.income(), cur.expenses(), invested.setScale(2, RoundingMode.HALF_UP), curAll.gross(), cur.refunds(),
                savings, savingsRate, cur.pendingExpense(), cur.pendingCount(), needsReview, suspected == null ? 0 : suspected, cur.transfersOut(),
                expenseCats, investedCats, incomeCats, merchants, methods, overview, new Period(prevFrom, prevTo), prev.income(), prev.expenses(),
                insights);
    }

    @Transactional(readOnly = true)
    public List<MonthPoint> monthly(UUID userId, int months) {
        int n = Math.max(1, Math.min(months, 24));
        YearMonth current = YearMonth.from(TimeConfig.today(clock));
        YearMonth first = current.minusMonths(n - 1L);
        Map<String, MonthPoint> byMonth = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            String key = first.plusMonths(i).toString();
            byMonth.put(key, new MonthPoint(key, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
        }
        MapSqlParameterSource mp = params(userId, first.atDay(1), current.atEndOfMonth());
        Map<String, BigDecimal> investedByMonth = new LinkedHashMap<>();
        jdbc.query(DashboardQueries.MONTHLY_INVESTED, mp, rs -> {
            investedByMonth.put(rs.getString("month"), rs.getBigDecimal("invested"));
        });
        jdbc.query(DashboardQueries.MONTHLY, mp, rs -> {
            BigDecimal income = rs.getBigDecimal("income");
            BigDecimal inv = investedByMonth.getOrDefault(rs.getString("month"), BigDecimal.ZERO);
            BigDecimal spending = rs.getBigDecimal("expenses").subtract(inv);
            byMonth.put(rs.getString("month"), new MonthPoint(rs.getString("month"), income, spending, inv, income.subtract(spending)));
        });
        return new ArrayList<>(byMonth.values());
    }

    @Transactional(readOnly = true)
    public List<DayPoint> daily(UUID userId, LocalDate from, LocalDate to) {
        LocalDate today = TimeConfig.today(clock);
        LocalDate f = from == null ? today.withDayOfMonth(1) : from;
        LocalDate t = to == null ? today : to;
        if (ChronoUnit.DAYS.between(f, t) > 400) throw com.expensetracker.common.ApiException.badRequest("Range too long (max 400 days)");
        Map<LocalDate, DayPoint> byDay = new LinkedHashMap<>();
        for (LocalDate d = f; !d.isAfter(t); d = d.plusDays(1)) byDay.put(d, new DayPoint(d, BigDecimal.ZERO, BigDecimal.ZERO));
        jdbc.query(DashboardQueries.DAILY, params(userId, f, t), rs -> {
            LocalDate d = rs.getObject("day", LocalDate.class);
            byDay.put(d, new DayPoint(d, rs.getBigDecimal("expenses"), rs.getBigDecimal("income")));
        });
        return new ArrayList<>(byDay.values());
    }

    // ------------------------------------------------------------------ helpers

    private AccountsOverview accountsOverview(UUID userId) {
        List<AccountService.AccountDto> list = accounts.list(userId).stream().filter(a -> !a.archived()).toList();
        BigDecimal assets = BigDecimal.ZERO;
        BigDecimal outstanding = BigDecimal.ZERO;
        for (AccountService.AccountDto a : list) {
            if (a.type() == AccountType.CREDIT_CARD) {
                if (a.balance().signum() < 0) outstanding = outstanding.add(a.balance().negate());
                else assets = assets.add(a.balance());
            } else {
                assets = assets.add(a.balance());
            }
        }
        NetWorthService.Summary nw = netWorth.summary(userId);
        BigDecimal loans = nw.liabilityGroups().stream().filter(g -> g.key().equals("LOANS")).map(NetWorthService.Group::value).findFirst().orElse(BigDecimal.ZERO);
        return new AccountsOverview(assets, outstanding, nw.netWorth(), nw.holdingsValue(), loans, list,
                "Balances are calculated from each account's opening balance plus imported transactions, not fetched live from your bank.");
    }

    private static Totals withoutInvested(Totals t, BigDecimal invested) {
        return new Totals(t.income(), t.gross().subtract(invested), t.refunds(), t.pendingExpense(), t.pendingCount(), t.transfersOut());
    }

    private static BigDecimal sumWhere(List<CategorySlice> slices, Map<UUID, CategoryService.CategoryInfo> info, boolean investment) {
        return slices.stream().filter(s -> isInvestment(s, info) == investment).map(CategorySlice::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static boolean isInvestment(CategorySlice s, Map<UUID, CategoryService.CategoryInfo> info) {
        CategoryService.CategoryInfo c = info.get(s.categoryId());
        return c != null && c.investment();
    }

    private static List<CategorySlice> reshare(List<CategorySlice> slices, Map<UUID, CategoryService.CategoryInfo> info, boolean investment, BigDecimal total) {
        return slices.stream().filter(s -> isInvestment(s, info) == investment)
                .map(s -> new CategorySlice(s.categoryId(), s.name(), s.color(), s.icon(), s.amount(),
                        total.signum() > 0 ? s.amount().multiply(BigDecimal.valueOf(100)).divide(total, 1, RoundingMode.HALF_UP) : BigDecimal.ZERO, s.count()))
                .toList();
    }

    private Totals totals(MapSqlParameterSource p) {
        return jdbc.queryForObject(DashboardQueries.TOTALS, p, (rs, i) -> new Totals(rs.getBigDecimal("income"),
                rs.getBigDecimal("gross_expense"), rs.getBigDecimal("refunds"), rs.getBigDecimal("pending_expense"),
                rs.getLong("pending_count"), rs.getBigDecimal("transfers_out")));
    }

    private List<CategorySlice> breakdown(MapSqlParameterSource base, String sign, List<String> kinds, BigDecimal total,
                                          Map<UUID, CategoryService.CategoryInfo> info) {
        MapSqlParameterSource p = new MapSqlParameterSource(base.getValues()).addValue("sign", sign).addValue("kinds", kinds);
        List<CategorySlice> out = new ArrayList<>();
        jdbc.query(DashboardQueries.CATEGORY_BREAKDOWN, p, rs -> {
            UUID id = rs.getObject("top_id", UUID.class);
            CategoryService.CategoryInfo c = info.get(id);
            BigDecimal amount = rs.getBigDecimal("total");
            BigDecimal share = total.signum() > 0 ? amount.multiply(BigDecimal.valueOf(100)).divide(total, 1, RoundingMode.HALF_UP) : BigDecimal.ZERO;
            out.add(new CategorySlice(id, c == null ? "Unknown" : c.name(), c == null ? "#94A3B8" : c.color(), c == null ? null : c.icon(),
                    amount, share, rs.getLong("n")));
        });
        return out;
    }

    private static MapSqlParameterSource params(UUID userId, LocalDate from, LocalDate to) {
        return new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("from", java.sql.Timestamp.from(TimeConfig.startOfDay(from)))
                .addValue("to", java.sql.Timestamp.from(TimeConfig.startOfDay(to.plusDays(1))));
    }

    static List<Insight> insights(Totals cur, Totals prev, BigDecimal savingsRate, List<CategorySlice> cats,
                                  List<CategorySlice> prevCats, long needsReview, long suspected, long days) {
        List<Insight> out = new ArrayList<>();
        out.add(new Insight("SPENT", "neutral", "You spent " + InrFormat.format(cur.expenses()) + " in this period"
                + (days > 1 ? " (about " + InrFormat.format(cur.expenses().divide(BigDecimal.valueOf(days), 0, RoundingMode.HALF_UP)) + " a day)." : ".")));
        if (prev.expenses().signum() > 0) {
            BigDecimal change = cur.expenses().subtract(prev.expenses()).multiply(BigDecimal.valueOf(100))
                    .divide(prev.expenses(), 0, RoundingMode.HALF_UP);
            if (change.abs().compareTo(BigDecimal.valueOf(5)) >= 0) {
                boolean up = change.signum() > 0;
                out.add(new Insight("SPEND_TREND", up ? "warning" : "positive", "Spending is " + (up ? "up " : "down ")
                        + change.abs().toPlainString() + "% compared with the same days of the previous period."));
            }
        }
        if (!cats.isEmpty()) {
            CategorySlice top = cats.get(0);
            String msg = top.name() + " is your biggest category at " + InrFormat.format(top.amount()) + " (" + top.share().toPlainString() + "% of spending)";
            CategorySlice before = prevCats.stream().filter(c -> c.categoryId().equals(top.categoryId())).findFirst().orElse(null);
            if (before != null && before.amount().signum() > 0) {
                BigDecimal ch = top.amount().subtract(before.amount()).multiply(BigDecimal.valueOf(100)).divide(before.amount(), 0, RoundingMode.HALF_UP);
                if (ch.abs().compareTo(BigDecimal.valueOf(10)) >= 0) msg += ", " + (ch.signum() > 0 ? "up " : "down ") + ch.abs().toPlainString() + "% on the previous period";
            }
            out.add(new Insight("TOP_CATEGORY", "neutral", msg + "."));
        }
        if (savingsRate != null) {
            out.add(new Insight("SAVINGS_RATE", savingsRate.signum() >= 0 ? "positive" : "warning",
                    "Your savings rate is " + savingsRate.toPlainString() + "%" + (savingsRate.signum() < 0 ? ": you spent more than you earned." : ".")));
        }
        if (needsReview > 0) {
            out.add(new Insight("NEEDS_REVIEW", "action", needsReview == 1 ? "1 transaction is waiting for you to categorise it."
                    : needsReview + " transactions are waiting for you to categorise them."));
        }
        if (cur.pendingExpense().signum() > 0) {
            out.add(new Insight("PENDING_AMOUNT", "neutral", InrFormat.format(cur.pendingExpense())
                    + " of this period's spending is not categorised yet, so the category chart leaves it out."));
        }
        if (suspected > 0) {
            out.add(new Insight("DUPLICATES", "action", suspected + " possible duplicate" + (suspected == 1 ? " is" : "s are")
                    + " excluded from totals until you review " + (suspected == 1 ? "it." : "them.")));
        }
        return out;
    }
}
