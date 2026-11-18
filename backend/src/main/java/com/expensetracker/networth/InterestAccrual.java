package com.expensetracker.networth;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.Month;
import java.time.temporal.TemporalAdjusters;

/**
 * How EPF and PPF interest works: worked out every month on the balance (rate ÷ 12) and credited into the account
 * once a year, on 31 March. Until then the earned interest is held separately (shown as an estimate).
 */
public final class InterestAccrual {

    public record State(BigDecimal balance, BigDecimal accrued, LocalDate accruedTo) {
    }

    private InterestAccrual() {
    }

    /**
     * Adds interest for every month that ended after {@code from.accruedTo()} and on or before {@code today}.
     * A month counts once it has ended; on 31 March the year's interest moves into the balance.
     */
    public static State accrue(State from, BigDecimal annualRatePercent, LocalDate today) {
        if (annualRatePercent == null || annualRatePercent.signum() <= 0 || from.accruedTo() == null || from.balance() == null) return from;
        BigDecimal balance = from.balance();
        BigDecimal accrued = from.accrued() == null ? BigDecimal.ZERO : from.accrued();
        LocalDate cursor = from.accruedTo();
        BigDecimal monthlyRate = annualRatePercent.divide(BigDecimal.valueOf(1200), 10, RoundingMode.HALF_UP);
        while (true) {
            LocalDate monthEnd = cursor.with(TemporalAdjusters.lastDayOfMonth());
            if (!monthEnd.isAfter(cursor)) monthEnd = cursor.plusDays(1).with(TemporalAdjusters.lastDayOfMonth());
            if (monthEnd.isAfter(today)) break;
            // a part month at the start (balance entered mid-month) earns for the days it was there
            long daysInMonth = monthEnd.lengthOfMonth();
            long days = cursor.getMonth() == monthEnd.getMonth() && cursor.getYear() == monthEnd.getYear()
                    ? monthEnd.getDayOfMonth() - cursor.getDayOfMonth() : daysInMonth;
            BigDecimal interest = balance.multiply(monthlyRate).multiply(BigDecimal.valueOf(days)).divide(BigDecimal.valueOf(daysInMonth), 2, RoundingMode.HALF_UP);
            accrued = accrued.add(interest);
            if (monthEnd.getMonth() == Month.MARCH) { // financial year ends: interest credited
                balance = balance.add(accrued);
                accrued = BigDecimal.ZERO;
            }
            cursor = monthEnd;
        }
        return new State(balance.setScale(2, RoundingMode.HALF_UP), accrued.setScale(2, RoundingMode.HALF_UP), cursor);
    }

    /** Official default rates, used when you don't enter one. */
    public static BigDecimal defaultRate(AssetClass cls) {
        return switch (cls) {
            case EPF -> new BigDecimal("8.25");  // EPFO, FY 2024-25
            case PPF -> new BigDecimal("7.10");  // Government of India, current quarter
            default -> null;
        };
    }
}
