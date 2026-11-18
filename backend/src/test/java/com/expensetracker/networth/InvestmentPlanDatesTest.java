package com.expensetracker.networth;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class InvestmentPlanDatesTest {

    private static InvestmentPlan plan(int day, LocalDate start, LocalDate end) {
        InvestmentPlan p = new InvestmentPlan(UUID.randomUUID(), UUID.randomUUID(), InvestmentPlan.Kind.SIP);
        p.setDayOfMonth(day);
        p.setStartDate(start);
        p.setEndDate(end);
        return p;
    }

    @Test
    void nextDueIsTheNextOccurrenceOfTheDayWithinStartAndEnd() {
        var p = plan(5, LocalDate.of(2026, 11, 18), null);
        assertEquals(LocalDate.of(2026, 12, 5), InvestmentPlanService.nextDue(p, LocalDate.of(2026, 11, 17)));
        assertEquals(LocalDate.of(2027, 1, 5), InvestmentPlanService.nextDue(p, LocalDate.of(2026, 12, 5)));
        var later = plan(20, LocalDate.of(2026, 11, 18), null);
        assertEquals(LocalDate.of(2026, 11, 20), InvestmentPlanService.nextDue(later, LocalDate.of(2026, 11, 17)));
        var ending = plan(5, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 12, 31));
        assertEquals(LocalDate.of(2026, 12, 5), InvestmentPlanService.nextDue(ending, LocalDate.of(2026, 11, 5)));
        assertNull(InvestmentPlanService.nextDue(ending, LocalDate.of(2026, 12, 5)), "no installments after the end date");
    }
}
