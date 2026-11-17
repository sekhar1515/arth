package com.expensetracker.goal;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/** Goal projections. Returns are compounded monthly at expectedReturnPct / 12. */
public final class GoalMath {

    private static final MathContext MC = MathContext.DECIMAL64;
    private static final int MAX_MONTHS = 600;

    public record Projection(BigDecimal saved, BigDecimal remaining, BigDecimal progressPct, Integer monthsLeft,
                             BigDecimal requiredMonthly, BigDecimal recentMonthly, LocalDate projectedDate, String state) {
    }

    private GoalMath() {
    }

    /**
     * @param recentMonthly average contribution per month recently (used to project a completion date)
     */
    public static Projection project(BigDecimal target, BigDecimal saved, LocalDate targetDate, BigDecimal expectedReturnPct,
                                     BigDecimal recentMonthly, LocalDate today) {
        BigDecimal remaining = target.subtract(saved).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
        BigDecimal pct = saved.max(BigDecimal.ZERO).multiply(BigDecimal.valueOf(100)).divide(target, 1, RoundingMode.HALF_UP).min(BigDecimal.valueOf(100));
        BigDecimal i = monthlyRate(expectedReturnPct);
        Integer months = targetDate == null ? null : (int) Math.max(0, ChronoUnit.MONTHS.between(today.withDayOfMonth(1), targetDate.withDayOfMonth(1)));
        BigDecimal required = null;
        if (remaining.signum() > 0 && months != null) required = requiredMonthly(target, saved, i, months);
        LocalDate projected = remaining.signum() == 0 ? today : projectDate(target, saved, i, recentMonthly, today);
        String state;
        if (remaining.signum() == 0) state = "ACHIEVED";
        else if (targetDate == null) state = projected == null ? "NO_CONTRIBUTIONS" : "IN_PROGRESS";
        else if (projected == null) state = "NO_CONTRIBUTIONS";
        else if (!projected.isAfter(targetDate)) state = "ON_TRACK";
        else state = "BEHIND";
        return new Projection(saved.setScale(2, RoundingMode.HALF_UP), remaining, pct, months, required,
                recentMonthly == null ? null : recentMonthly.setScale(2, RoundingMode.HALF_UP), projected, state);
    }

    static BigDecimal monthlyRate(BigDecimal annualPct) {
        if (annualPct == null || annualPct.signum() == 0) return BigDecimal.ZERO;
        return annualPct.divide(BigDecimal.valueOf(1200), MC);
    }

    /** Monthly amount needed so that saved (growing at i) plus contributions (paid at month end) reaches target in n months. */
    public static BigDecimal requiredMonthly(BigDecimal target, BigDecimal saved, BigDecimal i, int n) {
        if (n <= 0) return target.subtract(saved).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
        BigDecimal growth = BigDecimal.ONE.add(i).pow(n, MC);
        BigDecimal needed = target.subtract(saved.multiply(growth, MC));
        if (needed.signum() <= 0) return BigDecimal.ZERO.setScale(2);
        if (i.signum() == 0) return needed.divide(BigDecimal.valueOf(n), 2, RoundingMode.CEILING);
        BigDecimal factor = growth.subtract(BigDecimal.ONE).divide(i, MC);
        return needed.divide(factor, 2, RoundingMode.CEILING);
    }

    static LocalDate projectDate(BigDecimal target, BigDecimal saved, BigDecimal i, BigDecimal monthly, LocalDate today) {
        BigDecimal value = saved;
        boolean grows = i.signum() > 0 && saved.signum() > 0;
        if ((monthly == null || monthly.signum() <= 0) && !grows) return null;
        BigDecimal m = monthly == null ? BigDecimal.ZERO : monthly.max(BigDecimal.ZERO);
        for (int k = 1; k <= MAX_MONTHS; k++) {
            value = value.multiply(BigDecimal.ONE.add(i), MC).add(m);
            if (value.compareTo(target) >= 0) return today.plusMonths(k);
        }
        return null;
    }
}
