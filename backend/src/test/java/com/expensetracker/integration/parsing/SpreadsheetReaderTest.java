package com.expensetracker.integration.parsing;

import com.expensetracker.networth.HoldingsCsvParser;
import com.expensetracker.transaction.Direction;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class SpreadsheetReaderTest {

    @Test
    void xlsxStatementWithSummarySheetDatesAndNumbers() throws Exception {
        var sheets = SpreadsheetReader.read(SpreadsheetFixtures.statement(false));
        assertEquals(2, sheets.size());
        var tx = sheets.get(1);
        assertEquals("Transactions", tx.name());
        var parsed = CsvStatementParser.parse(tx.csv());
        assertEquals(3, parsed.rows().size());
        var first = parsed.rows().get(0);
        assertEquals(LocalDate.of(2026, 11, 12), first.date());
        assertEquals(new BigDecimal("3000.00"), first.amount());
        assertEquals("maid salary", first.note(), "own descriptions still count as notes");
        assertEquals(new BigDecimal("250.50"), parsed.rows().get(1).amount());
        assertEquals(Direction.CREDIT, parsed.rows().get(2).direction());
    }

    @Test
    void legacyXlsWorksToo() throws Exception {
        var sheets = SpreadsheetReader.read(SpreadsheetFixtures.statement(true));
        assertEquals(3, CsvStatementParser.parse(sheets.get(1).csv()).rows().size());
    }

    @Test
    void bankHtmlDisguisedAsXls() {
        var sheets = SpreadsheetReader.read(SpreadsheetFixtures.HTML_XLS.getBytes(StandardCharsets.UTF_8));
        var parsed = CsvStatementParser.parse(sheets.get(0).csv());
        assertEquals(2, parsed.rows().size());
        assertEquals(new BigDecimal("1250.00"), parsed.rows().get(0).amount());
        assertEquals(Direction.DEBIT, parsed.rows().get(0).direction());
        assertTrue(parsed.rows().get(1).description().contains("ACME TECHNOLOGIES & CO"), "entities decoded");
        assertEquals(Direction.CREDIT, parsed.rows().get(1).direction());
    }

    @Test
    void zerodhaStyleHoldingsWorkbookHasTwoReadableSheets() throws Exception {
        var sheets = SpreadsheetReader.read(SpreadsheetFixtures.holdings());
        assertEquals(2, sheets.size());
        var eq = HoldingsCsvParser.parse(sheets.get(0).csv());
        assertEquals("INE009A01021", eq.rows().get(0).isin());
        assertEquals(0, eq.rows().get(0).quantity().compareTo(BigDecimal.TEN));
        var mf = HoldingsCsvParser.parse(sheets.get(1).csv());
        assertTrue(mf.fundFile());
        assertEquals(0, mf.rows().get(0).quantity().compareTo(new BigDecimal("250.512")));
        assertEquals("12345678", mf.rows().get(0).folio());
    }

    @Test
    void friendlyErrors() {
        var e = assertThrows(IllegalArgumentException.class, () -> SpreadsheetReader.read("not a spreadsheet".getBytes()));
        assertTrue(e.getMessage().contains("Couldn't read"));
        assertThrows(IllegalArgumentException.class, () -> SpreadsheetReader.read(new byte[SpreadsheetReader.MAX_BYTES + 1]));
        assertTrue(SpreadsheetReader.isSpreadsheet("Statement.XLSX"));
        assertFalse(SpreadsheetReader.isSpreadsheet("statement.csv"));
    }
}
