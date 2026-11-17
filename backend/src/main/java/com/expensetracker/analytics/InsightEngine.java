package com.expensetracker.analytics;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Deterministic insights from monthly figures. Every insight carries the numbers it is based on, so the UI can
 * show the calculation. "Last month" means the last complete calendar month; "baseline" is the average of the
 * three months before it (only months that have data).
 */
public final class InsightEngine {

    public enum Type { CATEGORY_SPIKE, MERCHANT_INCREASE, UNUSUAL_TRANSACTION, LIFESTYLE_INFLATION, OVERSPENDING, SAVINGS_RATE_DOWN, SAVINGS_RATE_UP, TOP_CATEGORY }

    public record Insight(Type type, String severity, String title, String detail, String calculation, String link) {
    }

    /** Month totals oldest first; the last element is the month being analysed. */
    public record MonthTotals(String month, BigDecimal income, BigDecimal spending) {
    }

    public record SeriesByName(String name, String id, List<BigDecimal> monthly) {
    }

    public record Large(String id, String label, String category, BigDecimal amount, BigDecimal typical, String date) {
    }

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private InsightEngine() {
    }

    public static List<Insight> analyse(List<MonthTotals> months, List<SeriesByName> categories, List<SeriesByName> merchants, List<Large> unusual) {
        List<Insight> out = new ArrayList<>();
        if (months.isEmpty()) return out;
        MonthTotals last = months.get(months.size() - 1);
        List<MonthTotals> base = months.subList(Math.max(0, months.size() - 4), months.size() - 1).stream()
                .filter(m -> m.spending().signum() > 0 || m.income().signum() > 0).toList();

        if (last.income().signum() > 0 && last.spending().compareTo(last.income()) > 0) {
            BigDecimal gap = last.spending().subtract(last.income());
            out.add(new Insight(Type.OVERSPENDING, "WARNING", "You spent more than you earned in " + last.month(),
                    "Spending was " + InrFormat.format(gap) + " above income.",
                    "spending " + InrFormat.format(last.spending()) + " − income " + InrFormat.format(last.income()) + " = " + InrFormat.format(gap), "/analytics"));
        }

        BigDecimal rateNow = rate(last);
        if (rateNow != null && !base.isEmpty()) {
            List<BigDecimal> rates = base.stream().map(InsightEngine::rate).filter(r -> r != null).toList();
            if (!rates.isEmpty()) {
                BigDecimal avg = avg(rates).setScale(1, RoundingMode.HALF_UP);
                BigDecimal diff = rateNow.subtract(avg);
                if (diff.abs().compareTo(BigDecimal.TEN) >= 0) {
                    boolean down = diff.signum() < 0;
                    out.add(new Insight(down ? Type.SAVINGS_RATE_DOWN : Type.SAVINGS_RATE_UP, down ? "WARNING" : "POSITIVE",
                            "Savings rate " + (down ? "fell" : "rose") + " to " + rateNow.toPlainString() + "%",
                            "Your usual savings rate is " + avg.toPlainString() + "%.",
                            "(income − spending) ÷ income for " + last.month() + " = " + rateNow.toPlainString() + "%; average of previous " + rates.size() + " months = " + avg.toPlainString() + "%",
                            "/analytics"));
                }
            }
        }

        if (months.size() >= 6) {
            List<MonthTotals> recent = months.subList(months.size() - 3, months.size());
            List<MonthTotals> earlier = months.subList(months.size() - 6, months.size() - 3);
            BigDecimal r = avg(recent.stream().map(MonthTotals::spending).toList());
            BigDecimal e = avg(earlier.stream().map(MonthTotals::spending).toList());
            BigDecimal ri = avg(recent.stream().map(MonthTotals::income).toList());
            BigDecimal ei = avg(earlier.stream().map(MonthTotals::income).toList());
            if (e.signum() > 0) {
                BigDecimal spendGrowth = r.subtract(e).multiply(HUNDRED).divide(e, 1, RoundingMode.HALF_UP);
                BigDecimal incomeGrowth = ei.signum() > 0 ? ri.subtract(ei).multiply(HUNDRED).divide(ei, 1, RoundingMode.HALF_UP) : BigDecimal.ZERO;
                if (spendGrowth.compareTo(BigDecimal.valueOf(15)) >= 0 && spendGrowth.subtract(incomeGrowth).compareTo(BigDecimal.TEN) >= 0) {
                    out.add(new Insight(Type.LIFESTYLE_INFLATION, "WARNING", "Spending is creeping up",
                            "Your average monthly spending rose " + spendGrowth.toPlainString() + "% while income changed " + incomeGrowth.toPlainString() + "%.",
                            "avg spending last 3 months " + InrFormat.format(r) + " vs previous 3 months " + InrFormat.format(e), "/analytics"));
                }
            }
        }

        for (SeriesByName c : categories) {
            Insight i = spike(c, Type.CATEGORY_SPIKE, BigDecimal.valueOf(30), BigDecimal.valueOf(1000), last.month(), "/transactions?categoryId=" + c.id());
            if (i != null) out.add(i);
        }
        for (SeriesByName m : merchants) {
            Insight i = spike(m, Type.MERCHANT_INCREASE, BigDecimal.valueOf(50), BigDecimal.valueOf(1000), last.month(), "/transactions?merchantId=" + m.id());
            if (i != null) out.add(i);
        }
        for (Large l : unusual) {
            out.add(new Insight(Type.UNUSUAL_TRANSACTION, "INFO", "Unusually large: " + l.label(),
                    InrFormat.format(l.amount()) + " on " + l.date() + " in " + l.category() + ".",
                    "typical " + l.category() + " transaction is " + InrFormat.format(l.typical()) + "; this one is "
                            + l.amount().divide(l.typical(), 1, RoundingMode.HALF_UP).toPlainString() + "× that",
                    "/transactions/" + l.id()));
        }

        categories.stream().filter(c -> !c.monthly().isEmpty() && lastOf(c).signum() > 0)
                .max(Comparator.comparing(InsightEngine::lastOf))
                .ifPresent(top -> {
                    if (last.spending().signum() > 0) {
                        BigDecimal share = lastOf(top).multiply(HUNDRED).divide(last.spending(), 0, RoundingMode.HALF_UP);
                        out.add(new Insight(Type.TOP_CATEGORY, "INFO", top.name() + " was your biggest category",
                                InrFormat.format(lastOf(top)) + ", " + share.toPlainString() + "% of spending in " + last.month() + ".",
                                InrFormat.format(lastOf(top)) + " ÷ " + InrFormat.format(last.spending()), "/transactions?categoryId=" + top.id()));
                    }
                });
        return out;
    }

    static Insight spike(SeriesByName s, Type type, BigDecimal minPct, BigDecimal minDelta, String month, String link) {
        if (s.monthly().size() < 2) return null;
        BigDecimal now = lastOf(s);
        List<BigDecimal> prior = s.monthly().subList(Math.max(0, s.monthly().size() - 4), s.monthly().size() - 1).stream().filter(v -> v.signum() > 0).toList();
        if (prior.isEmpty()) return null;
        BigDecimal base = avg(prior);
        BigDecimal delta = now.subtract(base);
        if (base.signum() <= 0 || delta.compareTo(minDelta) < 0) return null;
        BigDecimal pct = delta.multiply(HUNDRED).divide(base, 0, RoundingMode.HALF_UP);
        if (pct.compareTo(minPct) < 0) return null;
        String what = type == Type.MERCHANT_INCREASE ? "at " + s.name() : "on " + s.name();
        return new Insight(type, "WARNING", "Spending " + what + " up " + pct.toPlainString() + "%",
                InrFormat.format(now) + " in " + month + " against a usual " + InrFormat.format(base) + ".",
                InrFormat.format(now) + " vs average of previous " + prior.size() + " months " + InrFormat.format(base) + " (+" + InrFormat.format(delta) + ")", link);
    }

    private static BigDecimal lastOf(SeriesByName s) {
        return s.monthly().get(s.monthly().size() - 1);
    }

    static BigDecimal rate(MonthTotals m) {
        if (m.income().signum() <= 0) return null;
        return m.income().subtract(m.spending()).multiply(HUNDRED).divide(m.income(), 1, RoundingMode.HALF_UP);
    }

    static BigDecimal avg(List<BigDecimal> xs) {
        if (xs.isEmpty()) return BigDecimal.ZERO;
        return xs.stream().reduce(BigDecimal.ZERO, BigDecimal::add).divide(BigDecimal.valueOf(xs.size()), 2, RoundingMode.HALF_UP);
    }

    /** Helper for callers: index month → value maps into aligned lists. */
    public static List<BigDecimal> align(List<String> months, Map<String, BigDecimal> values) {
        return months.stream().map(m -> values.getOrDefault(m, BigDecimal.ZERO)).toList();
    }
}
