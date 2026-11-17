package com.expensetracker.budget;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Pure budget calculations so they can be tested and explained. */
public final class BudgetMath {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    public record Status(BigDecimal limit, BigDecimal carriedOver, BigDecimal available, BigDecimal spent, BigDecimal remaining,
                         BigDecimal pctUsed, BigDecimal forecast, BigDecimal projectedOverspend, BigDecimal dailyAllowance,
                         String state) {
    }

    private BudgetMath() {
    }

    /**
     * @param limit      the budget amount for the period
     * @param carried    rollover from the previous period (positive = unspent carried in, negative = overspend carried in); zero without rollover
     * @param spent      net spending so far in the period
     * @param daysElapsed days elapsed including today (0 if the period has not started)
     * @param daysTotal  period length
     */
    public static Status status(BigDecimal limit, BigDecimal carried, BigDecimal spent, long daysElapsed, long daysTotal) {
        BigDecimal available = limit.add(carried).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
        BigDecimal remaining = available.subtract(spent).setScale(2, RoundingMode.HALF_UP);
        BigDecimal pct = available.signum() == 0
                ? (spent.signum() > 0 ? HUNDRED : BigDecimal.ZERO)
                : spent.multiply(HUNDRED).divide(available, 1, RoundingMode.HALF_UP);
        BigDecimal forecast = daysElapsed <= 0 ? spent
                : spent.multiply(BigDecimal.valueOf(daysTotal)).divide(BigDecimal.valueOf(daysElapsed), 2, RoundingMode.HALF_UP);
        BigDecimal over = forecast.subtract(available).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
        long daysLeft = Math.max(1, daysTotal - daysElapsed + (daysElapsed > 0 ? 1 : 0));
        BigDecimal daily = remaining.signum() <= 0 ? BigDecimal.ZERO.setScale(2)
                : remaining.divide(BigDecimal.valueOf(daysLeft), 2, RoundingMode.HALF_UP);
        String state = remaining.signum() < 0 ? "OVER"
                : pct.compareTo(BigDecimal.valueOf(90)) >= 0 ? "NEAR_LIMIT"
                : over.signum() > 0 ? "AT_RISK"
                : "ON_TRACK";
        return new Status(limit, carried, available, spent.setScale(2, RoundingMode.HALF_UP), remaining, pct, forecast, over, daily, state);
    }

    /** Rollover carries the previous period's leftover (or overspend) one period forward. */
    public static BigDecimal carryOver(boolean rollover, BigDecimal previousLimit, BigDecimal previousSpent) {
        if (!rollover) return BigDecimal.ZERO.setScale(2);
        return previousLimit.subtract(previousSpent).setScale(2, RoundingMode.HALF_UP);
    }

    /** Thresholds (e.g. 50, 75, 90, 100) reached at this usage, highest last. */
    public static List<Integer> crossed(List<Integer> thresholds, BigDecimal pctUsed) {
        List<Integer> out = new ArrayList<>();
        for (int t : thresholds.stream().sorted().toList()) {
            if (pctUsed.compareTo(BigDecimal.valueOf(t)) >= 0) out.add(t);
        }
        return out;
    }

    public static List<Integer> parseThresholds(String csv) {
        if (csv == null || csv.isBlank()) return List.of(50, 75, 90, 100);
        return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).map(Integer::parseInt)
                .filter(t -> t > 0 && t <= 200).distinct().sorted().toList();
    }

    /**
     * Suggested budget: the average of past complete periods, rounded up to a friendly step
     * (₹100 for weekly, ₹500 for monthly, ₹5,000 for yearly). Empty history gives null.
     */
    public static BigDecimal recommend(List<BigDecimal> pastPeriods, BudgetPeriods.Period period) {
        List<BigDecimal> vals = pastPeriods.stream().filter(v -> v != null && v.signum() > 0).toList();
        if (vals.isEmpty()) return null;
        BigDecimal avg = vals.stream().reduce(BigDecimal.ZERO, BigDecimal::add).divide(BigDecimal.valueOf(vals.size()), 2, RoundingMode.HALF_UP);
        BigDecimal step = switch (period) {
            case WEEKLY -> BigDecimal.valueOf(100);
            case MONTHLY -> BigDecimal.valueOf(500);
            case YEARLY -> BigDecimal.valueOf(5000);
        };
        return avg.divide(step, 0, RoundingMode.CEILING).multiply(step).setScale(2);
    }
}
