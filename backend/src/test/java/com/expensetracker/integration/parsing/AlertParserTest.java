package com.expensetracker.integration.parsing;

import com.expensetracker.transaction.Direction;
import com.expensetracker.transaction.PaymentMethod;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.*;

class AlertParserTest {

    private AlertParser.ParsedAlert parsed(String sms) {
        AlertParser.Result r = AlertParser.parse(sms);
        return assertInstanceOf(AlertParser.Parsed.class, r).alert();
    }

    @Test
    void upiDebitWithVpaAndReference() {
        var a = parsed("Rs.450.00 debited from A/c XX1234 on 17-11-26 to VPA swiggy@icici (UPI Ref No 431212345678). Not you? Call 1800");
        assertEquals(Direction.DEBIT, a.direction());
        assertEquals(new BigDecimal("450.00"), a.amount());
        assertEquals("1234", a.accountLast4());
        assertEquals(LocalDate.of(2026, 11, 17), a.date());
        assertEquals("swiggy@icici", a.vpa());
        assertEquals("swiggy", a.counterparty());
        assertEquals("431212345678", a.reference());
        assertEquals(PaymentMethod.UPI, a.method());
        assertNull(a.paymentProvider(), "payee VPA must not be read as the payer's app");
    }

    @Test
    void hdfcSentStyle() {
        var a = parsed("Sent Rs.1,250.50 From HDFC Bank A/C *5678 To ZOMATO LTD On 16/11/26 Ref 431298765432 Not You? Call 18002586161");
        assertEquals(Direction.DEBIT, a.direction());
        assertEquals(new BigDecimal("1250.50"), a.amount());
        assertEquals("5678", a.accountLast4());
        assertEquals("ZOMATO LTD", a.counterparty());
        assertEquals("431298765432", a.reference());
        assertEquals(LocalDate.of(2026, 11, 16), a.date());
    }

    @Test
    void cardSpendWithTime() {
        var a = parsed("INR 2,999.00 spent on ICICI Bank Card XX9012 on 15-Nov-26 at AMAZON PAY INDIA. Avl Lmt: INR 1,20,000.00. 14:32");
        assertEquals(Direction.DEBIT, a.direction());
        assertEquals(new BigDecimal("2999.00"), a.amount());
        assertEquals("9012", a.accountLast4());
        assertEquals("AMAZON PAY INDIA", a.counterparty());
        assertEquals(PaymentMethod.CARD, a.method());
        assertEquals(LocalDate.of(2026, 11, 15), a.date());
        assertEquals(LocalTime.of(14, 32), a.time());
    }

    @Test
    void salaryCreditNeft() {
        var a = parsed("Your A/c XX1234 is credited with INR 85,000.00 on 01-11-2026 by NEFT from ACME TECHNOLOGIES PVT LTD. Avl Bal INR 1,02,345.10");
        assertEquals(Direction.CREDIT, a.direction());
        assertEquals(new BigDecimal("85000.00"), a.amount());
        assertEquals(PaymentMethod.NEFT, a.method());
        assertEquals("ACME TECHNOLOGIES PVT LTD", a.counterparty());
    }

    @Test
    void upiCreditFromPhonePeUserIdentifiesNothingAboutOurApp() {
        var a = parsed("Rs 500.00 credited to a/c XX1234 on 12/11/26 by a/c linked to VPA rahul.k@ybl (UPI Ref No 431255556666).");
        assertEquals(Direction.CREDIT, a.direction());
        assertEquals("rahul.k@ybl", a.vpa());
        assertEquals("PhonePe", a.paymentProvider());
    }

    @Test
    void otpMessagesAreRejectedAndNeverParsed() {
        var r = AlertParser.parse("123456 is your OTP for txn of Rs 450.00 at SWIGGY. Do not share it with anyone.");
        assertInstanceOf(AlertParser.Rejected.class, r);
    }

    @Test
    void messageWithoutAmountIsRejected() {
        assertInstanceOf(AlertParser.Rejected.class, AlertParser.parse("Your statement is ready"));
    }

    @Test
    void failedTransactionFlagged() {
        var a = parsed("Txn of Rs.300.00 debited from A/c XX1234 to merchant@paytm has failed. Amount will be reversed.");
        assertTrue(a.failed());
    }

    @Test
    void timestampFallsBackToReceiveTime() {
        var a = parsed("Rs.99.00 debited from A/c XX1234 to VPA spotify@hdfcbank UPI Ref 431200001111");
        var ts = AlertParser.timestamp(a, java.time.LocalDateTime.of(2026, 11, 17, 8, 5));
        assertEquals(java.time.LocalDateTime.of(2026, 11, 17, 8, 5), ts);
    }
}
