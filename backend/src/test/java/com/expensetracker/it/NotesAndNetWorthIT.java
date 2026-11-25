package com.expensetracker.it;

import com.expensetracker.networth.PriceService;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

class NotesAndNetWorthIT extends IntegrationTestBase {

    @Autowired
    PriceService prices;

    private JsonNode uploadStatement(Session s, UUID account, String csv) throws Exception {
        MockMultipartFile f = new MockMultipartFile("file", "s.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));
        return call(s, multipart("/api/imports/csv").file(f).param("accountId", account.toString()), 200);
    }

    private JsonNode txnByAmount(Session s, String amount) throws Exception {
        for (JsonNode t : getJson(s, "/api/transactions?size=200").get("content")) {
            if (t.get("amount").decimalValue().compareTo(new BigDecimal(amount)) == 0) return t;
        }
        throw new AssertionError("no transaction of " + amount);
    }

    @Test
    void notesDriveSuggestionsAndCategoryColumnFilesDirectly() throws Exception {
        Session s = register();
        UUID acc = createAccount(s, "HDFC", "BANK", "4821", "50000.00");
        JsonNode r = uploadStatement(s, acc, """
                Date,Narration,Withdrawal Amt.,Deposit Amt.,Note,Category
                03/11/2026,UPI-RAMESH K-RAMESHK@OKSBI-SBIN0001234-431200000101-PAY,650.00,,sabzi,
                04/11/2026,UPI-RAMESH K-RAMESHK@OKSBI-SBIN0001234-431200000102-PAY,15000.00,,rent nov,
                05/11/2026,UPI-AMIT-AMIT@YBL-YESB0000001-431200000103-PAY,1200.00,,team lunch,Food & Dining > Restaurants
                06/11/2026,UPI/DR/431200000104/SURESH/SBIN/suresh@oksbi/petrol,800.00,,,
                07/11/2026,UPI-RAHUL-RAHUL@YBL-YESB0000001-431200000105-PAY,2000.00,,lent to rahul,
                """);
        assertEquals(5, r.get("imported").asInt());
        assertEquals(1, r.get("categorisedFromFile").asInt());
        assertEquals(5, r.get("withNotes").asInt(), "four Note cells + one UPI remark");

        JsonNode sabzi = txnByAmount(s, "650.00");
        assertEquals("Groceries", sabzi.get("suggestedCategory").get("path").asText(), "same payee, note decides");
        assertEquals("sabzi", sabzi.get("notes").asText());
        assertEquals("Rent", txnByAmount(s, "15000.00").get("suggestedCategory").get("path").asText());
        JsonNode lunch = txnByAmount(s, "1200.00");
        assertEquals("CONFIRMED", lunch.get("categorisationStatus").asText());
        assertEquals("Food & Dining > Restaurants", lunch.get("category").get("path").asText());
        JsonNode petrol = txnByAmount(s, "800.00");
        assertEquals("petrol", petrol.get("notes").asText(), "remark read from the bank narration");
        assertEquals("Fuel", petrol.get("suggestedCategory").get("path").asText());
        JsonNode lent = txnByAmount(s, "2000.00");
        assertTrue(lent.get("suggestedCategory").isNull());
        assertTrue(lent.get("suggestionReason").asText().contains("lent"));
    }

    @Test
    void confirmingANoteTeachesArthAndNoteRulesBeatMerchantRules() throws Exception {
        Session s = register();
        UUID acc = createAccount(s, "HDFC", "BANK", "4821", "0");
        var body = new HashMap<String, Object>();
        body.put("accountId", acc);
        body.put("direction", "DEBIT");
        body.put("amount", "300.00");
        body.put("date", "2026-11-03");
        body.put("merchant", "Ramesh K");
        body.put("notes", "maali");
        UUID first = UUID.fromString(postJson(s, "/api/transactions", body, 201).get("id").asText());
        assertTrue(txn(s, first).get("suggestedCategory").isNull(), "unknown word: no guess");
        UUID household = categoryId(s, "Household Help");
        postJson(s, "/api/transactions/" + first + "/categorise", Map.of("categoryId", household, "remember", "NOTE"), 200);

        body.put("amount", "320.00");
        body.put("date", "2026-11-10");
        body.put("notes", "Maali!");
        UUID second = UUID.fromString(postJson(s, "/api/transactions", body, 201).get("id").asText());
        JsonNode t = txn(s, second);
        assertEquals("Household Help", t.get("suggestedCategory").get("path").asText());
        assertEquals(0.98, t.get("suggestionConfidence").asDouble(), 0.001);

        // a merchant rule for Ramesh exists now too, but a different note wins
        postJson(s, "/api/transactions/" + second + "/categorise", Map.of("categoryId", household, "remember", "MERCHANT"), 200);
        body.put("amount", "640.00");
        body.put("date", "2026-11-12");
        body.put("notes", "sabzi");
        UUID third = UUID.fromString(postJson(s, "/api/transactions", body, 201).get("id").asText());
        assertEquals("Groceries", txn(s, third).get("suggestedCategory").get("path").asText());
    }

    @Test
    void reimportingWithNotesAnnotatesExistingTransactions() throws Exception {
        Session s = register();
        UUID acc = createAccount(s, "HDFC", "BANK", "4821", "0");
        String bare = """
                Date,Narration,Withdrawal Amt.,Deposit Amt.,Closing Balance
                03/11/2026,UPI-RAMESH K-RAMESHK@OKSBI-SBIN0001234-431200000101-PAY,650.00,,1000.00
                """;
        uploadStatement(s, acc, bare);
        assertTrue(txnByAmount(s, "650.00").get("suggestedCategory").isNull());
        JsonNode r = uploadStatement(s, acc, """
                Date,Narration,Withdrawal Amt.,Deposit Amt.,Closing Balance,Note
                03/11/2026,UPI-RAMESH K-RAMESHK@OKSBI-SBIN0001234-431200000101-PAY,650.00,,1000.00,sabzi
                """);
        assertEquals(1, r.get("updatedWithNotes").asInt());
        assertEquals(1, getJson(s, "/api/transactions").get("totalElements").asLong());
        assertEquals("Groceries", txnByAmount(s, "650.00").get("suggestedCategory").get("path").asText());
    }

    @Test
    void investmentsCountAsSavedNotSpent() throws Exception {
        Session s = register();
        UUID acc = createAccount(s, "HDFC", "BANK", "4821", "0");
        UUID salary = manual(s, acc, "CREDIT", "100000.00", "2026-10-01", "ACME");
        postJson(s, "/api/transactions/" + salary + "/categorise", Map.of("categoryId", categoryId(s, "Salary")), 200);
        UUID sip = manual(s, acc, "DEBIT", "10000.00", "2026-10-05", "BSE STAR MF");
        postJson(s, "/api/transactions/" + sip + "/categorise", Map.of("categoryId", categoryId(s, "Investments > SIP & Mutual Funds")), 200);
        UUID food = manual(s, acc, "DEBIT", "5000.00", "2026-10-06", "SWIGGY");
        postJson(s, "/api/transactions/" + food + "/categorise", Map.of("categoryId", categoryId(s, "Food & Dining")), 200);
        JsonNode sum = summary(s, "2026-10-01", "2026-10-31");
        assertEquals(0, sum.get("expenses").decimalValue().compareTo(new BigDecimal("5000.00")));
        assertEquals(0, sum.get("invested").decimalValue().compareTo(new BigDecimal("10000.00")));
        assertEquals(0, sum.get("savings").decimalValue().compareTo(new BigDecimal("95000.00")));
        assertEquals(1, sum.get("expenseByCategory").size());
        assertEquals(1, sum.get("investedByCategory").size());
    }

    @Test
    void netWorthCombinesAccountsHoldingsAndLoans() throws Exception {
        Session s = register();
        createAccount(s, "HDFC", "BANK", "4821", "200000.00");
        UUID card = createAccount(s, "Card", "CREDIT_CARD", "9012", "0");
        manual(s, card, "DEBIT", "15000.00", "2026-11-02", "AMAZON");

        prices.loadNavFile("""
                Open Ended Schemes(Equity Scheme - Flexi Cap Fund)
                Parag Parikh Mutual Fund
                122639;INF879O01027;-;Parag Parikh Flexi Cap Fund - Direct Plan - Growth;90.0000;14-Nov-2026
                """);
        var mf = new HashMap<String, Object>();
        mf.put("assetClass", "MUTUAL_FUND");
        mf.put("name", "Parag Parikh Flexi Cap");
        mf.put("schemeCode", "122639");
        mf.put("units", "1000");
        mf.put("investedAmount", "75000.00");
        JsonNode fund = postJson(s, "/api/networth/holdings", mf, 201);
        assertEquals(0, fund.get("value").decimalValue().compareTo(new BigDecimal("90000.00")), "units × AMFI NAV");
        assertEquals("AMFI", fund.get("priceSource").asText());
        assertEquals("INF879O01027", fund.get("isin").asText());

        var fd = new HashMap<String, Object>();
        fd.put("assetClass", "FIXED_DEPOSIT");
        fd.put("name", "SBI FD");
        fd.put("investedAmount", "100000.00");
        fd.put("interestRate", "7.0");
        fd.put("compoundingPerYear", 4);
        fd.put("startDate", java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")).toString()); // starts today: value = principal
        postJson(s, "/api/networth/holdings", fd, 201);
        var flat = new HashMap<String, Object>();
        flat.put("assetClass", "REAL_ESTATE");
        flat.put("name", "Flat in Pune");
        flat.put("manualValue", "4500000.00");
        postJson(s, "/api/networth/holdings", flat, 201);
        postJson(s, "/api/networth/liabilities", Map.of("type", "HOME_LOAN", "name", "SBI home loan", "outstanding", "2500000.00"), 201);

        JsonNode nw = getJson(s, "/api/networth");
        // assets: 200000 bank + 90000 MF + ~100000 FD + 4500000 flat ; liabilities: 15000 card + 2500000 loan
        BigDecimal assets = nw.get("assets").decimalValue();
        assertTrue(assets.compareTo(new BigDecimal("4890000")) >= 0 && assets.compareTo(new BigDecimal("4891000")) < 0, "assets " + assets);
        assertEquals(0, nw.get("liabilities").decimalValue().compareTo(new BigDecimal("2515000.00")));
        assertEquals(0, nw.get("netWorth").decimalValue().compareTo(assets.subtract(new BigDecimal("2515000.00"))));
        assertTrue(nw.get("allocation").size() >= 3);
        assertEquals(1, getJson(s, "/api/networth/history").size(), "today's snapshot");

        // re-pricing from a newer NAV file moves the value
        prices.loadNavFile("122639;INF879O01027;-;Parag Parikh Flexi Cap Fund - Direct Plan - Growth;95.0000;15-Nov-2026");
        JsonNode updated = getJson(s, "/api/networth/holdings");
        boolean found = false;
        for (JsonNode h : updated) {
            if (h.get("assetClass").asText().equals("MUTUAL_FUND")) {
                assertEquals(0, h.get("value").decimalValue().compareTo(new BigDecimal("95000.00")));
                found = true;
            }
        }
        assertTrue(found);
        assertTrue(getJson(s, "/api/dashboard/summary").get("accounts").get("netWorth").decimalValue().compareTo(new BigDecimal("2000000")) > 0);
    }

    @Test
    void brokerHoldingsImportCreatesThenUpdates() throws Exception {
        Session s = register();
        String kite = """
                Instrument,Qty.,Avg. cost,LTP,Cur. val,P&L,Net chg.,Day chg.
                INFY,10,1450.50,1612.30,16123.00,1618.00,11.15,0.52
                NIFTYBEES,100,250.00,270.00,27000.00,2000.00,8.0,0.1
                """;
        MockMultipartFile f = new MockMultipartFile("file", "holdings.csv", "text/csv", kite.getBytes(StandardCharsets.UTF_8));
        JsonNode r = call(s, multipart("/api/networth/holdings/import").file(f).param("platform", "Zerodha"), 200);
        assertEquals(2, r.get("created").asInt());
        String later = kite.replace("INFY,10,1450.50,1612.30,16123.00", "INFY,15,1500.00,1700.00,25500.00");
        MockMultipartFile f2 = new MockMultipartFile("file", "holdings.csv", "text/csv", later.getBytes(StandardCharsets.UTF_8));
        JsonNode r2 = call(s, multipart("/api/networth/holdings/import").file(f2).param("platform", "Zerodha"), 200);
        assertEquals(0, r2.get("created").asInt());
        assertEquals(2, r2.get("updated").asInt());
        JsonNode list = getJson(s, "/api/networth/holdings");
        assertEquals(2, list.size());
        for (JsonNode h : list) {
            if (h.get("name").asText().equals("NIFTYBEES")) assertEquals("ETF", h.get("assetClass").asText());
            if (h.get("name").asText().equals("INFY")) assertEquals(0, h.get("value").decimalValue().compareTo(new BigDecimal("25500.00")));
        }
    }

    @Test
    void parentsCanHoldTheSameFundAndGetTheirOwnSip() throws Exception {
        Session s = register();
        prices.loadNavFile("""
                Open Ended Schemes(Equity Scheme - Flexi Cap Fund)
                Parag Parikh Mutual Fund
                122639;INF879O01027;-;Parag Parikh Flexi Cap Fund - Direct Plan - Growth;90.0000;14-Nov-2026
                """); // so imported funds link to AMFI (a SIP needs that)
        String csv = """
                Scheme Name,ISIN,Units,Invested Value,Current NAV,Current Value
                Parag Parikh Flexi Cap Fund - Direct Plan - Growth,INF879O01027,100,8000,90,9000
                """;
        MockMultipartFile mine = new MockMultipartFile("file", "mf.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));
        assertEquals(1, call(s, multipart("/api/networth/holdings/import").file(mine).param("platform", "Groww"), 200).get("created").asInt());

        // the same fund, same platform, no folio, for parents: a separate holding, yours untouched
        String theirs = csv.replace(",100,8000,90,9000", ",50,4000,90,4500");
        MockMultipartFile f = new MockMultipartFile("file", "mf.csv", "text/csv", theirs.getBytes(StandardCharsets.UTF_8));
        JsonNode r = call(s, multipart("/api/networth/holdings/import").file(f).param("platform", "Groww").param("owner", "PARENTS"), 200);
        assertEquals(1, r.get("created").asInt());
        assertEquals(0, r.get("updated").asInt());
        JsonNode list = getJson(s, "/api/networth/holdings");
        assertEquals(2, list.size());
        String parentsFund = null;
        for (JsonNode h : list) {
            if (h.get("owner").asText().equals("PARENTS")) {
                parentsFund = h.get("id").asText();
                assertEquals(0, h.get("units").decimalValue().compareTo(new BigDecimal("50")));
            } else {
                assertEquals(0, h.get("units").decimalValue().compareTo(new BigDecimal("100")));
            }
        }
        assertTrue(getJson(s, "/api/networth/portfolio?owner=PARENTS").get("holdingsValue").decimalValue().signum() > 0);
        assertEquals(1, getJson(s, "/api/networth/portfolio?owner=PARENTS").get("holdingsCount").asInt());
        // opening the tab saved today's point for the parents' portfolio, separate from yours
        JsonNode hist = getJson(s, "/api/networth/portfolio/history?owner=PARENTS");
        assertEquals(1, hist.size());
        assertTrue(hist.get(0).get("value").decimalValue().signum() > 0);
        assertEquals(0, getJson(s, "/api/networth/portfolio/history?owner=SELF").size(), "your tab not opened yet");

        // adding it by hand for parents works too; a true duplicate gets a clear message
        Map<String, Object> manual = new HashMap<>(Map.of("assetClass", "MUTUAL_FUND", "name", "PPFAS Flexi", "isin", "INF879O01027",
                "units", "10", "currentPrice", "90", "owner", "PARENTS", "folio", "F-2"));
        postJson(s, "/api/networth/holdings", manual, 201);
        JsonNode dup = postJson(s, "/api/networth/holdings", manual, 409);
        assertTrue(dup.get("error").get("message").asText().contains("parents"));

        // a SIP for the parents' fund, not paid from your bank
        JsonNode plan = postJson(s, "/api/networth/plans", Map.of("holdingId", parentsFund, "amount", "2000", "dayOfMonth", 10), 201);
        assertEquals("SIP", plan.get("kind").asText());
        assertTrue(plan.get("accountId").isNull());
    }

    @Test
    void holdingValidationAndIsolation() throws Exception {
        Session a = register();
        Session b = register();
        postJson(a, "/api/networth/holdings", Map.of("assetClass", "STOCK", "name", "INFY"), 400); // no units
        JsonNode h = postJson(a, "/api/networth/holdings", Map.of("assetClass", "GOLD", "name", "Gold coins", "units", "20", "currentPrice", "7500"), 201);
        assertEquals(0, h.get("value").decimalValue().compareTo(new BigDecimal("150000.00")));
        call(b, org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/networth/holdings/" + h.get("id").asText()), 404);
        assertEquals(0, getJson(b, "/api/networth/holdings").size());
    }
}
