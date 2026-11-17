package com.expensetracker.creditcard;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;

/**
 * Credit-card billing cycle arithmetic. A statement is generated on {@code statementDay} each month and
 * covers purchases from the day after the previous statement up to and including the statement day.
 */
public final class CardCycle {

    public record Cycle(LocalDate start, LocalDate statementDate, LocalDate dueDate) {
    }

    public enum DueState { NO_DUES, PAID, PARTIALLY_PAID, DUE, OVERDUE }

    public record Due(BigDecimal statementAmount, BigDecimal paidSinceStatement, BigDecimal remaining, BigDecimal minimumDue,
                      BigDecimal minimumRemaining, DueState state, long daysToDue) {
    }

    private CardCycle() {
    }

    /** The cycle that is still open on {@code today} (its statement date is today or later). */
    public static Cycle current(int statementDay, int dueDaysAfter, LocalDate today) {
        LocalDate thisMonth = onDay(YearMonth.from(today), statementDay);
        LocalDate statement = today.isAfter(thisMonth) ? onDay(YearMonth.from(today).plusMonths(1), statementDay) : thisMonth;
        LocalDate prevStatement = onDay(YearMonth.from(statement).minusMonths(1), statementDay);
        return new Cycle(prevStatement.plusDays(1), statement, statement.plusDays(dueDaysAfter));
    }

    /** The most recent statement already generated before or on {@code today}. */
    public static Cycle lastStatement(int statementDay, int dueDaysAfter, LocalDate today) {
        Cycle cur = current(statementDay, dueDaysAfter, today);
        LocalDate statement = cur.statementDate().equals(today) ? today : onDay(YearMonth.from(cur.statementDate()).minusMonths(1), statementDay);
        LocalDate prev = onDay(YearMonth.from(statement).minusMonths(1), statementDay);
        return new Cycle(prev.plusDays(1), statement, statement.plusDays(dueDaysAfter));
    }

    private static LocalDate onDay(YearMonth ym, int day) {
        return ym.atDay(Math.min(day, ym.lengthOfMonth()));
    }

    /**
     * @param statementAmount outstanding at the statement close (positive = you owe)
     * @param paidSince       payments/credits received after the statement date
     */
    public static Due due(BigDecimal statementAmount, BigDecimal paidSince, BigDecimal minPct, BigDecimal minFloor,
                          LocalDate dueDate, LocalDate today) {
        BigDecimal owed = statementAmount.max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
        BigDecimal paid = paidSince.max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
        BigDecimal remaining = owed.subtract(paid).max(BigDecimal.ZERO);
        BigDecimal min = owed.signum() == 0 ? BigDecimal.ZERO.setScale(2)
                : owed.multiply(minPct).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP).max(minFloor).min(owed).setScale(2, RoundingMode.HALF_UP);
        BigDecimal minRemaining = min.subtract(paid).max(BigDecimal.ZERO);
        long days = java.time.temporal.ChronoUnit.DAYS.between(today, dueDate);
        DueState state;
        if (owed.signum() == 0) state = DueState.NO_DUES;
        else if (remaining.signum() == 0) state = DueState.PAID;
        else if (days < 0) state = DueState.OVERDUE;
        else if (paid.signum() > 0) state = DueState.PARTIALLY_PAID;
        else state = DueState.DUE;
        return new Due(owed, paid, remaining, min, minRemaining, state, days);
    }

    public static BigDecimal utilisationPct(BigDecimal outstanding, BigDecimal limit) {
        if (limit == null || limit.signum() <= 0) return null;
        return outstanding.max(BigDecimal.ZERO).multiply(BigDecimal.valueOf(100)).divide(limit, 1, RoundingMode.HALF_UP);
    }
}
