package com.expensetracker.networth;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class NetWorthLogicTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 17);

    private static Valuation.Inputs market(String units, String price, String invested) {
        return new Valuation.Inputs(ValuationMethod.MARKET_PRICE, units == null ? null : new BigDecimal(units), price == null ? null : new BigDecimal(price),
                null, invested == null ? null : new BigDecimal(invested), null, null, null, null);
    }

    @Test
    void marketValueAndGain() {
        var r = Valuation.value(market("152.347", "98.4521", "12000.00"), TODAY);
        assertEquals(new BigDecimal("14998.88"), r.value());
        assertEquals(new BigDecimal("2998.88"), r.gain());
        assertEquals(new BigDecimal("24.99"), r.gainPct());
        assertFalse(r.estimated());
    }

    @Test
    void missingPriceFallsBackToInvestedAndIsFlagged() {
        var r = Valuation.value(market("10", null, "5000.00"), TODAY);
        assertEquals(new BigDecimal("5000.00"), r.value());
        assertTrue(r.estimated());
    }

    @Test
    void fixedDepositCompoundsQuarterlyUntilMaturity() {
        var in = new Valuation.Inputs(ValuationMethod.FIXED_RATE, null, null, null, new BigDecimal("100000.00"), new BigDecimal("7.0"), 4,
                LocalDate.of(2025, 11, 17), LocalDate.of(2027, 11, 17));
        var r = Valuation.value(in, TODAY);
        // one year at 7% compounded quarterly = 107,185.90
        assertEquals(0, r.value().compareTo(new BigDecimal("107185.90")), "was " + r.value());
        assertTrue(r.estimated());
        var matured = Valuation.value(new Valuation.Inputs(ValuationMethod.FIXED_RATE, null, null, null, new BigDecimal("100000.00"),
                new BigDecimal("7.0"), 4, LocalDate.of(2024, 11, 17), LocalDate.of(2025, 11, 17)), TODAY);
        assertEquals(0, matured.value().compareTo(new BigDecimal("107185.90")), "stops growing at maturity, was " + matured.value());
    }

    @Test
    void manualValue() {
        var r = Valuation.value(new Valuation.Inputs(ValuationMethod.MANUAL, null, null, new BigDecimal("4500000"), new BigDecimal("3800000"),
                null, null, null, null), TODAY);
        assertEquals(new BigDecimal("4500000.00"), r.value());
        assertEquals(new BigDecimal("18.42"), r.gainPct());
    }

    @Test
    void amfiNavFile() {
        String txt = """
                Scheme Code;ISIN Div Payout/ ISIN Growth;ISIN Div Reinvestment;Scheme Name;Net Asset Value;Date

                Open Ended Schemes(Equity Scheme - Flexi Cap Fund)

                Parag Parikh Mutual Fund

                122639;INF879O01027;-;Parag Parikh Flexi Cap Fund - Direct Plan - Growth;92.4521;14-Nov-2026
                122640;-;INF879O01035;Parag Parikh Flexi Cap Fund - Direct Plan - IDCW;N.A.;14-Nov-2026

                Open Ended Schemes(Debt Scheme - Liquid Fund)

                HDFC Mutual Fund

                119091;INF179KB1HK0;-;HDFC Liquid Fund - Direct Plan - Growth Option;5123.4567;15-Nov-2026
                """;
        var rows = AmfiNavParser.parse(txt);
        assertEquals(3, rows.size());
        var ppfas = rows.get(0);
        assertEquals("122639", ppfas.schemeCode());
        assertEquals("INF879O01027", ppfas.isinGrowth());
        assertNull(ppfas.isinReinvest());
        assertEquals(new BigDecimal("92.4521"), ppfas.nav());
        assertEquals(LocalDate.of(2026, 11, 14), ppfas.date());
        assertEquals("Equity Scheme - Flexi Cap Fund", ppfas.category());
        assertEquals("Parag Parikh Mutual Fund", ppfas.amc());
        assertNull(rows.get(1).nav(), "N.A. NAV is skipped, not zero");
        assertEquals(Allocation.DEBT, AssetClass.allocationForFundCategory(rows.get(2).category()));
        assertEquals(Allocation.EQUITY, AssetClass.allocationForFundCategory(ppfas.category()));
    }

    @Test
    void amfiNavFileWithPlanAndOptionColumns() {
        // AMFI's current layout (seen November 2026): Plan and Option sit between the name and the NAV
        String txt = """
                Scheme Code;ISIN Div Payout/ ISIN Growth;ISIN Div Reinvestment;Scheme Name;Plan;Option;Net Asset Value;Date

                Open Ended Schemes(Children's Fund - Childrens' Fund)

                Axis Mutual Fund

                135762;INF846K01WO1;-;Axis Children's Fund;Direct Plan;Growth Option;31.1190;16-Nov-2026
                135763;INF846K01WS2;INF846K01WQ6;Axis Children's Fund;Direct Plan;IDCW Option;28.7204;16-Nov-2026
                """;
        var rows = AmfiNavParser.parse(txt);
        assertEquals(2, rows.size());
        assertEquals(new BigDecimal("31.1190"), rows.get(0).nav());
        assertEquals(LocalDate.of(2026, 11, 16), rows.get(0).date());
        assertEquals("Axis Children's Fund - Direct Plan - Growth Option", rows.get(0).name());
        assertEquals("INF846K01WQ6", rows.get(1).isinReinvest());
    }

    @Test
    void zerodhaKiteHoldings() {
        String csv = """
                Instrument,Qty.,Avg. cost,LTP,Cur. val,P&L,Net chg.,Day chg.
                INFY,10,1450.50,1612.30,16123.00,1618.00,11.15,0.52
                HDFCBANK,25,1520.00,1702.10,42552.50,4552.50,11.98,-0.21
                """;
        var res = HoldingsCsvParser.parse(csv);
        assertFalse(res.fundFile());
        var infy = res.rows().get(0);
        assertEquals("INFY", infy.symbol());
        assertEquals(0, infy.quantity().compareTo(BigDecimal.TEN));
        assertEquals(new BigDecimal("14505.00"), infy.invested());
        assertEquals(new BigDecimal("1612.30"), infy.price());
    }

    @Test
    void growwStocksAndMutualFunds() {
        String stocks = """
                Stock Name,ISIN,Quantity,Average buy price,Buy value,Closing price,Closing value,Unrealised P&L
                Tata Motors Ltd,INE155A01022,20,720.10,14402.00,788.40,15768.00,1366.00
                """;
        var s = HoldingsCsvParser.parse(stocks).rows().get(0);
        assertEquals("INE155A01022", s.isin());
        assertEquals(new BigDecimal("14402.00"), s.invested());
        assertFalse(s.looksLikeFund());

        String funds = """
                Scheme Name,AMC,Category,Sub-category,Folio No.,Source,Units,Invested Value,Current Value,Returns,XIRR
                Parag Parikh Flexi Cap Fund Direct Growth,PPFAS,Equity,Flexi Cap,12345678/90,Groww,152.347,"12,000","14,998.80","2,998.80",18.2%
                Total,,,,,,,"12,000","14,998.80",,
                """;
        var res = HoldingsCsvParser.parse(funds);
        assertTrue(res.fundFile());
        assertEquals(1, res.rows().size(), "total row skipped");
        var f = res.rows().get(0);
        assertEquals("12345678/90", f.folio());
        assertEquals(new BigDecimal("98.451561"), f.price());
        assertTrue(f.looksLikeFund());
    }
}
