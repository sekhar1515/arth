package com.expensetracker.networth;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

public class BhavcopyParserTest {

    public static final String HEADER = "TradDt,BizDt,Sgmt,Src,FinInstrmTp,FinInstrmId,ISIN,TckrSymb,SctySrs,XpryDt,FininstrmActlXpryDt,StrkPric,OptnTp,"
            + "FinInstrmNm,OpnPric,HghPric,LwPric,ClsPric,LastPric,PrvsClsgPric,UndrlygPric,SttlmPric,OpnIntrst,ChngInOpnIntrst,TtlTradgVol,"
            + "TtlTrfVal,TtlNbOfTxsExctd,SsnId,NewBrdLotQty,Rmks,Rsvd1,Rsvd2,Rsvd3,Rsvd4\n";

    @Test
    void parsesNseUdiffRows() {
        String csv = HEADER
                + "2026-11-16,2026-11-16,CM,NSE,STK,1594,INE009A01021,INFY,EQ,,,,,INFOSYS LIMITED,1150.00,1159.90,1138.20,1145.10,1145.10,1141.50,,1145.08,,,4852092,5556783677.80,74241,F1,1,,,,,\n"
                + "2026-11-16,2026-11-16,CM,NSE,STK,10576,INF204KB14I2,NIFTYBEES,EQ,,,,,NIP IND ETF NIFTY BEES,288.00,289.00,285.90,287.61,288.00,286.64,,287.61,,,8430028,2426018851.86,70040,F1,1,,,,,\n"
                + "2026-11-16,2026-11-16,CM,NSE,STK,19078,IN0020200104,SGBJUN28,GB,,,,,2.5%GOLDBONDS2028SR-III,13744.00,13744.00,13530.02,13530.02,13530.02,13753.66,,13515.51,,,172,2339034.85,47,F1,1,,,,,\n"
                + "2026-11-16,2026-11-16,FO,NSE,IDO,1,,NIFTY,,2026-11-27,,,,NIFTY FUT,1,1,1,1,1,1,,1,,,1,1,1,F1,1,,,,,\n";
        var quotes = BhavcopyParser.parse(csv);
        assertEquals(3, quotes.size(), "only STK rows with an ISIN");
        var infy = quotes.get(0);
        assertEquals("INE009A01021", infy.isin());
        assertEquals("INFY", infy.symbol());
        assertEquals(new BigDecimal("1145.10"), infy.close());
        assertEquals(new BigDecimal("1141.50"), infy.prevClose());
        assertEquals(LocalDate.of(2026, 11, 16), infy.date());
        assertEquals("NSE", infy.exchange());
        assertEquals("SGBJUN28", quotes.get(2).symbol());
    }

    @Test
    void readsBseSourceAndRejectsOtherFiles() {
        String csv = HEADER + "2026-11-16,2026-11-16,CM,BSE,STK,500002,INE117A01022,ABB,A,,,,,ABB INDIA LIMITED,7116.90,7128.70,6806.00,6885.00,6885.00,7045.00,,6894.59,,,43575,300512032.00,5489,F1,1,,,,,\n";
        assertEquals("BSE", BhavcopyParser.parse(csv).get(0).exchange());
        assertThrows(IllegalArgumentException.class, () -> BhavcopyParser.parse("Date,Amount\n2026-01-01,5\n"));
    }

    @Test
    void cleansCommonSymbolSpellings() {
        assertEquals("INFY", StockPriceService.cleanSymbol("infy.ns"));
        assertEquals("INFY", StockPriceService.cleanSymbol("NSE:INFY"));
        assertEquals("RELIANCE", StockPriceService.cleanSymbol("RELIANCE-EQ"));
        assertEquals("TCS", StockPriceService.cleanSymbol("TCS.BO"));
    }
}
