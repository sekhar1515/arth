package com.expensetracker.it;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

class DeletionAndPlanningIT extends IntegrationTestBase {

    private static final String STATEMENT = """
            Date,Narration,Chq./Ref.No.,Value Dt,Withdrawal Amt.,Deposit Amt.,Closing Balance
            01/10/26,NEFT CR-CITI0000002-ACME TECHNOLOGIES PVT LTD-SALARY,CITIN26123456789,01/10/26,,"85,000.00","85,000.00"
            03/10/26,UPI-SWIGGY-SWIGGY@ICICI-ICIC0DC0099-431212345678-ORDER,0000431212345678,03/10/26,450.00,,"84,550.00"
            04/10/26,UPI-CHAI POINT-CHAIPOINT@YBL-YESB0000001-431200000001-PAY,0000431200000001,04/10/26,20.00,,"84,530.00"
            """;

    private JsonNode upload(Session s, UUID account) throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "statement.csv", "text/csv", STATEMENT.getBytes(StandardCharsets.UTF_8));
        return call(s, multipart("/api/imports/csv").file(file).param("accountId", account.toString()), 200);
    }

    private List<String> ids(Session s, UUID account) throws Exception {
        List<String> out = new java.util.ArrayList<>();
        for (JsonNode t : getJson(s, "/api/transactions?accountId=" + account + "&size=100").get("content")) out.add(t.get("id").asText());
        return out;
    }

    @Test
    void deletedImportedRowsStayDeletedOnReimport() throws Exception {
        Session s = register();
        UUID acc = createAccount(s, "HDFC", "BANK", "4821", "0");
        upload(s, acc);
        List<String> before = ids(s, acc);
        assertEquals(3, before.size());
        JsonNode r = call(s, delete("/api/transactions/" + before.get(0)), 200);
        assertEquals(1, r.get("deleted").asInt());
        JsonNode again = upload(s, acc);
        assertEquals(0, again.get("imported").asInt());
        assertEquals(1, again.get("previouslyDeleted").asInt());
        assertEquals(2, ids(s, acc).size());
        // clearing tombstones lets it come back
        call(s, delete("/api/privacy/deleted-imports"), 200);
        assertEquals(1, upload(s, acc).get("imported").asInt());
    }

    @Test
    void bulkDeleteAndUndoImport() throws Exception {
        Session s = register();
        UUID acc = createAccount(s, "HDFC", "BANK", "4821", "0");
        JsonNode first = upload(s, acc);
        List<String> all = ids(s, acc);
        JsonNode bulk = postJson(s, "/api/transactions/bulk-delete", Map.of("ids", all.subList(0, 2)), 200);
        assertEquals(2, bulk.get("deleted").asInt());
        assertEquals(1, ids(s, acc).size());
        JsonNode undo = postJson(s, "/api/imports/" + first.get("syncId").asText() + "/undo", Map.of(), 200);
        assertEquals(1, undo.get("deleted").asInt());
        assertEquals(0, ids(s, acc).size());
        // undo leaves no tombstones: re-importing brings back only the rows not deleted individually
        assertEquals(1, upload(s, acc).get("imported").asInt());
    }

    @Test
    void accountDeleteNeedsConfirmationWhenItHasTransactions() throws Exception {
        Session s = register();
        UUID acc = createAccount(s, "Wrong account", "BANK", "1111", "0");
        manual(s, acc, "DEBIT", "99.00", "2026-10-03", "Tea");
        call(s, delete("/api/accounts/" + acc), 409);
        JsonNode r = call(s, delete("/api/accounts/" + acc + "?withTransactions=true"), 200);
        assertEquals(1, r.get("transactionsDeleted").asInt());
        call(s, get("/api/transactions?accountId=" + acc), 200);
    }

    @Test
    void deletingACategorySendsItsTransactionsBackToReview() throws Exception {
        Session s = register();
        UUID acc = createAccount(s, "HDFC", "BANK", "4821", "0");
        JsonNode cat = postJson(s, "/api/categories", Map.of("name", "Oops", "kind", "EXPENSE"), 201);
        UUID catId = UUID.fromString(cat.get("id").asText());
        UUID t = manual(s, acc, "DEBIT", "250.00", "2026-10-05", "Corner shop");
        postJson(s, "/api/transactions/" + t + "/categorise", Map.of("categoryId", catId, "remember", "ONLY_THIS"), 200);
        JsonNode r = call(s, delete("/api/categories/" + catId), 200);
        assertEquals(1, r.get("categoriesDeleted").asInt());
        assertEquals("PENDING", txn(s, t).get("categorisationStatus").asText());
    }

    @Test
    void budgetAndRecurringLifecycle() throws Exception {
        Session s = register();
        UUID food = categoryId(s, "Food & Dining");
        JsonNode b = postJson(s, "/api/budgets", Map.of("categoryId", food, "period", "MONTHLY", "amount", "8000"), 201);
        assertEquals("Food & Dining", b.get("name").asText());
        postJson(s, "/api/budgets", Map.of("categoryId", food, "period", "MONTHLY", "amount", "9000"), 409);
        call(s, delete("/api/budgets/" + b.get("id").asText()), 204);

        JsonNode r = postJson(s, "/api/recurring", Map.of("name", "Rent", "kind", "RENT", "frequency", "MONTHLY", "amount", "25000", "nextDue", "2030-01-05"), 201);
        assertEquals("ACTIVE", r.get("status").asText());
        assertEquals(0, new java.math.BigDecimal("300000.00").compareTo(r.get("annualCost").decimalValue()));
        call(s, delete("/api/recurring/" + r.get("id").asText()), 204);
    }

    @Test
    void reportsDownloadInEachFormat() throws Exception {
        Session s = register();
        UUID acc = createAccount(s, "HDFC", "BANK", "4821", "0");
        manual(s, acc, "DEBIT", "120.00", "2026-10-03", "Tea");
        for (String f : List.of("CSV", "XLSX", "PDF")) {
            var res = mvc.perform(get("/api/reports/download?type=TRANSACTIONS&format=" + f + "&from=2026-10-01&to=2026-10-31").cookie(s.cookie())).andReturn().getResponse();
            assertEquals(200, res.getStatus(), f);
            assertTrue(res.getContentAsByteArray().length > 100, f);
        }
    }

    @Test
    void assistantAnswersFromDataAndAdmitsWhatItCannot() throws Exception {
        Session s = register();
        UUID acc = createAccount(s, "HDFC", "BANK", "4821", "0");
        manual(s, acc, "DEBIT", "120.00", "2026-10-03", "Tea");
        JsonNode a = postJson(s, "/api/assistant/ask", Map.of("question", "how much did I spend in October 2026"), 200);
        assertEquals("SPEND_TOTAL", a.get("intent").asText());
        assertTrue(a.get("answer").asText().contains("₹120"), a.toString());
        JsonNode h = postJson(s, "/api/assistant/ask", Map.of("question", "write me a poem"), 200);
        assertEquals("HELP", h.get("intent").asText());
    }

    @Test
    void notificationsAreDedupedAndDeletable() throws Exception {
        Session s = register();
        UUID food = categoryId(s, "Food & Dining");
        UUID acc = createAccount(s, "HDFC", "BANK", "4821", "0");
        postJson(s, "/api/budgets", Map.of("categoryId", food, "period", "MONTHLY", "amount", "100"), 201);
        UUID t = manual(s, acc, "DEBIT", "150.00", java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")).toString(), "Cafe");
        postJson(s, "/api/transactions/" + t + "/categorise", Map.of("categoryId", food, "remember", "ONLY_THIS"), 200);
        assertTrue(postJson(s, "/api/notifications/check", Map.of(), 200).get("created").asInt() >= 1);
        assertEquals(0, postJson(s, "/api/notifications/check", Map.of(), 200).get("created").asInt());
        JsonNode list = getJson(s, "/api/notifications");
        call(s, delete("/api/notifications/" + list.get(0).get("id").asText()), 204);
    }

    @Test
    void exportAndDeleteAccount() throws Exception {
        Session s = register();
        JsonNode export = getJson(s, "/api/privacy/export");
        assertEquals("arth-export-v1", export.get("format").asText());
        assertFalse(export.toString().contains("password_hash"));
        postJson(s, "/api/privacy/delete-account", Map.of("password", "correct-horse-42", "confirmation", "nope"), 400);
        postJson(s, "/api/privacy/delete-account", Map.of("password", "correct-horse-42", "confirmation", "DELETE"), 204);
    }
}
