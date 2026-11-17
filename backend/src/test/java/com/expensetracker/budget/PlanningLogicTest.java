package com.expensetracker.budget;

import com.expensetracker.creditcard.CardCycle;
import com.expensetracker.goal.GoalMath;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PlanningLogicTest {

    private static BigDecimal d(String s) { return new BigDecimal(s); }

    @Test
    void periodsFollowIndianConventions() {
        var w = BudgetPeriods.containing(BudgetPeriods.Period.WEEKLY, LocalDate.of(2026, 11, 19)); // Thursday
        assertEquals(LocalDate.of(2026, 11, 16), w.start());
        assertEquals(LocalDate.of(2026, 11, 23), w.endExclusive());
        var m = BudgetPeriods.containing(BudgetPeriods.Period.MONTHLY, LocalDate.of(2026, 2, 14));
        assertEquals(28L, m.days());
        var fy = BudgetPeriods.containing(BudgetPeriods.Period.YEARLY, LocalDate.of(2027, 2, 1));
        assertEquals(LocalDate.of(2026, 4, 1), fy.start());
        assertEquals("FY 2026-27", BudgetPeriods.label(BudgetPeriods.Period.YEARLY, fy));
        assertEquals(LocalDate.of(2026, 1, 1), BudgetPeriods.previous(BudgetPeriods.Period.MONTHLY, m).start());
        assertEquals(14L, m.daysElapsed(LocalDate.of(2026, 2, 14)));
    }

    @Test
    void budgetStatusForecastAndAllowance() {
        // ₹10,000 month, ₹6,000 spent by day 15 of 30
        var s = BudgetMath.status(d("10000"), BigDecimal.ZERO, d("6000"), 15, 30);
        assertEquals(d("4000.00"), s.remaining());
        assertEquals(d("60.0"), s.pctUsed());
        assertEquals(d("12000.00"), s.forecast());
        assertEquals(d("2000.00"), s.projectedOverspend());
        assertEquals("AT_RISK", s.state());
        assertEquals(d("250.00"), s.dailyAllowance()); // 4000 over 16 days incl. today
        var over = BudgetMath.status(d("5000"), d("-1000"), d("4500"), 20, 30);
        assertEquals(d("4000.00"), over.available());
        assertEquals("OVER", over.state());
    }

    @Test
    void rolloverThresholdsAndRecommendation() {
        assertEquals(d("1500.00"), BudgetMath.carryOver(true, d("10000"), d("8500")));
        assertEquals(d("0.00"), BudgetMath.carryOver(false, d("10000"), d("8500")));
        assertEquals(List.of(50, 75), BudgetMath.crossed(List.of(100, 50, 90, 75), d("80.2")));
        assertEquals(List.of(50, 80, 100), BudgetMath.parseThresholds("100, 50,80,50"));
        assertEquals(d("9500.00"), BudgetMath.recommend(List.of(d("8200"), d("9100"), d("10000")), BudgetPeriods.Period.MONTHLY));
        assertNull(BudgetMath.recommend(List.of(), BudgetPeriods.Period.MONTHLY));
    }

    @Test
    void cardCycleAroundStatementDay() {
        var c = CardCycle.current(15, 20, LocalDate.of(2026, 11, 17));
        assertEquals(LocalDate.of(2026, 11, 16), c.start());
        assertEquals(LocalDate.of(2026, 12, 15), c.statementDate());
        var last = CardCycle.lastStatement(15, 20, LocalDate.of(2026, 11, 17));
        assertEquals(LocalDate.of(2026, 11, 15), last.statementDate());
        assertEquals(LocalDate.of(2026, 12, 5), last.dueDate());
        assertEquals(LocalDate.of(2026, 10, 16), last.start());
        // statement day 28 in February
        assertEquals(LocalDate.of(2027, 2, 28), CardCycle.current(28, 20, LocalDate.of(2027, 2, 10)).statementDate());
        // on the statement day itself, that statement is the latest one
        assertEquals(LocalDate.of(2026, 11, 15), CardCycle.lastStatement(15, 20, LocalDate.of(2026, 11, 15)).statementDate());
    }

    @Test
    void cardDueStates() {
        var due = CardCycle.due(d("24000"), d("5000"), d("5"), d("200"), LocalDate.of(2026, 12, 5), LocalDate.of(2026, 11, 30));
        assertEquals(d("19000.00"), due.remaining());
        assertEquals(d("1200.00"), due.minimumDue());
        assertEquals(d("0.00"), due.minimumRemaining().setScale(2));
        assertEquals(CardCycle.DueState.PARTIALLY_PAID, due.state());
        assertEquals(5L, due.daysToDue());
        assertEquals(CardCycle.DueState.OVERDUE, CardCycle.due(d("3000"), BigDecimal.ZERO, d("5"), d("200"), LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 5)).state());
        assertEquals(d("200.00"), CardCycle.due(d("1000"), BigDecimal.ZERO, d("5"), d("200"), LocalDate.of(2026, 12, 1), LocalDate.of(2026, 11, 5)).minimumDue());
        assertEquals(CardCycle.DueState.PAID, CardCycle.due(d("1000"), d("1000"), d("5"), d("200"), LocalDate.of(2026, 12, 1), LocalDate.of(2026, 11, 5)).state());
        assertEquals(d("37.5"), CardCycle.utilisationPct(d("75000"), d("200000")));
    }

    @Test
    void goalRequiredMonthlyWithAndWithoutReturns() {
        // ₹1,20,000 in 12 months from zero, no return → ₹10,000/month
        assertEquals(d("10000.00"), GoalMath.requiredMonthly(d("120000"), BigDecimal.ZERO, BigDecimal.ZERO, 12));
        // with 12% a year (1%/month): 120000 / ((1.01^12 - 1)/0.01) = 9461.855 → 9461.86 (rounded up)
        assertEquals(d("9461.86"), GoalMath.requiredMonthly(d("120000"), BigDecimal.ZERO, d("0.01"), 12));
        var p = GoalMath.project(d("100000"), d("40000"), LocalDate.of(2027, 11, 1), BigDecimal.ZERO, d("5000"), LocalDate.of(2026, 11, 17));
        assertEquals(d("40.0"), p.progressPct());
        assertEquals(Integer.valueOf(12), p.monthsLeft());
        assertEquals(d("5000.00"), p.requiredMonthly());
        assertEquals(LocalDate.of(2027, 11, 17), p.projectedDate());
        assertEquals("BEHIND", p.state()); // 17 Nov is after 1 Nov
        var none = GoalMath.project(d("100000"), d("0"), null, BigDecimal.ZERO, BigDecimal.ZERO, LocalDate.of(2026, 11, 17));
        assertEquals("NO_CONTRIBUTIONS", none.state());
        assertEquals("ACHIEVED", GoalMath.project(d("1000"), d("1200"), null, BigDecimal.ZERO, null, LocalDate.of(2026, 11, 17)).state());
    }
}
