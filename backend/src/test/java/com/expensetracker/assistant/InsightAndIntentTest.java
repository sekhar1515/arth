package com.expensetracker.assistant;

import com.expensetracker.analytics.InsightEngine;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class InsightAndIntentTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 17);
    private static BigDecimal d(String s) { return new BigDecimal(s); }

    @Test
    void parsesSpendQuestions() {
        var i = IntentParser.parse("How much did I spend on food last month?", TODAY);
        assertEquals(IntentParser.Type.SPEND_TOTAL, i.type());
        assertEquals("food", i.subject());
        assertEquals(LocalDate.of(2026, 10, 1), i.range().from());
        assertEquals(LocalDate.of(2026, 11, 1), i.range().toExclusive());
        var s = IntentParser.parse("what did i spend at swiggy in september", TODAY);
        assertEquals("swiggy", s.subject());
        assertEquals(LocalDate.of(2026, 9, 1), s.range().from());
        var t = IntentParser.parse("total spending this month", TODAY);
        assertNull(t.subject());
        assertEquals("this month", t.range().label());
        var dec = IntentParser.parse("how much did I spend in december", TODAY);
        assertEquals(LocalDate.of(2025, 12, 1), dec.range().from()); // future month means last year
        var n = IntentParser.parse("spending in the last 7 days", TODAY);
        assertEquals(LocalDate.of(2026, 11, 11), n.range().from());
    }

    @Test
    void parsesOtherIntents() {
        assertEquals(IntentParser.Type.NET_WORTH, IntentParser.parse("What's my net worth?", TODAY).type());
        assertEquals(IntentParser.Type.SUBSCRIPTIONS, IntentParser.parse("list my subscriptions", TODAY).type());
        assertEquals(IntentParser.Type.CARD_DUES, IntentParser.parse("when is my credit card bill due", TODAY).type());
        assertEquals(IntentParser.Type.TOP_CATEGORIES, IntentParser.parse("where did my money go this year", TODAY).type());
        assertEquals(IntentParser.Type.INCOME_TOTAL, IntentParser.parse("how much salary did I get last month", TODAY).type());
        assertEquals(IntentParser.Type.HELP, IntentParser.parse("tell me a joke", TODAY).type());
        assertEquals(IntentParser.Type.BUDGETS, IntentParser.parse("am I over budget?", TODAY).type());
    }

    @Test
    void parsesAffordAmounts() {
        var a = IntentParser.parse("can I afford a ₹45,000 phone?", TODAY);
        assertEquals(IntentParser.Type.CAN_AFFORD, a.type());
        assertEquals(0, d("45000").compareTo(a.amount()));
        assertEquals(0, d("150000").compareTo(IntentParser.parse("can i afford 1.5 lakh trip in 2027", TODAY).amount()));
        assertEquals(0, d("60000").compareTo(IntentParser.parse("can I afford 60k", TODAY).amount()));
    }

    @Test
    void insightsShowTheirCalculation() {
        var months = List.of(
                new InsightEngine.MonthTotals("Jun", d("100000"), d("50000")), new InsightEngine.MonthTotals("Jul", d("100000"), d("52000")),
                new InsightEngine.MonthTotals("Aug", d("100000"), d("51000")), new InsightEngine.MonthTotals("Sep", d("100000"), d("60000")),
                new InsightEngine.MonthTotals("Oct", d("100000"), d("62000")), new InsightEngine.MonthTotals("Nov", d("100000"), d("75000")));
        var cats = List.of(new InsightEngine.SeriesByName("Food & Dining", "c1", List.of(d("8000"), d("8000"), d("9000"), d("8000"), d("9000"), d("14000"))));
        var list = InsightEngine.analyse(months, cats, List.of(), List.of());
        var types = list.stream().map(InsightEngine.Insight::type).toList();
        assertTrue(types.contains(InsightEngine.Type.SAVINGS_RATE_DOWN), types.toString());
        assertTrue(types.contains(InsightEngine.Type.LIFESTYLE_INFLATION), types.toString());
        assertTrue(types.contains(InsightEngine.Type.CATEGORY_SPIKE), types.toString());
        var spike = list.stream().filter(i -> i.type() == InsightEngine.Type.CATEGORY_SPIKE).findFirst().orElseThrow();
        assertEquals("Spending on Food & Dining up 62%", spike.title()); // 14000 vs avg(8000, 9000, 8000)=8333.33
        assertFalse(types.contains(InsightEngine.Type.OVERSPENDING));
    }
}
