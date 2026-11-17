package com.expensetracker.it;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

class IngestWebhookIT extends IntegrationTestBase {

    private JsonNode send(String token, String text, int expected) throws Exception {
        var req = post("/api/ingest/sms").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("text", text)));
        if (token != null) req.header("X-Ingest-Token", token);
        return call(null, req, expected);
    }

    @Test
    void smsAlertsBecomePendingTransactionsOnTheMatchingAccount() throws Exception {
        Session s = register();
        UUID hdfc = createAccount(s, "HDFC", "BANK", "4821", "0");
        JsonNode conn = postJson(s, "/api/integrations/SMS_FORWARDER/connect", Map.of(), 201);
        String token = conn.get("ingestToken").asText();
        assertTrue(token.startsWith("arth_"));
        assertEquals("/api/ingest/sms", conn.get("ingestPath").asText());

        JsonNode r = send(token, "Sent Rs.1,250.50 From HDFC Bank A/C *4821 To ZOMATO LTD On 16/10/26 Ref 431298765432", 200);
        assertEquals("CREATED", r.get("outcome").asText());
        JsonNode tx = txn(s, UUID.fromString(r.get("transactionId").asText()));
        assertEquals(hdfc.toString(), tx.get("account").get("id").asText());
        assertEquals("Zomato", tx.get("merchant").get("name").asText());
        assertEquals("PENDING", tx.get("categorisationStatus").asText());
        assertEquals("SMS", tx.get("sources").get(0).asText());

        // the same SMS delivered twice is idempotent
        assertEquals("ALREADY_IMPORTED", send(token, "Sent Rs.1,250.50 From HDFC Bank A/C *4821 To ZOMATO LTD On 16/10/26 Ref 431298765432", 200)
                .get("outcome").asText());

        JsonNode catalogue = getJson(s, "/api/integrations");
        boolean found = false;
        for (JsonNode e : catalogue) {
            if (e.get("provider").get("key").asText().equals("SMS_FORWARDER")) {
                found = true;
                assertEquals("CONNECTED", e.get("connection").get("status").asText());
                assertFalse(e.get("connection").get("lastSyncAt").isNull());
            }
            if (e.get("provider").get("key").asText().equals("PHONEPE_API")) {
                assertEquals("UNAVAILABLE", e.get("provider").get("availability").asText());
            }
        }
        assertTrue(found);
    }

    @Test
    void otpMessagesAreIgnoredAndNotStored() throws Exception {
        Session s = register();
        createAccount(s, "HDFC", "BANK", "4821", "0");
        String token = postJson(s, "/api/integrations/SMS_FORWARDER/connect", Map.of(), 201).get("ingestToken").asText();
        JsonNode r = send(token, "482913 is your OTP for a txn of Rs 450.00 on HDFC Bank card XX4821. Do not share.", 200);
        assertEquals("IGNORED", r.get("outcome").asText());
        assertEquals(0, getJson(s, "/api/transactions").get("totalElements").asLong());
    }

    @Test
    void invalidOrRevokedTokensAreRejected() throws Exception {
        Session s = register();
        createAccount(s, "HDFC", "BANK", "4821", "0");
        JsonNode conn = postJson(s, "/api/integrations/SMS_FORWARDER/connect", Map.of(), 201);
        String token = conn.get("ingestToken").asText();
        send(null, "Rs.10 debited from A/c XX4821", 401);
        send("arth_not-a-real-token-at-all-000000", "Rs.10 debited from A/c XX4821", 401);
        postJson(s, "/api/integrations/connections/" + conn.get("connection").get("id").asText() + "/disconnect", Map.of(), 204);
        send(token, "Rs.10 debited from A/c XX4821", 401);
    }

    @Test
    void unknownAccountDigitsAreReportedNotGuessed() throws Exception {
        Session s = register();
        createAccount(s, "HDFC", "BANK", "4821", "0");
        String token = postJson(s, "/api/integrations/SMS_FORWARDER/connect", Map.of(), 201).get("ingestToken").asText();
        JsonNode err = send(token, "Rs.99.00 debited from A/c XX7777 to VPA spotify@hdfcbank UPI Ref 431200001111", 422);
        assertTrue(err.get("error").get("message").asText().contains("7777"));
        assertEquals(0, getJson(s, "/api/transactions").get("totalElements").asLong());
    }

    @Test
    void unavailableProvidersCannotBeConnected() throws Exception {
        Session s = register();
        postJson(s, "/api/integrations/PHONEPE_API/connect", Map.of(), 400);
        postJson(s, "/api/integrations/ACCOUNT_AGGREGATOR/connect", Map.of(), 400);
    }
}
