package com.expensetracker.it;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TransfersAndCalculationsIT extends IntegrationTestBase {

    private static void assertMoney(String expected, JsonNode actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual.decimalValue()), "expected " + expected + " but was " + actual);
    }

    private JsonNode account(Session s, UUID id) throws Exception {
        for (JsonNode a : getJson(s, "/api/accounts")) if (a.get("id").asText().equals(id.toString())) return a;
        throw new AssertionError("account not found");
    }

    @Test
    void internalTransferMovesBalancesButIsNeitherIncomeNorExpense() throws Exception {
        Session s = register();
        UUID hdfc = createAccount(s, "HDFC", "BANK", "4821", "50000.00");
        UUID sbi = createAccount(s, "SBI", "BANK", "3307", "1000.00");
        UUID t = manual(s, hdfc, "DEBIT", "10000.00", "2026-10-05", null);
        JsonNode res = postJson(s, "/api/transactions/" + t + "/mark-transfer", Map.of("counterpartAccountId", sbi), 200);
        assertTrue(res.get("counterpartCreated").asBoolean());
        UUID other = UUID.fromString(res.get("counterpartTransactionId").asText());

        assertMoney("40000.00", account(s, hdfc).get("balance"));
        assertMoney("11000.00", account(s, sbi).get("balance"));
        JsonNode summary = summary(s, "2026-10-01", "2026-10-31");
        assertMoney("0", summary.get("income"));
        assertMoney("0", summary.get("expenses"));
        assertEquals("TRANSFER", txn(s, other).get("kind").asText());
        assertEquals(0, getJson(s, "/api/review/count").get("count").asLong());
    }

    @Test
    void transferBetweenYourOwnAccountsInOneStep() throws Exception {
        Session s = register();
        UUID hdfc = createAccount(s, "HDFC", "BANK", "4821", "50000.00");
        UUID sbi = createAccount(s, "SBI", "BANK", "3307", "1000.00");
        JsonNode res = postJson(s, "/api/transactions/transfer", Map.of("fromAccountId", hdfc, "toAccountId", sbi, "amount", "7500.00",
                "date", "2026-10-07", "paymentMethod", "IMPS", "notes", "move to savings"), 201);
        assertFalse(res.get("matchedExisting").asBoolean());
        assertEquals("TRANSFER", res.get("out").get("kind").asText());
        assertEquals("TRANSFER", txn(s, UUID.fromString(res.get("inTransactionId").asText())).get("kind").asText());
        assertMoney("42500.00", account(s, hdfc).get("balance"));
        assertMoney("8500.00", account(s, sbi).get("balance"));
        JsonNode summary = summary(s, "2026-10-01", "2026-10-31");
        assertMoney("0", summary.get("income"));
        assertMoney("0", summary.get("expenses"));
        postJson(s, "/api/transactions/transfer", Map.of("fromAccountId", hdfc, "toAccountId", hdfc, "amount", "1", "date", "2026-10-07"), 400);
    }

    @Test
    void settingTheBalanceAdjustsTheStartingBalanceOnly() throws Exception {
        Session s = register();
        UUID hdfc = createAccount(s, "HDFC", "BANK", "4821", "10000.00");
        manual(s, hdfc, "DEBIT", "2500.00", "2026-10-05", "Shop");
        assertMoney("7500.00", account(s, hdfc).get("balance"));
        JsonNode a = postJson(s, "/api/accounts/" + hdfc + "/balance", Map.of("balance", "8120.50"), 200);
        assertMoney("8120.50", a.get("balance"));
        assertMoney("10620.50", a.get("openingBalance"));
        assertEquals(1, a.get("transactionCount").asInt(), "transactions untouched");
    }

    @Test
    void transferPairsWithAnExistingCounterpartInsteadOfCreatingOne() throws Exception {
        Session s = register();
        UUID hdfc = createAccount(s, "HDFC", "BANK", "4821", "0");
        UUID sbi = createAccount(s, "SBI", "BANK", "3307", "0");
        UUID out = manual(s, hdfc, "DEBIT", "2500.00", "2026-10-05", null);
        UUID in = manual(s, sbi, "CREDIT", "2500.00", "2026-10-06", null);
        JsonNode res = postJson(s, "/api/transactions/" + out + "/mark-transfer", Map.of("counterpartAccountId", sbi), 200);
        assertFalse(res.get("counterpartCreated").asBoolean());
        assertEquals(in.toString(), res.get("counterpartTransactionId").asText());
        assertEquals(2, getJson(s, "/api/transactions").get("totalElements").asLong());
    }

    @Test
    void creditCardPurchasesCountOnceAndTheBillPaymentIsATransfer() throws Exception {
        Session s = register();
        UUID bank = createAccount(s, "HDFC", "BANK", "4821", "20000.00");
        UUID card = createAccount(s, "ICICI Card", "CREDIT_CARD", "9012", "0");
        UUID shop = manual(s, card, "DEBIT", "3000.00", "2026-10-02", "AMAZON PAY INDIA");
        postJson(s, "/api/transactions/" + shop + "/categorise", Map.of("categoryId", categoryId(s, "Shopping > Online Shopping")), 200);
        assertMoney("3000.00", summary(s, "2026-10-01", "2026-10-31").get("accounts").get("creditCardOutstanding"));

        var body = new HashMap<String, Object>();
        body.put("accountId", bank);
        body.put("direction", "DEBIT");
        body.put("amount", "3000.00");
        body.put("date", "2026-10-20");
        body.put("description", "BIL/ONL/431299990000/ICICI CREDIT CARD/CC PAYMENT");
        UUID bill = UUID.fromString(postJson(s, "/api/transactions", body, 201).get("id").asText());
        assertTrue(txn(s, bill).get("suggestionReason").asText().contains("credit card"));
        postJson(s, "/api/transactions/" + bill + "/mark-transfer", Map.of("counterpartAccountId", card), 200);

        JsonNode summary = summary(s, "2026-10-01", "2026-10-31");
        assertMoney("3000.00", summary.get("expenses"));
        assertMoney("0", summary.get("accounts").get("creditCardOutstanding"));
        assertMoney("17000.00", summary.get("accounts").get("netWorth"));
    }

    @Test
    void pendingCountsInCashFlowButNotInCategoryAnalyticsAndFailedNeverCounts() throws Exception {
        Session s = register();
        UUID acc = createAccount(s, "HDFC", "BANK", "4821", "0");
        UUID salary = manual(s, acc, "CREDIT", "60000.00", "2026-10-01", "ACME TECHNOLOGIES");
        postJson(s, "/api/transactions/" + salary + "/categorise", Map.of("categoryId", categoryId(s, "Salary")), 200);
        UUID food = manual(s, acc, "DEBIT", "450.00", "2026-10-03", "SWIGGY");
        postJson(s, "/api/transactions/" + food + "/categorise", Map.of("categoryId", categoryId(s, "Food & Dining > Food Delivery")), 200);
        manual(s, acc, "DEBIT", "300.00", "2026-10-04", "Unknown Shop");
        UUID failed = manual(s, acc, "DEBIT", "999.00", "2026-10-05", "Something");
        putJson(s, "/api/transactions/" + failed, Map.of("status", "FAILED"), 200);
        UUID refund = manual(s, acc, "CREDIT", "150.00", "2026-10-06", "SWIGGY");
        postJson(s, "/api/transactions/" + refund + "/mark-refund", Map.of("originalTransactionId", food), 200);

        JsonNode summary = summary(s, "2026-10-01", "2026-10-31");
        assertMoney("60000.00", summary.get("income"));
        assertMoney("600.00", summary.get("expenses"));          // 450 + 300 pending - 150 refund
        assertMoney("300.00", summary.get("pendingCategorisationAmount"));
        assertMoney("59400.00", summary.get("savings"));
        assertMoney("99.0", summary.get("savingsRate"));
        JsonNode cats = summary.get("expenseByCategory");
        assertEquals(1, cats.size(), "pending is not a category");
        assertEquals("Food & Dining", cats.get(0).get("name").asText());
        assertMoney("300.00", cats.get(0).get("amount"));       // 450 - 150 refund
        assertMoney("59400.00", getJson(s, "/api/accounts").get(1).get("balance")); // failed 999 excluded
    }

    @Test
    void monthlyTrendAndCsvExport() throws Exception {
        Session s = register();
        UUID acc = createAccount(s, "HDFC", "BANK", "4821", "0");
        var body = new HashMap<String, Object>();
        body.put("accountId", acc);
        body.put("direction", "DEBIT");
        body.put("amount", "400.00");
        body.put("date", "2026-10-03");
        body.put("description", "=HYPERLINK(\"http://evil\")");
        postJson(s, "/api/transactions", body, 201);
        JsonNode months = getJson(s, "/api/dashboard/monthly?months=3");
        assertEquals(3, months.size());
        String csv = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/transactions/export.csv")
                .cookie(s.cookie())).andReturn().getResponse().getContentAsString();
        assertTrue(csv.startsWith("Date,Time,Account"));
        assertFalse(csv.contains(",=HYPERLINK"), "formula injection neutralised");
        assertTrue(csv.contains("'=HYPERLINK"));
    }

    @Test
    void manualTransactionsCanBeDeleted() throws Exception {
        Session s = register();
        UUID acc = createAccount(s, "HDFC", "BANK", "4821", "0");
        UUID t = manual(s, acc, "DEBIT", "10.00", "2026-10-03", "Tea");
        call(s, org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/transactions/" + t), 200);
        call(s, org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/transactions/" + t), 404);
    }
}
