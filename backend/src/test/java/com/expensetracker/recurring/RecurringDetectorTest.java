package com.expensetracker.recurring;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RecurringDetectorTest {

    private static RecurringDetector.Payment p(String key, String name, String date, String amt) {
        return new RecurringDetector.Payment(key, name, LocalDate.parse(date), new BigDecimal(amt), false, null, false);
    }

    @Test
    void monthlySubscriptionWithPriceChange() {
        var pats = RecurringDetector.detect(List.of(
                p("netflix", "Netflix", "2026-07-05", "499"), p("netflix", "Netflix", "2026-08-05", "499"),
                p("netflix", "Netflix", "2026-09-04", "499"), p("netflix", "Netflix", "2026-10-05", "649")));
        assertEquals(1, pats.size());
        var n = pats.get(0);
        assertEquals(RecurringDetector.Frequency.MONTHLY, n.frequency());
        assertEquals(RecurringDetector.Kind.SUBSCRIPTION, n.kind());
        assertEquals(new BigDecimal("649.00"), n.amount());
        assertTrue(n.priceChanged());
        assertEquals(LocalDate.of(2026, 11, 5), n.nextDue());
        assertEquals(new BigDecimal("7788.00"), n.annualCost());
    }

    @Test
    void varyingBillIsNotAPriceChange() {
        var pats = RecurringDetector.detect(List.of(
                p("bescom", "BESCOM electricity", "2026-07-10", "1820"), p("bescom", "BESCOM electricity", "2026-08-11", "2410"),
                p("bescom", "BESCOM electricity", "2026-09-10", "1990"), p("bescom", "BESCOM electricity", "2026-10-09", "2205")));
        var b = pats.get(0);
        assertTrue(b.amountVaries());
        assertFalse(b.priceChanged());
        assertEquals(RecurringDetector.Kind.BILL, b.kind());
        assertEquals(new BigDecimal("2201.67"), b.amount()); // average of last three
    }

    @Test
    void irregularOrTooFewRepeatsAreIgnored() {
        assertTrue(RecurringDetector.detect(List.of(p("swiggy", "Swiggy", "2026-10-01", "300"), p("swiggy", "Swiggy", "2026-10-03", "450"),
                p("swiggy", "Swiggy", "2026-10-20", "280"), p("swiggy", "Swiggy", "2026-11-02", "510"))).isEmpty());
        assertTrue(RecurringDetector.detect(List.of(p("gym", "Cult gym", "2026-09-01", "1500"), p("gym", "Cult gym", "2026-10-01", "1500"))).isEmpty());
    }

    @Test
    void yearlyNeedsTwoAndKindsAreGuessed() {
        var y = RecurringDetector.detect(List.of(p("lic", "LIC premium", "2025-03-20", "18000"), p("lic", "LIC premium", "2026-03-21", "18000")));
        assertEquals(RecurringDetector.Frequency.YEARLY, y.get(0).frequency());
        assertEquals(RecurringDetector.Kind.INSURANCE, y.get(0).kind());
        assertEquals(RecurringDetector.Kind.INCOME, RecurringDetector.guessKind("Acme salary", null, true, false));
        assertEquals(RecurringDetector.Kind.SIP, RecurringDetector.guessKind("anything", null, false, true));
        assertEquals(RecurringDetector.Kind.RENT, RecurringDetector.guessKind("house rent", null, false, false));
        assertEquals(RecurringDetector.Kind.OTHER, RecurringDetector.guessKind("parent", null, false, false)); // "rent" inside a word
        assertEquals(RecurringDetector.Kind.BILL, RecurringDetector.guessKind("maid salary", "Household Help", false, false));
    }

    @Test
    void weeklyWithOneMissedWeekStillDetected() {
        List<RecurringDetector.Payment> ps = new ArrayList<>();
        LocalDate d = LocalDate.of(2026, 9, 6);
        for (int i = 0; i < 8; i++) {
            if (i != 4) ps.add(new RecurringDetector.Payment("milk", "milkman", d, new BigDecimal("420"), false, null, false));
            d = d.plusWeeks(1);
        }
        var w = RecurringDetector.detect(ps);
        assertEquals(1, w.size());
        assertEquals(RecurringDetector.Frequency.WEEKLY, w.get(0).frequency());
    }
}
