package com.expensetracker.budget;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.Month;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;

/**
 * Budget period arithmetic. Weeks start on Monday; months are calendar months; years follow the
 * Indian financial year (1 April to 31 March).
 */
public final class BudgetPeriods {

    public enum Period { WEEKLY, MONTHLY, YEARLY }

    /** Half-open range [start, endExclusive). */
    public record Range(LocalDate start, LocalDate endExclusive) {
        public long days() {
            return ChronoUnit.DAYS.between(start, endExclusive);
        }

        public boolean contains(LocalDate d) {
            return !d.isBefore(start) && d.isBefore(endExclusive);
        }

        /** Days elapsed including today (1 on the first day), capped at the period length. */
        public long daysElapsed(LocalDate today) {
            if (today.isBefore(start)) return 0;
            return Math.min(days(), ChronoUnit.DAYS.between(start, today) + 1);
        }
    }

    private BudgetPeriods() {
    }

    public static Range containing(Period period, LocalDate day) {
        return switch (period) {
            case WEEKLY -> {
                LocalDate s = day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                yield new Range(s, s.plusWeeks(1));
            }
            case MONTHLY -> {
                LocalDate s = day.withDayOfMonth(1);
                yield new Range(s, s.plusMonths(1));
            }
            case YEARLY -> {
                int fyStartYear = day.getMonthValue() >= 4 ? day.getYear() : day.getYear() - 1;
                LocalDate s = LocalDate.of(fyStartYear, Month.APRIL, 1);
                yield new Range(s, s.plusYears(1));
            }
        };
    }

    public static Range previous(Period period, Range r) {
        return containing(period, r.start().minusDays(1));
    }

    public static String label(Period period, Range r) {
        return switch (period) {
            case WEEKLY -> "Week of " + r.start().getDayOfMonth() + " " + r.start().getMonth().getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH);
            case MONTHLY -> r.start().getMonth().getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH) + " " + r.start().getYear();
            case YEARLY -> "FY " + r.start().getYear() + "-" + String.format("%02d", (r.start().getYear() + 1) % 100);
        };
    }
}
