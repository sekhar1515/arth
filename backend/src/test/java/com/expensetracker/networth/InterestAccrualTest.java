package com.expensetracker.networth;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class InterestAccrualTest {

    private static BigDecimal d(String s) {
        return new BigDecimal(s);
    }

    @Test
    void epfEarnsMonthlyOnTheBalanceAndIsCreditedOn31March() {
        // ₹4,50,000 from 31 Oct at 8.25%: Nov, Dec, Jan, Feb each earn 450000 × 8.25 / 1200 = 3093.75
        var start = new InterestAccrual.State(d("450000"), BigDecimal.ZERO, LocalDate.of(2026, 10, 31));
        var feb = InterestAccrual.accrue(start, d("8.25"), LocalDate.of(2027, 3, 15));
        assertEquals(d("450000.00"), feb.balance(), "not credited before March ends");
        assertEquals(d("12375.00"), feb.accrued());
        assertEquals(LocalDate.of(2027, 2, 28), feb.accruedTo());
        // after 31 March: five months' interest moves into the balance
        var april = InterestAccrual.accrue(feb, d("8.25"), LocalDate.of(2027, 4, 2));
        assertEquals(d("465468.75"), april.balance());
        assertEquals(d("0.00"), april.accrued());
        // the new year earns on the bigger balance
        var may = InterestAccrual.accrue(april, d("8.25"), LocalDate.of(2027, 5, 1));
        assertEquals(d("3200.10"), may.accrued(), "465468.75 × 8.25 / 1200");
    }

    @Test
    void partFirstMonthAndNoRate() {
        // entered on 16 Nov (30-day month): 14 of 30 days → 450000 × 0.006875 × 14/30 = 1443.75
        var s = InterestAccrual.accrue(new InterestAccrual.State(d("450000"), BigDecimal.ZERO, LocalDate.of(2026, 11, 16)), d("8.25"), LocalDate.of(2026, 12, 1));
        assertEquals(d("1443.75"), s.accrued());
        var none = new InterestAccrual.State(d("1000"), BigDecimal.ZERO, LocalDate.of(2026, 1, 1));
        assertSame(none, InterestAccrual.accrue(none, null, LocalDate.of(2027, 1, 1)));
        // nothing until the month has ended
        assertEquals(BigDecimal.ZERO.setScale(2), InterestAccrual.accrue(new InterestAccrual.State(d("1000"), BigDecimal.ZERO, LocalDate.of(2026, 11, 30)), d("8"), LocalDate.of(2026, 12, 30)).accrued());
    }
}
