package com.expensetracker.integration.parsing;

import com.expensetracker.categorisation.NoteDictionary;
import com.expensetracker.transaction.Direction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** A personal sheet whose Description column is what you typed should be read as your note. */
class HandWrittenDescriptionTest {

    @Test
    void recognisesHandWrittenDescriptions() {
        for (String s : new String[]{"petrol for bike", "maid salary", "medicines for amma", "Gift for sister's birthday", "haircut", "Swiggy dinner"}) {
            assertTrue(CsvStatementParser.looksHandWritten(s), s);
        }
    }

    @Test
    void rejectsBankNarrations() {
        for (String s : new String[]{"UPI/DR/431212345678/SWIGGY/YESB/swiggy@yesbank/Payment",
                "UPI-BLINKIT-BLINKIT.PAYU@HDFCBANK-HDFC0000001-431277778888-ORDER",
                "NEFT CR-CITI0000002-ACME TECHNOLOGIES PVT LTD-SALARY NOV 2026-CITIN26123456789",
                "POS 4111XXXXXXXX1234 DMART AVENUE", "ATM WDL 12345678 MG ROAD", "IMPS-612345678901-RAMESH", "", "1234"}) {
            assertFalse(CsvStatementParser.looksHandWritten(s), s);
        }
    }

    @Test
    void personalSheetWithoutNoteColumnUsesDescriptionAsNote() {
        String csv = """
                Date,Description,Amount,Type
                2026-11-12,maid salary,3000,Debit
                2026-11-13,UPI/DR/431212345678/RAMESH/YESB/ramesh@ybl/Payment,500,Debit
                """;
        var result = CsvStatementParser.parse(csv);
        assertEquals("maid salary", result.rows().get(0).note());
        assertNull(result.rows().get(1).note(), "bank narrations are not notes");
    }

    @Test
    void explicitNoteColumnStillWins() {
        String csv = """
                Date,Description,Amount,Type,Note
                2026-11-12,Paid to Ramesh,3000,Debit,rent
                """;
        assertEquals("rent", CsvStatementParser.parse(csv).rows().get(0).note());
    }

    @Test
    void dictionaryCoversCommonPersonalDescriptions() {
        assertEquals("Household Help", NoteDictionary.lookup("maid salary", Direction.DEBIT).orElseThrow().categoryPath());
        assertEquals("Personal Care", NoteDictionary.lookup("haircut", Direction.DEBIT).orElseThrow().categoryPath());
        assertEquals("Medical > Pharmacy", NoteDictionary.lookup("medicines for amma", Direction.DEBIT).orElseThrow().categoryPath());
        assertEquals("Freelancing", NoteDictionary.lookup("freelance logo project", Direction.CREDIT).orElseThrow().categoryPath());
        assertEquals("Family Expenses", NoteDictionary.lookup("sent to amma", Direction.DEBIT).orElseThrow().categoryPath());
    }

    @Test
    void coreKeyDropsMonthsAmountsAndFillerSoLearningCarriesOver() {
        assertEquals("chitti", NoteDictionary.coreKey("Chitti payment Nov 2026"));
        assertEquals("chitti", NoteDictionary.coreKey("chitti dec"));
        assertEquals("house rent", NoteDictionary.coreKey("House rent for March"));
        assertEquals("sabzi", NoteDictionary.coreKey("sabzi 2nd week"));
        assertEquals("nov", NoteDictionary.coreKey("Nov"), "a note made only of filler falls back to itself");
    }

    @Test
    void transferHintsStillReadTheWholeNote() {
        assertEquals(NoteDictionary.Hint.LENT_OR_REPAID, NoteDictionary.lookup("paid back to ravi", Direction.DEBIT).orElseThrow().hint());
        assertEquals(NoteDictionary.Hint.SELF_TRANSFER, NoteDictionary.lookup("to self", Direction.DEBIT).orElseThrow().hint());
    }
}
