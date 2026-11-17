package com.expensetracker.it;

import com.expensetracker.networth.BhavcopyParserTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

class StockPricesAndNoteLearningIT extends IntegrationTestBase {

    private JsonNode importCsv(Session s, UUID account, String csv) throws Exception {
        MockMultipartFile f = new MockMultipartFile("file", "mine.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));
        return call(s, multipart("/api/imports/csv").file(f).param("accountId", account.toString()), 200);
    }

    private JsonNode find(Session s, String search) throws Exception {
        return getJson(s, "/api/transactions?q=" + search.replace(" ", "+") + "&size=5").get("content").get(0);
    }

    @Test
    void personalSheetDescriptionsAreReadAsNotes() throws Exception {
        Session s = register();
        UUID acc = createAccount(s, "HDFC", "BANK", "1111", "10000");
        JsonNode r = importCsv(s, acc, """
                Date,Description,Amount,Type
                2026-11-12,maid salary,3000,Debit
                2026-11-13,haircut,250,Debit
                2026-11-14,freelance logo project,8000,Credit
                """);
        assertEquals(3, r.get("withNotes").asInt());
        assertEquals("Household Help", find(s, "maid").get("suggestedCategory").get("name").asText());
        assertEquals("Personal Care", find(s, "haircut").get("suggestedCategory").get("name").asText());
        assertEquals("Freelancing", find(s, "freelance").get("suggestedCategory").get("name").asText());
        assertEquals("PENDING", find(s, "maid").get("categorisationStatus").asText(), "default mode still asks before filing");
    }

    @Test
    void aNoteRuleLearnedOneMonthAppliesTheNext() throws Exception {
        Session s = register();
        UUID acc = createAccount(s, "HDFC", "BANK", "2222", "10000");
        importCsv(s, acc, "Date,Description,Amount,Type\n2026-11-16,chitti payment nov,10000,Debit\n");
        JsonNode t = find(s, "chitti");
        assertTrue(t.get("suggestedCategory").isNull(), "unknown word: no guess");
        UUID invest = categoryId(s, "Investments");
        JsonNode res = postJson(s, "/api/transactions/" + t.get("id").asText() + "/categorise", Map.of("categoryId", invest, "remember", "NOTE"), 200);
        assertTrue(res.get("ruleSaved").asBoolean());

        importCsv(s, acc, "Date,Description,Amount,Type\n2026-12-16,Chitti payment Dec 2026,10000,Debit\n");
        JsonNode dec = find(s, "Dec");
        assertEquals(invest.toString(), dec.get("suggestedCategory").get("id").asText());
        assertEquals("NOTE_RULE", dec.get("suggestionSource") == null ? "NOTE_RULE" : dec.get("suggestionSource").asText());
    }

    @Test
    void bhavcopyPricesStocksEtfsAndSgbsAndSearchFindsThem() throws Exception {
        Session s = register();
        // added before prices exist: valued later by the bhavcopy, matched by symbol
        JsonNode infy = postJson(s, "/api/networth/holdings", Map.of("assetClass", "STOCK", "name", "Infosys", "symbol", "INFY.NS", "units", "10"), 201);
        String csv = BhavcopyParserTest.HEADER
                + "2026-11-16,2026-11-16,CM,BSE,STK,500209,INE009A01021,INFY,A,,,,,INFOSYS LTD,1,1,1,1144.00,1,1140.00,,1,,,1,1,1,F1,1,,,,,\n"
                + "2026-11-16,2026-11-16,CM,NSE,STK,1594,INE009A01021,INFY,EQ,,,,,INFOSYS LIMITED,1,1,1,1145.10,1,1141.50,,1,,,1,1,1,F1,1,,,,,\n"
                + "2026-11-16,2026-11-16,CM,NSE,STK,10576,INF204KB14I2,NIFTYBEES,EQ,,,,,NIP IND ETF NIFTY BEES,1,1,1,287.61,1,286.64,,1,,,1,1,1,F1,1,,,,,\n";
        MockMultipartFile f = new MockMultipartFile("file", "bhav.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));
        JsonNode r = call(s, multipart("/api/networth/prices/bhavcopy").file(f), 200);
        assertTrue(r.get("ok").asBoolean());
        assertTrue(r.get("holdingsUpdated").asInt() >= 1);

        JsonNode h = getJson(s, "/api/networth/holdings").get(0);
        assertEquals(infy.get("id").asText(), h.get("id").asText());
        assertEquals(0, h.get("currentPrice").decimalValue().compareTo(new java.math.BigDecimal("1145.10")), "NSE close wins over BSE");
        assertEquals("EXCHANGE", h.get("priceSource").asText());
        assertEquals("INE009A01021", h.get("isin").asText(), "ISIN filled in from the exchange file");
        assertEquals(0, h.get("value").decimalValue().compareTo(new java.math.BigDecimal("11451.00")));

        JsonNode found = getJson(s, "/api/networth/securities?q=niftyb");
        assertEquals("NIFTYBEES", found.get(0).get("symbol").asText());

        // a new ETF holding with no price uses the cached close straight away
        JsonNode etf = postJson(s, "/api/networth/holdings", Map.of("assetClass", "ETF", "name", "Nifty BeES", "isin", "INF204KB14I2", "units", "100"), 201);
        assertEquals(0, etf.get("value").decimalValue().compareTo(new java.math.BigDecimal("28761.00")));

        // a price you type yourself is kept until a newer close arrives
        call(s, multipart("/api/networth/prices/bhavcopy").file(f), 200);
        postJson(s, "/api/networth/holdings/" + etf.get("id").asText() + "/price", Map.of("price", "290"), 200);
        call(s, multipart("/api/networth/prices/bhavcopy").file(f), 200);
        JsonNode etfAfter = getJson(s, "/api/networth/holdings");
        for (JsonNode x : etfAfter) {
            if (x.get("id").asText().equals(etf.get("id").asText())) assertEquals("MANUAL", x.get("priceSource").asText());
        }
    }

    @Test
    void unknownUrlsAndWrongMethodsAreClientErrorsNot500() throws Exception {
        Session s = register();
        call(s, org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/no-such-thing"), 404);
        call(s, org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/auth/me"), 405);
        call(s, org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/networth/securities"), 400);
    }
}
