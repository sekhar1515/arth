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

    @Test
    void excelStatementsAndHoldingsImportLikeCsv() throws Exception {
        Session s = register();
        UUID acc = createAccount(s, "HDFC", "BANK", "3333", "10000");
        MockMultipartFile xlsx = new MockMultipartFile("file", "Statement Nov.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", com.expensetracker.integration.parsing.SpreadsheetFixtures.statement(false));
        JsonNode r = call(s, multipart("/api/imports/csv").file(xlsx).param("accountId", acc.toString()), 200);
        assertEquals(3, r.get("imported").asInt());
        assertEquals("Household Help", find(s, "maid").get("suggestedCategory").get("name").asText());
        // the same statement as legacy .xls is recognised as already imported
        MockMultipartFile xls = new MockMultipartFile("file", "statement.xls", "application/vnd.ms-excel",
                com.expensetracker.integration.parsing.SpreadsheetFixtures.statement(true));
        assertEquals(3, call(s, multipart("/api/imports/csv").file(xls).param("accountId", acc.toString()), 200).get("alreadyImported").asInt());

        MockMultipartFile html = new MockMultipartFile("file", "OpTransactionHistory.xls", "application/vnd.ms-excel",
                com.expensetracker.integration.parsing.SpreadsheetFixtures.HTML_XLS.getBytes(StandardCharsets.UTF_8));
        UUID acc2 = createAccount(s, "SBI", "BANK", "1234", "50000");
        assertEquals(2, call(s, multipart("/api/imports/csv").file(html).param("accountId", acc2.toString()), 200).get("imported").asInt());

        MockMultipartFile holdings = new MockMultipartFile("file", "holdings.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", com.expensetracker.integration.parsing.SpreadsheetFixtures.holdings());
        JsonNode h = call(s, multipart("/api/networth/holdings/import").file(holdings).param("platform", "Zerodha"), 200);
        assertEquals(2, h.get("created").asInt(), "one stock from the Equity sheet, one fund from the Mutual Funds sheet");
        JsonNode list = getJson(s, "/api/networth/holdings");
        assertEquals(2, list.size());
        boolean fund = false, stock = false;
        for (JsonNode x : list) {
            if (x.get("assetClass").asText().equals("MUTUAL_FUND")) fund = true;
            if (x.get("assetClass").asText().equals("STOCK")) stock = true;
        }
        assertTrue(fund && stock);

        MockMultipartFile junk = new MockMultipartFile("file", "broken.xlsx", "application/octet-stream", "nope".getBytes());
        call(s, multipart("/api/networth/holdings/import").file(junk), 422);
    }

    @org.springframework.beans.factory.annotation.Autowired
    com.expensetracker.networth.PriceService priceService;

    @Test
    void getLatestPriceReplacesATypedPriceWithTheOfficialOne() throws Exception {
        Session s = register();
        priceService.loadNavFile("""
                Scheme Code;ISIN Div Payout/ ISIN Growth;ISIN Div Reinvestment;Scheme Name;Plan;Option;Net Asset Value;Date

                Open Ended Schemes(Equity Scheme - Flexi Cap Fund)

                PPFAS Mutual Fund

                122639;INF879O01027;-;Parag Parikh Flexi Cap Fund;Direct Plan;Growth Option;94.2890;17-Nov-2026
                """);
        JsonNode fund = postJson(s, "/api/networth/holdings", Map.of("assetClass", "MUTUAL_FUND", "name", "PPFAS", "isin", "INF879O01027", "units", "100"), 201);
        String id = fund.get("id").asText();
        // typing a price by hand...
        postJson(s, "/api/networth/holdings/" + id + "/price", Map.of("price", "80"), 200);
        // ...then "Get latest price" brings back the official NAV
        JsonNode r = postJson(s, "/api/networth/holdings/" + id + "/latest-price", Map.of(), 200);
        assertEquals(0, r.get("price").decimalValue().compareTo(new java.math.BigDecimal("94.289")));
        assertEquals(0, r.get("previousPrice").decimalValue().compareTo(new java.math.BigDecimal("80")));
        assertEquals("2026-11-17", r.get("priceDate").asText());
        assertEquals("AMFI", r.get("holding").get("priceSource").asText());
        assertEquals(0, r.get("holding").get("value").decimalValue().compareTo(new java.math.BigDecimal("9428.90")));
        assertEquals("122639", r.get("holding").get("schemeCode").asText(), "linked to its AMFI scheme by ISIN");

        // a fund with no ISIN or scheme can't be looked up, and says how to fix it
        JsonNode loose = postJson(s, "/api/networth/holdings", Map.of("assetClass", "MUTUAL_FUND", "name", "Some fund", "units", "10", "currentPrice", "50"), 201);
        JsonNode err = postJson(s, "/api/networth/holdings/" + loose.get("id").asText() + "/latest-price", Map.of(), 422);
        assertTrue(err.get("error").get("message").asText().contains("isn't linked to an AMFI scheme"));

        // things valued by hand have no market price to fetch
        JsonNode ppf = postJson(s, "/api/networth/holdings", Map.of("assetClass", "PPF", "name", "PPF", "manualValue", "150000"), 201);
        postJson(s, "/api/networth/holdings/" + ppf.get("id").asText() + "/latest-price", Map.of(), 422);
    }
}
