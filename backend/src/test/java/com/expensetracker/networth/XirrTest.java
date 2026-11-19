package com.expensetracker.networth;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class XirrTest {

    private static Xirr.Flow f(String date, String amount) {
        return new Xirr.Flow(LocalDate.parse(date), new BigDecimal(amount));
    }

    @Test
    void lumpSumOneYear() {
        // 1,00,000 in, 1,10,000 a year later = 10%
        assertEquals(new BigDecimal("10.00"), Xirr.percent(List.of(f("2025-01-01", "-100000"), f("2026-01-01", "110000"))));
    }

    @Test
    void matchesSpreadsheetXirr() {
        // the classic Excel XIRR example: -10000, 2750, 4250, 3250, 2750 → 37.34%
        BigDecimal r = Xirr.percent(List.of(f("2008-01-01", "-10000"), f("2008-03-01", "2750"), f("2008-10-30", "4250"),
                f("2009-02-15", "3250"), f("2009-04-01", "2750")));
        assertEquals(0, r.compareTo(new BigDecimal("37.34")), r.toString());
    }

    @Test
    void monthlySipWithLossAndEdgeCases() {
        // 12 SIPs of 5,000 then worth 57,000 (a loss): negative XIRR
        List<Xirr.Flow> flows = new ArrayList<>();
        for (int m = 0; m < 12; m++) flows.add(new Xirr.Flow(LocalDate.of(2025, 1, 5).plusMonths(m), new BigDecimal("-5000")));
        flows.add(f("2026-01-05", "57000"));
        BigDecimal r = Xirr.percent(flows);
        assertTrue(r.signum() < 0 && r.compareTo(new BigDecimal("-15")) > 0, r.toString());
        assertNull(Xirr.percent(List.of(f("2026-01-01", "-100"), f("2026-01-10", "120"))), "under a month: no annual figure");
        assertNull(Xirr.percent(List.of(f("2025-01-01", "-100"))));
        assertNull(Xirr.percent(List.of(f("2025-01-01", "100"), f("2026-01-01", "120"))), "needs money in and value out");
    }
}
