package com.expensetracker.integration.parsing;

import com.expensetracker.transaction.Direction;
import com.expensetracker.transaction.PaymentMethod;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class NarrationAndCsvParserTest {

    @Test
    void upiNarrationSlashStyle() {
        var n = NarrationParser.parse("UPI/DR/431212345678/SWIGGY/YESB/swiggy@yesbank/Payment");
        assertEquals(PaymentMethod.UPI, n.method());
        assertEquals("431212345678", n.reference());
        assertEquals("SWIGGY", n.counterparty());
        assertEquals("swiggy@yesbank", n.vpa());
    }

    @Test
    void upiNarrationHdfcDashStyle() {
        var n = NarrationParser.parse("UPI-BLINKIT-BLINKIT.PAYU@HDFCBANK-HDFC0000001-431277778888-ORDER");
        assertEquals(PaymentMethod.UPI, n.method());
        assertEquals("BLINKIT", n.counterparty());
        assertEquals("431277778888", n.reference());
    }

    @Test
    void neftSalaryNarration() {
        var n = NarrationParser.parse("NEFT CR-CITI0000002-ACME TECHNOLOGIES PVT LTD-SALARY NOV 2026-CITIN26123456789");
        assertEquals(PaymentMethod.NEFT, n.method());
        assertEquals("ACME TECHNOLOGIES PVT LTD", n.counterparty());
        assertEquals("CITIN26123456789", n.reference());
    }

    @Test
    void posAndAtmNarrations() {
        assertEquals("AMAZON PAY INDIA", NarrationParser.parse("POS 416021XXXXXX1234 AMAZON PAY INDIA").counterparty());
        assertEquals(PaymentMethod.CARD, NarrationParser.parse("POS 416021XXXXXX1234 AMAZON PAY INDIA").method());
        assertEquals(PaymentMethod.ATM, NarrationParser.parse("ATM WDL/CASH/431299991111").method());
    }

    @Test
    void hdfcStyleCsvWithPreamble() {
        String csv = """
                HDFC BANK Ltd.,,,,,,
                Statement of account,,,,,,
                Date,Narration,Chq./Ref.No.,Value Dt,Withdrawal Amt.,Deposit Amt.,Closing Balance
                01/11/26,NEFT CR-CITI0000002-ACME TECHNOLOGIES PVT LTD-SALARY,CITIN26123456789,01/11/26,,"85,000.00","1,02,345.10"
                03/11/26,UPI-SWIGGY-SWIGGY@ICICI-ICIC0DC0099-431212345678-ORDER,0000431212345678,03/11/26,450.00,,"1,01,895.10"
                ,,,,,,
                *** End of statement ***,,,,,,
                """;
        var result = CsvStatementParser.parse(csv);
        assertEquals(2, result.rows().size());
        assertTrue(result.errors().isEmpty());
        var salary = result.rows().get(0);
        assertEquals(LocalDate.of(2026, 11, 1), salary.date());
        assertEquals(Direction.CREDIT, salary.direction());
        assertEquals(new BigDecimal("85000.00"), salary.amount());
        assertEquals(new BigDecimal("102345.10"), salary.balance());
        var swiggy = result.rows().get(1);
        assertEquals(Direction.DEBIT, swiggy.direction());
        assertEquals(new BigDecimal("450.00"), swiggy.amount());
        assertEquals("0000431212345678", swiggy.reference());
    }

    @Test
    void amountPlusDrCrLayout() {
        String csv = """
                Txn Date,Description,Amount,Dr/Cr,Balance
                17 Nov 2026,UPI/DR/431200001111/UBER/UTIB/uber@axisbank,"312.40",DR,5000.00
                18 Nov 2026,Interest credit,12.00,CR,5012.00
                """;
        var result = CsvStatementParser.parse(csv);
        assertEquals("amount + Dr/Cr column", result.detectedLayout());
        assertEquals(Direction.DEBIT, result.rows().get(0).direction());
        assertEquals(new BigDecimal("312.40"), result.rows().get(0).amount());
        assertEquals(Direction.CREDIT, result.rows().get(1).direction());
    }

    @Test
    void badDateReportedAsRowError() {
        String csv = """
                Date,Narration,Withdrawal Amt.,Deposit Amt.
                31/02/2026,Something,100.00,
                05/11/2026,OK row,50.00,
                """;
        var result = CsvStatementParser.parse(csv);
        assertEquals(1, result.rows().size());
        assertEquals(1, result.errors().size());
        assertEquals(2, result.errors().get(0).rowNumber());
    }

    @Test
    void unrecognisedFileThrows() {
        assertThrows(CsvStatementParser.UnrecognisedFormatException.class,
                () -> CsvStatementParser.parse("name,age\nbob,3\n"));
    }

    @Test
    void quotedFieldsWithCommasAndNewlines() {
        var rows = SimpleCsv.parse("a,\"b, c\",\"d \"\"q\"\"\"\r\n1,\"multi\nline\",3\n");
        assertEquals(2, rows.size());
        assertEquals("b, c", rows.get(0).get(1));
        assertEquals("d \"q\"", rows.get(0).get(2));
        assertEquals("multi\nline", rows.get(1).get(1));
    }
}
