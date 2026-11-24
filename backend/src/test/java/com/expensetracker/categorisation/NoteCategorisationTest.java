package com.expensetracker.categorisation;

import com.expensetracker.integration.parsing.CsvStatementParser;
import com.expensetracker.integration.parsing.NarrationParser;
import com.expensetracker.transaction.Direction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NoteCategorisationTest {

    @Test
    void remarkExtractedFromSbiStyleNarration() {
        var n = NarrationParser.parse("UPI/DR/431212345678/RAMESH K/SBIN/rameshk@oksbi/groceries");
        assertEquals("groceries", n.remark());
        assertEquals("RAMESH K", n.counterparty());
    }

    @Test
    void remarkExtractedFromHdfcStyleNarration() {
        assertEquals("rent nov", NarrationParser.parse("UPI-RAMESH IYER-RAMESHIYER@OKSBI-SBIN0001234-431200000101-rent nov").remark());
    }

    @Test
    void remarkExtractedFromIciciStyleWhereNoteComesBeforeVpa() {
        assertEquals("petrol", NarrationParser.parse("UPI/431212345678/petrol/hpcl.pump@icici/ICICI Bank/ICI4b7c9e1a2").remark());
    }

    @Test
    void appDefaultTextsAreNotNotes() {
        assertNull(NarrationParser.parse("UPI/DR/431212345678/SWIGGY/YESB/swiggy@yesbank/Payment from Ph").remark());
        assertNull(NarrationParser.parse("UPI/DR/431212345678/RAHUL/HDFC/rahul@okhdfcbank/Sent using Paytm U").remark());
        assertNull(NarrationParser.parse("UPI/DR/431212345678/RAHUL/HDFC/rahul@okhdfcbank/UPI").remark());
        assertNull(NarrationParser.parse("NEFT CR-CITI0000002-ACME-SALARY").remark(), "remarks are only read from UPI narrations");
    }

    @Test
    void templeNotesGoToTempleAndPooja() {
        assertEquals("Temple & Pooja", NoteDictionary.lookup("temple", Direction.DEBIT).orElseThrow().categoryPath());
        assertEquals("Temple & Pooja", NoteDictionary.lookup("mandir prasad", Direction.DEBIT).orElseThrow().categoryPath());
        assertEquals("Temple & Pooja", NoteDictionary.lookup("pooja samagri", Direction.DEBIT).orElseThrow().categoryPath());
        assertEquals("Temple & Pooja", NoteDictionary.lookup("hundi", Direction.DEBIT).orElseThrow().categoryPath());
        assertEquals("Donations", NoteDictionary.lookup("church donation", Direction.DEBIT).orElseThrow().categoryPath());
        // "Pooja" alone is usually a person's name, not a pooja
        assertTrue(NoteDictionary.lookup("pooja", Direction.DEBIT).map(m -> !m.categoryPath().equals("Temple & Pooja")).orElse(true));
    }

    @Test
    void notesMapToCategoriesIncludingHinglish() {
        assertEquals("Groceries", NoteDictionary.lookup("sabzi", Direction.DEBIT).orElseThrow().categoryPath());
        assertEquals("Groceries", NoteDictionary.lookup("Doodh + eggs", Direction.DEBIT).orElseThrow().categoryPath());
        assertEquals("Rent", NoteDictionary.lookup("Kiraya Nov", Direction.DEBIT).orElseThrow().categoryPath());
        assertEquals("Fuel", NoteDictionary.lookup("petrol", Direction.DEBIT).orElseThrow().categoryPath());
        assertEquals("Bills & Utilities > Electricity", NoteDictionary.lookup("bijli bill", Direction.DEBIT).orElseThrow().categoryPath());
        assertEquals("Household Help", NoteDictionary.lookup("maid salary oct", Direction.DEBIT).orElseThrow().categoryPath());
        assertEquals("Food & Dining > Cafes", NoteDictionary.lookup("chai", Direction.DEBIT).orElseThrow().categoryPath());
        assertEquals("Medical > Pharmacy", NoteDictionary.lookup("dawai for papa", Direction.DEBIT).orElseThrow().categoryPath());
        assertEquals("Bank Charges", NoteDictionary.lookup("late fee", Direction.DEBIT).orElseThrow().categoryPath());
    }

    @Test
    void directionMatters() {
        assertEquals("Rental Income", NoteDictionary.lookup("rent", Direction.CREDIT).orElseThrow().categoryPath());
        assertEquals("Salary", NoteDictionary.lookup("salary nov", Direction.CREDIT).orElseThrow().categoryPath());
        assertEquals("Household Help", NoteDictionary.lookup("driver salary", Direction.DEBIT).orElseThrow().categoryPath());
    }

    @Test
    void lendingAndCardBillsAreHintsNotSpending() {
        assertEquals(NoteDictionary.Hint.LENT_OR_REPAID, NoteDictionary.lookup("lent to rahul", Direction.DEBIT).orElseThrow().hint());
        assertEquals(NoteDictionary.Hint.LENT_OR_REPAID, NoteDictionary.lookup("udhaar wapas", Direction.CREDIT).orElseThrow().hint());
        assertEquals(NoteDictionary.Hint.CREDIT_CARD_PAYMENT, NoteDictionary.lookup("hdfc cc bill", Direction.DEBIT).orElseThrow().hint());
    }

    @Test
    void wholeWordMatchingOnly() {
        assertTrue(NoteDictionary.lookup("parental", Direction.DEBIT).isEmpty(), "'rent' inside 'parental' must not match");
        assertTrue(NoteDictionary.matchesPattern("Groceries for the week!", "groceries"));
        assertFalse(NoteDictionary.matchesPattern("parental", "rent"));
        assertEquals("rent nov 2026", NoteDictionary.normalise("  Rent, Nov-2026 "));
    }

    @Test
    void csvWithNoteAndCategoryColumns() {
        String csv = """
                Date,Narration,Withdrawal Amt.,Deposit Amt.,Note,Category
                03/11/2026,UPI-RAMESH-RAMESH@OKSBI-SBIN0001234-431200000101-PAY,650.00,,sabzi,
                04/11/2026,UPI-AMIT-AMIT@YBL-YESB0000001-431200000102-PAY,1200.00,,team lunch,Food & Dining > Restaurants
                """;
        var rows = CsvStatementParser.parse(csv).rows();
        assertEquals("sabzi", rows.get(0).note());
        assertNull(rows.get(0).category());
        assertEquals("Food & Dining > Restaurants", rows.get(1).category());
    }

    @Test
    void personalSheetWithOnlyNoteAndSignedType() {
        String csv = """
                Date,Amount,Type,Note
                2026-11-03,650,Expense,sabzi
                2026-11-04,92000,Income,salary
                """;
        var rows = CsvStatementParser.parse(csv).rows();
        assertEquals(2, rows.size());
        assertEquals(Direction.DEBIT, rows.get(0).direction());
        assertEquals("sabzi", rows.get(0).description());
        assertEquals("sabzi", rows.get(0).note());
        assertEquals(Direction.CREDIT, rows.get(1).direction());
    }

    @Test
    void depositTypeIsCredit() {
        var rows = CsvStatementParser.parse("Date,Description,Amount,Type\n2026-11-03,Cash deposit,5000,Deposit\n").rows();
        assertEquals(Direction.CREDIT, rows.get(0).direction());
    }
}
