package com.expensetracker.analytics;

import com.expensetracker.common.TimeConfig;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Monthly report and insights. All figures come from your transactions; nothing is estimated except where labelled. */
@Service
public class AnalyticsService {

    public record CategoryRow(UUID categoryId, String name, String color, BigDecimal thisMonth, BigDecimal lastMonth, BigDecimal average3m,
                              BigDecimal changePct) {
    }

    public record MerchantRow(UUID merchantId, String name, BigDecimal amount, long count) {
    }

    public record MonthPoint(String month, String label, BigDecimal income, BigDecimal spending, BigDecimal invested, BigDecimal savings, BigDecimal savingsRate) {
    }

    public record Report(String month, String label, boolean inProgress, BigDecimal income, BigDecimal spending, BigDecimal invested,
                         BigDecimal savings, BigDecimal savingsRate, BigDecimal previousIncome, BigDecimal previousSpending,
                         BigDecimal averageDailySpend, BigDecimal pendingCategorisation, List<MonthPoint> trend, List<CategoryRow> categories,
                         List<MerchantRow> merchants, List<InsightEngine.Insight> insights, String note) {
    }

    private static final String CAT_SERIES = """
            WITH lines AS (
              SELECT to_char(date_trunc('month', t.txn_at AT TIME ZONE 'Asia/Kolkata'), 'YYYY-MM') AS month,
                     COALESCE(s.category_id, t.category_id) AS category_id,
                     CASE WHEN t.direction = 'DEBIT' THEN COALESCE(s.amount, t.amount) ELSE -COALESCE(s.amount, t.amount) END AS amount
              FROM transactions t LEFT JOIN transaction_splits s ON s.transaction_id = t.id
              WHERE t.user_id = :userId AND t.txn_at >= :from AND t.txn_at < :to AND t.status = 'POSTED' AND t.duplicate_status = 'NONE'
                AND t.categorisation_status IN ('CONFIRMED', 'AUTO') AND t.kind IN ('EXPENSE', 'REFUND')
            )
            SELECT l.month, top.id AS top_id, top.name, top.color, SUM(l.amount) AS total
            FROM lines l JOIN categories c ON c.id = l.category_id
            JOIN categories top ON top.id = COALESCE(c.parent_id, c.id)
            WHERE NOT top.is_investment
            GROUP BY l.month, top.id, top.name, top.color
            """;

    private static final String MERCHANT_SERIES = """
            SELECT to_char(date_trunc('month', t.txn_at AT TIME ZONE 'Asia/Kolkata'), 'YYYY-MM') AS month, m.id, m.name,
                   SUM(t.amount) AS total, COUNT(*) AS n
            FROM transactions t JOIN merchants m ON m.id = t.merchant_id
            LEFT JOIN categories c ON c.id = t.category_id LEFT JOIN categories p ON p.id = c.parent_id
            WHERE t.user_id = :userId AND t.txn_at >= :from AND t.txn_at < :to AND t.status = 'POSTED' AND t.duplicate_status = 'NONE'
              AND t.direction = 'DEBIT' AND t.kind = 'EXPENSE' AND NOT COALESCE(p.is_investment, c.is_investment, FALSE)
            GROUP BY 1, m.id, m.name
            """;

    /** A transaction this month that is at least 3× the median of its category over the previous six months (and ≥ ₹2,000). */
    private static final String UNUSUAL = """
            WITH hist AS (
              SELECT t.category_id, percentile_cont(0.5) WITHIN GROUP (ORDER BY t.amount) AS median, COUNT(*) AS n
              FROM transactions t
              WHERE t.user_id = :userId AND t.txn_at >= :histFrom AND t.txn_at < :from AND t.status = 'POSTED' AND t.duplicate_status = 'NONE'
                AND t.direction = 'DEBIT' AND t.kind = 'EXPENSE' AND t.category_id IS NOT NULL
              GROUP BY t.category_id HAVING COUNT(*) >= 4
            )
            SELECT t.id, COALESCE(m.name, t.notes, t.counterparty, t.description) AS label, c.name AS category, t.amount, h.median,
                   CAST(t.txn_at AT TIME ZONE 'Asia/Kolkata' AS date) AS day
            FROM transactions t JOIN hist h ON h.category_id = t.category_id
            JOIN categories c ON c.id = t.category_id LEFT JOIN merchants m ON m.id = t.merchant_id
            WHERE t.user_id = :userId AND t.txn_at >= :from AND t.txn_at < :to AND t.status = 'POSTED' AND t.duplicate_status = 'NONE'
              AND t.direction = 'DEBIT' AND t.kind = 'EXPENSE' AND t.amount >= 2000 AND t.amount >= 3 * h.median
            ORDER BY t.amount DESC LIMIT 5
            """;

    private final DashboardService dashboard;
    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    public AnalyticsService(DashboardService dashboard, NamedParameterJdbcTemplate jdbc, Clock clock) {
        this.dashboard = dashboard;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Report report(UUID userId, YearMonth month) {
        LocalDate today = TimeConfig.today(clock);
        YearMonth ym = month != null ? month : YearMonth.from(today);
        boolean inProgress = ym.equals(YearMonth.from(today));
        List<YearMonth> months = new ArrayList<>();
        for (int i = 5; i >= 0; i--) months.add(ym.minusMonths(i));
        List<String> keys = months.stream().map(YearMonth::toString).toList();

        List<MonthPoint> trend = new ArrayList<>();
        List<InsightEngine.MonthTotals> totals = new ArrayList<>();
        DashboardService.Summary current = null, previous = null;
        for (YearMonth m : months) {
            LocalDate end = m.equals(YearMonth.from(today)) ? today : m.atEndOfMonth();
            if (m.atDay(1).isAfter(today)) continue;
            DashboardService.Summary s = dashboard.summary(userId, m.atDay(1), end);
            trend.add(new MonthPoint(m.toString(), label(m), s.income(), s.expenses(), s.invested(), s.savings(), s.savingsRate()));
            totals.add(new InsightEngine.MonthTotals(label(m), s.income(), s.expenses()));
            if (m.equals(ym)) current = s;
            if (m.equals(ym.minusMonths(1))) previous = s;
        }
        if (current == null) current = dashboard.summary(userId, ym.atDay(1), ym.atEndOfMonth());

        MapSqlParameterSource p = new MapSqlParameterSource("userId", userId)
                .addValue("from", ts(months.get(0).atDay(1))).addValue("to", ts(ym.plusMonths(1).atDay(1)));
        Map<UUID, String[]> catMeta = new LinkedHashMap<>();
        Map<UUID, Map<String, BigDecimal>> catVals = new LinkedHashMap<>();
        jdbc.query(CAT_SERIES, p, rs -> {
            UUID id = (UUID) rs.getObject("top_id");
            catMeta.putIfAbsent(id, new String[]{rs.getString("name"), rs.getString("color")});
            catVals.computeIfAbsent(id, k -> new LinkedHashMap<>()).put(rs.getString("month"), rs.getBigDecimal("total"));
        });
        Map<UUID, String> merchName = new LinkedHashMap<>();
        Map<UUID, Map<String, BigDecimal>> merchVals = new LinkedHashMap<>();
        Map<UUID, Long> merchCount = new LinkedHashMap<>();
        jdbc.query(MERCHANT_SERIES, p, rs -> {
            UUID id = (UUID) rs.getObject("id");
            merchName.putIfAbsent(id, rs.getString("name"));
            merchVals.computeIfAbsent(id, k -> new LinkedHashMap<>()).put(rs.getString("month"), rs.getBigDecimal("total"));
            if (ym.toString().equals(rs.getString("month"))) merchCount.put(id, rs.getLong("n"));
        });

        String thisKey = ym.toString(), lastKey = ym.minusMonths(1).toString();
        List<CategoryRow> rows = new ArrayList<>();
        List<InsightEngine.SeriesByName> catSeries = new ArrayList<>();
        for (var e : catVals.entrySet()) {
            List<BigDecimal> series = InsightEngine.align(keys, e.getValue());
            BigDecimal now = e.getValue().getOrDefault(thisKey, BigDecimal.ZERO);
            BigDecimal last = e.getValue().getOrDefault(lastKey, BigDecimal.ZERO);
            BigDecimal avg3 = series.subList(2, 5).stream().reduce(BigDecimal.ZERO, BigDecimal::add).divide(BigDecimal.valueOf(3), 2, RoundingMode.HALF_UP);
            BigDecimal change = last.signum() > 0 ? now.subtract(last).multiply(BigDecimal.valueOf(100)).divide(last, 1, RoundingMode.HALF_UP) : null;
            if (now.signum() != 0 || last.signum() != 0) rows.add(new CategoryRow(e.getKey(), catMeta.get(e.getKey())[0], catMeta.get(e.getKey())[1], now, last, avg3, change));
            if (!inProgress) catSeries.add(new InsightEngine.SeriesByName(catMeta.get(e.getKey())[0], e.getKey().toString(), series));
        }
        rows.sort((a, b) -> b.thisMonth().compareTo(a.thisMonth()));
        List<MerchantRow> merchants = merchVals.entrySet().stream()
                .filter(e -> e.getValue().containsKey(thisKey))
                .map(e -> new MerchantRow(e.getKey(), merchName.get(e.getKey()), e.getValue().get(thisKey), merchCount.getOrDefault(e.getKey(), 0L)))
                .sorted((a, b) -> b.amount().compareTo(a.amount())).limit(10).toList();
        List<InsightEngine.SeriesByName> merchSeries = inProgress ? List.of() : merchVals.entrySet().stream()
                .map(e -> new InsightEngine.SeriesByName(merchName.get(e.getKey()), e.getKey().toString(), InsightEngine.align(keys, e.getValue()))).toList();

        List<InsightEngine.Large> unusual = jdbc.query(UNUSUAL, new MapSqlParameterSource("userId", userId)
                .addValue("histFrom", ts(ym.minusMonths(6).atDay(1))).addValue("from", ts(ym.atDay(1))).addValue("to", ts(ym.plusMonths(1).atDay(1))), (rs, i) ->
                new InsightEngine.Large(rs.getObject("id").toString(), rs.getString("label"), rs.getString("category"), rs.getBigDecimal("amount"),
                        rs.getBigDecimal("median").setScale(2, RoundingMode.HALF_UP), rs.getDate("day").toLocalDate().toString()));

        // month-over-month comparisons are only fair once a month is complete; in-progress months get the unusual-transaction check only
        List<InsightEngine.Insight> insights = inProgress
                ? InsightEngine.analyse(List.of(new InsightEngine.MonthTotals(label(ym), BigDecimal.ZERO, BigDecimal.ZERO)), List.of(), List.of(), unusual)
                : InsightEngine.analyse(totals, catSeries, merchSeries, unusual);

        long days = inProgress ? today.getDayOfMonth() : ym.lengthOfMonth();
        BigDecimal daily = current.expenses().divide(BigDecimal.valueOf(Math.max(1, days)), 2, RoundingMode.HALF_UP);
        return new Report(thisKey, label(ym), inProgress, current.income(), current.expenses(), current.invested(), current.savings(), current.savingsRate(),
                previous == null ? null : previous.income(), previous == null ? null : previous.expenses(), daily, current.pendingCategorisationAmount(),
                trend, rows, merchants, insights,
                (inProgress ? "This month is still in progress, so comparisons with earlier months are left out. " : "")
                        + "Spending excludes transfers and money moved into investments. Category figures count only categorised transactions; "
                        + InrFormat.format(current.pendingCategorisationAmount()) + " is still waiting in Needs Review.");
    }

    private static String label(YearMonth m) {
        return m.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH) + " " + m.getYear();
    }

    private static java.sql.Timestamp ts(LocalDate d) {
        return java.sql.Timestamp.from(TimeConfig.startOfDay(d));
    }
}
