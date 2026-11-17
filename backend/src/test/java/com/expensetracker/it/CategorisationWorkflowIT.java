package com.expensetracker.it;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CategorisationWorkflowIT extends IntegrationTestBase {

    @Test
    void defaultModeAsksForEveryTransactionButStillSuggests() throws Exception {
        Session s = register();
        UUID acc = createAccount(s, "HDFC", "BANK", "4821", "0");
        UUID t = manual(s, acc, "DEBIT", "450.00", "2026-10-05", "SWIGGY");
        JsonNode tx = txn(s, t);
        assertEquals("PENDING", tx.get("categorisationStatus").asText());
        assertTrue(tx.get("category").isNull());
        assertEquals("Food & Dining > Food Delivery", tx.get("suggestedCategory").get("path").asText());
        assertTrue(tx.get("needsReview").asBoolean());
        assertEquals(1, getJson(s, "/api/review/count").get("count").asLong());
        assertEquals(1, getJson(s, "/api/review").get("totalElements").asLong());
    }

    @Test
    void rememberingAMerchantDrivesFutureSuggestionsAndUserRulesBeatTheDictionary() throws Exception {
        Session s = register();
        UUID acc = createAccount(s, "HDFC", "BANK", "4821", "0");
        UUID groceries = categoryId(s, "Groceries");
        UUID first = manual(s, acc, "DEBIT", "300.00", "2026-10-05", "SWIGGY");
        JsonNode res = postJson(s, "/api/transactions/" + first + "/categorise",
                Map.of("categoryId", groceries, "remember", "MERCHANT"), 200);
        assertTrue(res.get("ruleSaved").asBoolean());
        assertEquals("CONFIRMED", res.get("transaction").get("categorisationStatus").asText());

        UUID second = manual(s, acc, "DEBIT", "520.00", "2026-10-06", "SWIGGY");
        JsonNode tx = txn(s, second);
        assertEquals("PENDING", tx.get("categorisationStatus").asText(), "ALWAYS_ASK still waits for approval");
        assertEquals("Groceries", tx.get("suggestedCategory").get("path").asText(), "merchant rule wins over the built-in dictionary");
        assertEquals(0.99, tx.get("suggestionConfidence").asDouble(), 0.0001);
        assertEquals(1, getJson(s, "/api/rules").size());
    }

    @Test
    void semiAutomaticCategorisesOnlyHighConfidenceSuggestions() throws Exception {
        Session s = register();
        putJson(s, "/api/preferences", Map.of("categorisationMode", "SEMI_AUTOMATIC"), 200);
        UUID acc = createAccount(s, "HDFC", "BANK", "4821", "0");
        UUID food = categoryId(s, "Food & Dining > Food Delivery");

        UUID dictionaryOnly = manual(s, acc, "DEBIT", "200.00", "2026-10-05", "ZOMATO"); // 0.80 < 0.90
        assertEquals("PENDING", txn(s, dictionaryOnly).get("categorisationStatus").asText());

        postJson(s, "/api/transactions/" + dictionaryOnly + "/categorise", Map.of("categoryId", food, "remember", "MERCHANT"), 200);
        UUID ruled = manual(s, acc, "DEBIT", "210.00", "2026-10-06", "ZOMATO"); // rule 0.99
        JsonNode tx = txn(s, ruled);
        assertEquals("AUTO", tx.get("categorisationStatus").asText());
        assertEquals(food.toString(), tx.get("category").get("id").asText());
        assertFalse(tx.get("needsReview").asBoolean());
        // user can still change an auto-categorised transaction
        UUID other = categoryId(s, "Other");
        assertEquals("CONFIRMED", postJson(s, "/api/transactions/" + ruled + "/categorise", Map.of("categoryId", other), 200)
                .get("transaction").get("categorisationStatus").asText());
    }

    @Test
    void applyToHistoryRecategorisesTheMerchantsOtherTransactions() throws Exception {
        Session s = register();
        UUID acc = createAccount(s, "HDFC", "BANK", "4821", "0");
        UUID a = manual(s, acc, "DEBIT", "100.00", "2026-10-01", "Sharma General Stores");
        UUID b = manual(s, acc, "DEBIT", "150.00", "2026-10-02", "SHARMA GENERAL STORES PVT LTD");
        UUID c = manual(s, acc, "DEBIT", "175.00", "2026-10-03", "sharma general stores");
        UUID groceries = categoryId(s, "Groceries");
        JsonNode res = postJson(s, "/api/transactions/" + c + "/categorise", Map.of("categoryId", groceries, "remember", "APPLY_HISTORICAL"), 200);
        assertEquals(2, res.get("appliedToOthers").asInt());
        assertEquals("Groceries", txn(s, a).get("category").get("name").asText());
        assertEquals("Groceries", txn(s, b).get("category").get("name").asText());
        assertEquals(0, getJson(s, "/api/review/count").get("count").asLong());
    }

    @Test
    void bulkConfirmAcceptsSuggestionsAndSkipsTheRest() throws Exception {
        Session s = register();
        UUID acc = createAccount(s, "HDFC", "BANK", "4821", "0");
        UUID uber = manual(s, acc, "DEBIT", "220.00", "2026-10-01", "UBER");
        UUID netflix = manual(s, acc, "DEBIT", "649.00", "2026-10-02", "NETFLIX");
        UUID unknown = manual(s, acc, "DEBIT", "90.00", "2026-10-03", "Qwerty Zxcv");
        JsonNode res = postJson(s, "/api/review/bulk-confirm", Map.of("ids", List.of(uber, netflix, unknown, UUID.randomUUID())), 200);
        assertEquals(2, res.get("confirmed").asInt());
        assertEquals(2, res.get("skipped").asInt());
        assertEquals("Transportation > Cab & Auto", txn(s, uber).get("category").get("path").asText());
        assertEquals("PENDING", txn(s, unknown).get("categorisationStatus").asText());
    }

    @Test
    void creditInExpenseCategoryBecomesRefundAndDebitInIncomeCategoryIsRejected() throws Exception {
        Session s = register();
        UUID acc = createAccount(s, "HDFC", "BANK", "4821", "0");
        UUID refund = manual(s, acc, "CREDIT", "150.00", "2026-10-04", "SWIGGY");
        UUID food = categoryId(s, "Food & Dining > Food Delivery");
        assertEquals("REFUND", postJson(s, "/api/transactions/" + refund + "/categorise", Map.of("categoryId", food), 200)
                .get("transaction").get("kind").asText());
        UUID debit = manual(s, acc, "DEBIT", "10.00", "2026-10-04", "X");
        postJson(s, "/api/transactions/" + debit + "/categorise", Map.of("categoryId", categoryId(s, "Salary")), 400);
    }

    @Test
    void splitsMustAddUpExactly() throws Exception {
        Session s = register();
        UUID acc = createAccount(s, "HDFC", "BANK", "4821", "0");
        UUID t = manual(s, acc, "DEBIT", "1000.00", "2026-10-05", "DMART");
        UUID groceries = categoryId(s, "Groceries");
        UUID personal = categoryId(s, "Personal Care");
        putJson(s, "/api/transactions/" + t + "/splits", List.of(
                Map.of("categoryId", groceries, "amount", "600.00"), Map.of("categoryId", personal, "amount", "399.99")), 400);
        JsonNode tx = putJson(s, "/api/transactions/" + t + "/splits", List.of(
                Map.of("categoryId", groceries, "amount", "600.00"), Map.of("categoryId", personal, "amount", "400.00")), 200);
        assertEquals(2, tx.get("splits").size());
        assertEquals("CONFIRMED", tx.get("categorisationStatus").asText());
        JsonNode summary = summary(s, "2026-10-01", "2026-10-31");
        assertEquals(0, summary.get("expenses").decimalValue().compareTo(new java.math.BigDecimal("1000.00")));
        assertEquals(2, summary.get("expenseByCategory").size());
    }

    @Test
    void historyRecordsEveryDecision() throws Exception {
        Session s = register();
        UUID acc = createAccount(s, "HDFC", "BANK", "4821", "0");
        UUID t = manual(s, acc, "DEBIT", "250.00", "2026-10-05", "SWIGGY");
        postJson(s, "/api/transactions/" + t + "/categorise", Map.of("categoryId", categoryId(s, "Groceries")), 200);
        postJson(s, "/api/transactions/" + t + "/categorise", Map.of("categoryId", categoryId(s, "Food & Dining > Food Delivery")), 200);
        JsonNode history = getJson(s, "/api/transactions/" + t).get("history");
        assertEquals(2, history.size());
        assertEquals("Groceries", history.get(1).get("from").get("name").asText());
    }
}
