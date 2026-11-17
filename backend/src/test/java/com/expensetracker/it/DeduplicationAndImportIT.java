package com.expensetracker.it;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

class DeduplicationAndImportIT extends IntegrationTestBase {

    private static final String STATEMENT = """
            HDFC BANK Ltd.,,,,,,
            Date,Narration,Chq./Ref.No.,Value Dt,Withdrawal Amt.,Deposit Amt.,Closing Balance
            01/10/26,NEFT CR-CITI0000002-ACME TECHNOLOGIES PVT LTD-SALARY,CITIN26123456789,01/10/26,,"85,000.00","85,000.00"
            03/10/26,UPI-SWIGGY-SWIGGY@ICICI-ICIC0DC0099-431212345678-ORDER,0000431212345678,03/10/26,450.00,,"84,550.00"
            04/10/26,UPI-CHAI POINT-CHAIPOINT@YBL-YESB0000001-431200000001-PAY,0000431200000001,04/10/26,20.00,,"84,530.00"
            04/10/26,UPI-CHAI POINT-CHAIPOINT@YBL-YESB0000001-431200000002-PAY,0000431200000002,04/10/26,20.00,,"84,510.00"
            """;

    private JsonNode upload(Session s, UUID account, String csv) throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "statement.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));
        return call(s, multipart("/api/imports/csv").file(file).param("accountId", account.toString()), 200);
    }

    @Test
    void reImportingTheSameStatementNeverDuplicates() throws Exception {
        Session s = register();
        UUID acc = createAccount(s, "HDFC", "BANK", "4821", "0");
        JsonNode first = upload(s, acc, STATEMENT);
        assertEquals(4, first.get("imported").asInt());
        assertEquals(0, first.get("errors").size());
        JsonNode second = upload(s, acc, STATEMENT);
        assertEquals(0, second.get("imported").asInt());
        assertEquals(4, second.get("alreadyImported").asInt());
        assertEquals(4, getJson(s, "/api/transactions").get("totalElements").asLong());
        // the two identical-looking ₹20 chai payments have different references and stay separate
        JsonNode summary = summary(s, "2026-10-01", "2026-10-31");
        assertEquals(0, summary.get("expenses").decimalValue().compareTo(new BigDecimal("490.00")));
        assertEquals(0, summary.get("income").decimalValue().compareTo(new BigDecimal("85000.00")));
    }

    @Test
    void alertAndStatementWithSameReferenceMergeIntoOneTransaction() throws Exception {
        Session s = register();
        UUID acc = createAccount(s, "HDFC", "BANK", "4821", "0");
        JsonNode conn = postJson(s, "/api/integrations/SMS_FORWARDER/connect", Map.of(), 201);
        String token = conn.get("ingestToken").asText();
        JsonNode alert = call(null, org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/ingest/sms")
                .header("X-Ingest-Token", token).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("text",
                        "Rs.450.00 debited from A/c XX4821 on 03-10-26 20:15 to VPA swiggy@icici (UPI Ref No 431212345678)"))), 200);
        assertEquals("CREATED", alert.get("outcome").asText());
        UUID id = UUID.fromString(alert.get("transactionId").asText());

        JsonNode imported = upload(s, acc, STATEMENT);
        assertEquals(1, imported.get("merged").asInt());
        assertEquals(3, imported.get("imported").asInt());
        JsonNode detail = getJson(s, "/api/transactions/" + id);
        assertEquals(2, detail.get("sources").size(), "SMS + CSV sources on one transaction");
        assertTrue(detail.get("transaction").get("timeKnown").asBoolean(), "keeps the alert's exact time");
        assertEquals(4, getJson(s, "/api/transactions").get("totalElements").asLong());
    }

    @Test
    void fuzzyDuplicatesAreFlaggedExcludedFromTotalsAndResolvable() throws Exception {
        Session s = register();
        UUID acc = createAccount(s, "HDFC", "BANK", "4821", "1000.00");
        UUID original = manual(s, acc, "DEBIT", "386.00", "2026-10-10", "SWIGGY");
        UUID dup = manual(s, acc, "DEBIT", "386.00", "2026-10-10", "Swiggy");
        JsonNode d = txn(s, dup);
        assertEquals("SUSPECTED", d.get("duplicateStatus").asText());
        assertEquals(original.toString(), d.get("duplicateOfId").asText());

        JsonNode summary = summary(s, "2026-10-01", "2026-10-31");
        assertEquals(0, summary.get("expenses").decimalValue().compareTo(new BigDecimal("386.00")), "counted once");
        assertEquals(1, summary.get("suspectedDuplicates").asLong());
        JsonNode accounts = getJson(s, "/api/accounts");
        JsonNode hdfc = accounts.get(1);
        assertEquals(0, hdfc.get("balance").decimalValue().compareTo(new BigDecimal("614.00")));

        postJson(s, "/api/review/duplicates/" + dup + "/resolve", Map.of("action", "MERGE"), 204);
        assertEquals(1, getJson(s, "/api/transactions").get("totalElements").asLong());
        assertEquals(2, getJson(s, "/api/transactions/" + original).get("sources").size());
    }

    @Test
    void keepingBothCountsBoth() throws Exception {
        Session s = register();
        UUID acc = createAccount(s, "HDFC", "BANK", "4821", "0");
        manual(s, acc, "DEBIT", "20.00", "2026-10-10", "Chai Point");
        UUID second = manual(s, acc, "DEBIT", "20.00", "2026-10-10", "Chai Point");
        postJson(s, "/api/review/duplicates/" + second + "/resolve", Map.of("action", "KEEP_BOTH"), 204);
        assertEquals(0, summary(s, "2026-10-01", "2026-10-31").get("expenses").decimalValue().compareTo(new BigDecimal("40.00")));
    }

    @Test
    void unrecognisedCsvIsRejectedWithAHelpfulMessage() throws Exception {
        Session s = register();
        UUID acc = createAccount(s, "HDFC", "BANK", "4821", "0");
        MockMultipartFile file = new MockMultipartFile("file", "x.csv", "text/csv", "name,age\nbob,3\n".getBytes(StandardCharsets.UTF_8));
        JsonNode err = call(s, multipart("/api/imports/csv").file(file).param("accountId", acc.toString()), 422);
        assertTrue(err.get("error").get("message").asText().contains("header row"));
    }
}
