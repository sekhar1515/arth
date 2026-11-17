package com.expensetracker.report;

import com.expensetracker.analytics.AnalyticsService;
import com.expensetracker.analytics.DashboardService;
import com.expensetracker.analytics.InrFormat;
import com.expensetracker.budget.BudgetService;
import com.expensetracker.common.ApiException;
import com.expensetracker.common.TimeConfig;
import com.expensetracker.creditcard.CreditCardService;
import com.expensetracker.networth.HoldingService;
import com.expensetracker.networth.LiabilityService;
import com.expensetracker.networth.NetWorthService;
import com.expensetracker.recurring.RecurringPayment;
import com.expensetracker.recurring.RecurringService;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Builds downloadable reports. Each type produces one {@link ReportTable}; the format only changes how it's written. */
@Service
public class ReportService {

    public enum Type { MONTHLY, ANNUAL, CATEGORIES, INCOME, CASH_FLOW, BUDGETS, SUBSCRIPTIONS, CREDIT_CARDS, NET_WORTH, TRANSACTIONS }

    public enum Format { CSV, XLSX, PDF }

    public record File(String filename, String contentType, byte[] bytes) {
    }

    private static final DateTimeFormatter D = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    private final DashboardService dashboard;
    private final AnalyticsService analytics;
    private final BudgetService budgets;
    private final RecurringService recurring;
    private final CreditCardService cards;
    private final NetWorthService netWorth;
    private final HoldingService holdings;
    private final LiabilityService liabilities;
    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    public ReportService(DashboardService dashboard, AnalyticsService analytics, BudgetService budgets, RecurringService recurring,
                         CreditCardService cards, NetWorthService netWorth, HoldingService holdings, LiabilityService liabilities,
                         NamedParameterJdbcTemplate jdbc, Clock clock) {
        this.dashboard = dashboard;
        this.analytics = analytics;
        this.budgets = budgets;
        this.recurring = recurring;
        this.cards = cards;
        this.netWorth = netWorth;
        this.holdings = holdings;
        this.liabilities = liabilities;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public File generate(UUID userId, Type type, Format format, LocalDate from, LocalDate to) {
        LocalDate today = TimeConfig.today(clock);
        if (from == null) from = today.withDayOfMonth(1);
        if (to == null) to = today;
        if (to.isBefore(from)) throw ApiException.badRequest("'to' must not be before 'from'");
        if (from.plusYears(5).isBefore(to)) throw ApiException.badRequest("Choose a range of at most five years");
        ReportTable t = switch (type) {
            case MONTHLY -> monthly(userId, YearMonth.from(from));
            case ANNUAL -> annual(userId, from, to);
            case CATEGORIES -> categories(userId, from, to);
            case INCOME -> income(userId, from, to);
            case CASH_FLOW -> cashFlow(userId, from, to);
            case BUDGETS -> budgets(userId);
            case SUBSCRIPTIONS -> subscriptions(userId);
            case CREDIT_CARDS -> cards(userId);
            case NET_WORTH -> netWorth(userId);
            case TRANSACTIONS -> transactions(userId, from, to);
        };
        String base = "arth-" + type.name().toLowerCase().replace('_', '-') + "-" + from + (type == Type.MONTHLY ? "" : "-to-" + to);
        return switch (format) {
            case CSV -> new File(base + ".csv", "text/csv; charset=utf-8", CsvWriter.write(t));
            case XLSX -> new File(base + ".xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", XlsxWriter.write(t));
            case PDF -> new File(base + ".pdf", "application/pdf", PdfWriter.write(t));
        };
    }

    private static String range(LocalDate from, LocalDate to) {
        return D.format(from) + " to " + D.format(to);
    }

    private ReportTable monthly(UUID u, YearMonth ym) {
        var r = analytics.report(u, ym);
        List<String> summary = new ArrayList<>(List.of(
                "Income: " + InrFormat.format(r.income()), "Spending: " + InrFormat.format(r.spending()), "Invested: " + InrFormat.format(r.invested()),
                "Saved: " + InrFormat.format(r.savings()) + (r.savingsRate() != null ? " (" + r.savingsRate().stripTrailingZeros().toPlainString() + "% of income)" : ""),
                "Average daily spending: " + InrFormat.format(r.averageDailySpend())));
        if (r.inProgress()) summary.add("This month is still in progress.");
        List<List<Object>> cats = r.categories().stream().map(c -> List.<Object>of(c.name(), c.thisMonth(), c.lastMonth(), c.average3m(),
                c.changePct() == null ? "" : c.changePct().toPlainString() + "%")).toList();
        List<List<Object>> merch = r.merchants().stream().map(m -> List.<Object>of(m.name(), m.amount(), m.count())).toList();
        List<List<Object>> trend = r.trend().stream().map(p -> List.<Object>of(p.label(), p.income(), p.spending(), p.invested(), p.savings())).toList();
        List<List<Object>> ins = r.insights().stream().map(i -> List.<Object>of(i.title(), i.detail(), i.calculation())).toList();
        return new ReportTable("Monthly report: " + r.label(), null, summary, List.of(
                new ReportTable.Section("Categories", List.of("Category", "This month (₹)", "Last month (₹)", "3-month average (₹)", "Change"), cats),
                new ReportTable.Section("Top merchants", List.of("Merchant", "Amount (₹)", "Payments"), merch),
                new ReportTable.Section("Last six months", List.of("Month", "Income (₹)", "Spending (₹)", "Invested (₹)", "Saved (₹)"), trend),
                new ReportTable.Section("Insights", List.of("Insight", "Detail", "How it was worked out"), ins)));
    }

    private ReportTable annual(UUID u, LocalDate from, LocalDate to) {
        List<List<Object>> rows = new ArrayList<>();
        BigDecimal inc = BigDecimal.ZERO, sp = BigDecimal.ZERO, inv = BigDecimal.ZERO;
        for (YearMonth m = YearMonth.from(from); !m.isAfter(YearMonth.from(to)); m = m.plusMonths(1)) {
            LocalDate s = m.atDay(1).isBefore(from) ? from : m.atDay(1);
            LocalDate e = m.atEndOfMonth().isAfter(to) ? to : m.atEndOfMonth();
            var sum = dashboard.summary(u, s, e);
            rows.add(List.of(m.toString(), sum.income(), sum.expenses(), sum.invested(), sum.savings()));
            inc = inc.add(sum.income());
            sp = sp.add(sum.expenses());
            inv = inv.add(sum.invested());
        }
        var whole = dashboard.summary(u, from, to);
        List<List<Object>> cats = whole.expenseByCategory().stream().map(c -> List.<Object>of(c.name(), c.amount(), c.count())).toList();
        return new ReportTable("Annual summary", range(from, to), List.of("Income: " + InrFormat.format(inc), "Spending: " + InrFormat.format(sp),
                "Invested: " + InrFormat.format(inv), "Saved: " + InrFormat.format(inc.subtract(sp))), List.of(
                new ReportTable.Section("By month", List.of("Month", "Income (₹)", "Spending (₹)", "Invested (₹)", "Saved (₹)"), rows),
                new ReportTable.Section("By category", List.of("Category", "Amount (₹)", "Transactions"), cats)));
    }

    private ReportTable categories(UUID u, LocalDate from, LocalDate to) {
        var s = dashboard.summary(u, from, to);
        List<List<Object>> rows = s.expenseByCategory().stream().map(c -> List.<Object>of(c.name(), c.amount(), c.share() == null ? "" : c.share() + "%", c.count())).toList();
        List<List<Object>> inv = s.investedByCategory().stream().map(c -> List.<Object>of(c.name(), c.amount(), c.count())).toList();
        return new ReportTable("Spending by category", range(from, to), List.of("Total spending: " + InrFormat.format(s.expenses()),
                "Not yet categorised: " + InrFormat.format(s.pendingCategorisationAmount())), List.of(
                new ReportTable.Section("Spending", List.of("Category", "Amount (₹)", "Share", "Transactions"), rows),
                new ReportTable.Section("Investments", List.of("Category", "Amount (₹)", "Transactions"), inv)));
    }

    private ReportTable income(UUID u, LocalDate from, LocalDate to) {
        var s = dashboard.summary(u, from, to);
        List<List<Object>> rows = s.incomeByCategory().stream().map(c -> List.<Object>of(c.name(), c.amount(), c.count())).toList();
        return new ReportTable("Income", range(from, to), List.of("Total income: " + InrFormat.format(s.income())),
                List.of(new ReportTable.Section("Income by source", List.of("Category", "Amount (₹)", "Transactions"), rows)));
    }

    private ReportTable cashFlow(UUID u, LocalDate from, LocalDate to) {
        List<List<Object>> rows = new ArrayList<>();
        for (YearMonth m = YearMonth.from(from); !m.isAfter(YearMonth.from(to)); m = m.plusMonths(1)) {
            LocalDate s = m.atDay(1).isBefore(from) ? from : m.atDay(1);
            LocalDate e = m.atEndOfMonth().isAfter(to) ? to : m.atEndOfMonth();
            var sum = dashboard.summary(u, s, e);
            rows.add(List.of(m.toString(), sum.income(), sum.grossExpenses(), sum.refunds(), sum.invested(), sum.expenses(), sum.savings(), sum.transfersOut()));
        }
        return new ReportTable("Cash flow", range(from, to), List.of("Spending = expenses − refunds − investments. Transfers between your own accounts are shown separately and not counted."),
                List.of(new ReportTable.Section("Cash flow by month", List.of("Month", "Income (₹)", "Money out on expenses (₹)", "Refunds (₹)", "Invested (₹)",
                        "Spending (₹)", "Saved (₹)", "Transfers out (₹)"), rows)));
    }

    private ReportTable budgets(UUID u) {
        var o = budgets.overview(u);
        List<List<Object>> rows = o.budgets().stream().filter(b -> !b.archived()).map(b -> List.<Object>of(b.name(), b.period().name().toLowerCase(), b.periodLabel(),
                b.status().available(), b.status().spent(), b.status().remaining(), b.status().pctUsed() + "%", b.status().forecast(), b.status().state().replace('_', ' ').toLowerCase())).toList();
        return new ReportTable("Budgets", "Current periods", List.of(o.note()), List.of(new ReportTable.Section("Budgets",
                List.of("Budget", "Period", "Current period", "Available (₹)", "Spent (₹)", "Remaining (₹)", "Used", "Forecast (₹)", "Status"), rows)));
    }

    private ReportTable subscriptions(UUID u) {
        var s = recurring.summary(u);
        List<List<Object>> rows = s.items().stream().filter(i -> i.status() == RecurringPayment.Status.ACTIVE).map(i -> List.<Object>of(i.name(),
                i.kind().name().toLowerCase(), i.frequency().name().toLowerCase(), i.amount(), i.monthlyCost(), i.annualCost(),
                i.nextDue() == null ? "" : i.nextDue().toString(), i.priceChanged() ? "from " + i.previousAmount().toPlainString() : "")).toList();
        return new ReportTable("Subscriptions and recurring payments", null, List.of("Committed each month: " + InrFormat.format(s.monthlyCommitted()),
                "Subscriptions per year: " + InrFormat.format(s.annualSubscriptions())), List.of(new ReportTable.Section("Active",
                List.of("Name", "Type", "Repeats", "Amount (₹)", "Per month (₹)", "Per year (₹)", "Next due", "Price change"), rows)));
    }

    private ReportTable cards(UUID u) {
        var o = cards.overview(u);
        List<List<Object>> rows = new ArrayList<>();
        List<List<Object>> pays = new ArrayList<>();
        for (var c : o.cards()) {
            rows.add(List.of(c.name(), c.outstanding(), c.creditLimit() == null ? "" : c.creditLimit(), c.utilisationPct() == null ? "" : c.utilisationPct() + "%",
                    c.lastStatement() == null ? "" : c.lastStatement().statementDate().toString(), c.due() == null ? "" : c.due().statementAmount(),
                    c.due() == null ? "" : c.due().remaining(), c.lastStatement() == null ? "" : c.lastStatement().dueDate().toString(),
                    c.due() == null ? "set up needed" : c.due().state().name().replace('_', ' ').toLowerCase()));
            c.payments().forEach(p -> pays.add(List.of(c.name(), p.date().toString(), p.amount(), p.description() == null ? "" : p.description())));
        }
        return new ReportTable("Credit cards", null, List.of("Total outstanding: " + InrFormat.format(o.totalOutstanding()),
                "Statement amounts are calculated from the transactions in Arth; check them against your bank's statement."), List.of(
                new ReportTable.Section("Cards", List.of("Card", "Outstanding (₹)", "Limit (₹)", "Used", "Last statement", "Statement amount (₹)", "Still to pay (₹)", "Due date", "Status"), rows),
                new ReportTable.Section("Payments", List.of("Card", "Date", "Amount (₹)", "Description"), pays)));
    }

    private ReportTable netWorth(UUID u) {
        var n = netWorth.summary(u);
        List<List<Object>> assets = new ArrayList<>();
        n.assetGroups().forEach(g -> g.items().forEach(i -> assets.add(List.of(g.label(), i.name(), i.value(), i.invested() == null ? "" : i.invested(), i.gain() == null ? "" : i.gain()))));
        List<List<Object>> liab = new ArrayList<>();
        n.liabilityGroups().forEach(g -> g.items().forEach(i -> liab.add(List.of(g.label(), i.name(), i.value()))));
        List<List<Object>> hist = netWorth.history(u, 24).stream().map(p -> List.<Object>of(p.date().toString(), p.assets(), p.liabilities(), p.netWorth())).toList();
        return new ReportTable("Net worth", "As of " + D.format(TimeConfig.today(clock)), List.of("Assets: " + InrFormat.format(n.assets()),
                "Liabilities: " + InrFormat.format(n.liabilities()), "Net worth: " + InrFormat.format(n.netWorth()),
                n.estimatedCount() > 0 ? n.estimatedCount() + " value(s) are manual or calculated estimates." : "All values are from recent prices or your entries."), List.of(
                new ReportTable.Section("Assets", List.of("Group", "Item", "Value (₹)", "Invested (₹)", "Gain (₹)"), assets),
                new ReportTable.Section("Liabilities", List.of("Group", "Item", "Owed (₹)"), liab),
                new ReportTable.Section("History", List.of("Date", "Assets (₹)", "Liabilities (₹)", "Net worth (₹)"), hist)));
    }

    private ReportTable transactions(UUID u, LocalDate from, LocalDate to) {
        List<List<Object>> rows = jdbc.query("""
                SELECT CAST(t.txn_at AT TIME ZONE 'Asia/Kolkata' AS date) AS day, a.name AS account, COALESCE(m.name, t.counterparty) AS payee,
                       t.description, t.direction, t.amount, t.kind,
                       CASE WHEN p.id IS NOT NULL THEN p.name || ' > ' || c.name ELSE c.name END AS category, t.categorisation_status, t.notes
                FROM transactions t
                JOIN financial_accounts a ON a.id = t.account_id
                LEFT JOIN merchants m ON m.id = t.merchant_id
                LEFT JOIN categories c ON c.id = t.category_id LEFT JOIN categories p ON p.id = c.parent_id
                WHERE t.user_id = :u AND t.txn_at >= :from AND t.txn_at < :to AND t.duplicate_status = 'NONE'
                ORDER BY t.txn_at, t.created_at LIMIT 50000
                """, new MapSqlParameterSource("u", u).addValue("from", java.sql.Timestamp.from(TimeConfig.startOfDay(from)))
                .addValue("to", java.sql.Timestamp.from(TimeConfig.startOfDay(to.plusDays(1)))), (rs, i) -> {
            List<Object> r = new ArrayList<>();
            r.add(rs.getDate("day").toString());
            r.add(rs.getString("account"));
            r.add(nz(rs.getString("payee")));
            r.add(nz(rs.getString("description")));
            r.add("DEBIT".equals(rs.getString("direction")) ? "Out" : "In");
            r.add(rs.getBigDecimal("amount"));
            r.add(rs.getString("kind").toLowerCase());
            r.add(rs.getString("category") == null ? ("PENDING".equals(rs.getString("categorisation_status")) ? "Needs review" : "") : rs.getString("category"));
            r.add(nz(rs.getString("notes")));
            return r;
        });
        return new ReportTable("Transactions", range(from, to), List.of(rows.size() + " transactions (suspected duplicates left out)"),
                List.of(new ReportTable.Section("Transactions", List.of("Date", "Account", "Payee", "Description", "In/Out", "Amount (₹)", "Type", "Category", "Note"), rows)));
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
