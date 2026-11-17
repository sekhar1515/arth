package com.expensetracker.categorisation;

import com.expensetracker.merchant.MerchantNormaliser;
import com.expensetracker.transaction.Direction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class KeywordAndMerchantTest {

    @Test
    void merchantAliasesCollapseLegalNames() {
        assertEquals("Swiggy", MerchantNormaliser.normalise("BUNDL TECHNOLOGIES PVT LTD").displayName());
        assertEquals("Swiggy", MerchantNormaliser.normalise("swiggy@icici").displayName());
        assertEquals("Swiggy", MerchantNormaliser.normalise("SWIGGY*ORDER 12345678").displayName());
        assertEquals("swiggy", MerchantNormaliser.normalise("SWIGGY").key());
        assertEquals("Ola", MerchantNormaliser.normalise("ANI TECHNOLOGIES PVT LTD").displayName());
    }

    @Test
    void unknownMerchantIsTitleCasedWithoutNoise() {
        var n = MerchantNormaliser.normalise("SHARMA GENERAL STORES PVT LTD");
        assertEquals("Sharma General Stores", n.displayName());
        assertEquals("sharmageneralstores", n.key());
    }

    @Test
    void personNameKept() {
        assertEquals("Rahul Kumar", MerchantNormaliser.normalise("RAHUL KUMAR").displayName());
    }

    @Test
    void swiggyIsFoodDeliveryButInstamartIsGroceries() {
        var food = KeywordDictionary.lookup("Swiggy", "UPI-SWIGGY-ORDER", Direction.DEBIT).orElseThrow();
        assertEquals("Food & Dining > Food Delivery", food.categoryPath());
        var groceries = KeywordDictionary.lookup("Swiggy", "SWIGGY INSTAMART ORDER", Direction.DEBIT).orElseThrow();
        assertEquals("Groceries", groceries.categoryPath());
    }

    @Test
    void salaryOnlyMatchesCredits() {
        assertEquals("Salary", KeywordDictionary.lookup("Acme", "NEFT SALARY NOV", Direction.CREDIT).orElseThrow().categoryPath());
        assertTrue(KeywordDictionary.lookup("Acme", "NEFT SALARY NOV", Direction.DEBIT).isEmpty());
    }

    @Test
    void creditCardBillPaymentIsATransferHint() {
        var m = KeywordDictionary.lookup(null, "BILLDESK CC PAYMENT HDFC CREDIT CARD", Direction.DEBIT).orElseThrow();
        assertEquals(KeywordDictionary.Hint.CREDIT_CARD_PAYMENT, m.hint());
    }

    @Test
    void wordBoundariesRespected() {
        // "OLA" must not match inside "COLA"
        assertTrue(KeywordDictionary.lookup("Coca Cola Store", "POS COCA COLA", Direction.DEBIT)
                .map(m -> !m.categoryPath().startsWith("Transportation")).orElse(true));
    }
}
