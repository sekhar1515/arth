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
        assertEquals("FUND_NOT_LINKED", err.get("error").get("code").asText());

        // things valued by hand have no market price to fetch
        JsonNode ppf = postJson(s, "/api/networth/holdings", Map.of("assetClass", "PPF", "name", "PPF", "manualValue", "150000"), 201);
        postJson(s, "/api/networth/holdings/" + ppf.get("id").asText() + "/latest-price", Map.of(), 422);
    }

    @Test
    void fundsAddedByNameLinkThemselvesOnlyWhenUnambiguous() throws Exception {
        Session s = register();
        priceService.loadNavFile("""
                Scheme Code;ISIN Div Payout/ ISIN Growth;ISIN Div Reinvestment;Scheme Name;Plan;Option;Net Asset Value;Date

                Open Ended Schemes(Equity Scheme - Flexi Cap Fund)

                PPFAS Mutual Fund

                122639;INF879O01027;-;Parag Parikh Flexi Cap Fund;Direct Plan;Growth Option;94.2890;17-Nov-2026
                122640;-;INF879O01035;Parag Parikh Flexi Cap Fund;Direct Plan;IDCW Option;40.1000;17-Nov-2026
                122638;INF879O01019;-;Parag Parikh Flexi Cap Fund;Regular Plan;Growth Option;86.5000;17-Nov-2026
                """);
        // a full name says plan and option: linked automatically
        JsonNode exact = postJson(s, "/api/networth/holdings", Map.of("assetClass", "MUTUAL_FUND", "name", "parag parikh flexi cap direct growth",
                "units", "10", "currentPrice", "90"), 201);
        JsonNode r = postJson(s, "/api/networth/holdings/" + exact.get("id").asText() + "/latest-price", Map.of(), 200);
        assertEquals("122639", r.get("holding").get("schemeCode").asText());
        assertEquals(0, r.get("price").decimalValue().compareTo(new java.math.BigDecimal("94.289")));

        // a bare name fits three schemes: Arth asks instead of guessing
        JsonNode vague = postJson(s, "/api/networth/holdings", Map.of("assetClass", "MUTUAL_FUND", "name", "Parag Parikh Flexi Cap",
                "units", "10", "currentPrice", "90"), 201);
        String id = vague.get("id").asText();
        JsonNode err = postJson(s, "/api/networth/holdings/" + id + "/latest-price", Map.of(), 422);
        assertEquals("FUND_NOT_LINKED", err.get("error").get("code").asText());

        // picking the scheme links it and prices it from AMFI straight away
        JsonNode linked = putJson(s, "/api/networth/holdings/" + id, Map.of("schemeCode", "122638"), 200);
        assertEquals("122638", linked.get("schemeCode").asText());
        assertEquals("AMFI", linked.get("priceSource").asText());
        assertEquals(0, linked.get("currentPrice").decimalValue().compareTo(new java.math.BigDecimal("86.5")));
        postJson(s, "/api/networth/holdings/" + id + "/latest-price", Map.of(), 200);
    }

    @Test
    void growwStyleExportWithoutIsinsLinksFundsByName() throws Exception {
        Session s = register();
        priceService.loadNavFile("""
                Scheme Code;ISIN Div Payout/ ISIN Growth;ISIN Div Reinvestment;Scheme Name;Plan;Option;Net Asset Value;Date

                Open Ended Schemes(Equity Scheme - Small Cap Fund)

                Quant Mutual Fund

                120828;INF966L01689;-;Quant Small Cap Fund;Direct Plan;Growth Option;311.2140;17-Nov-2026
                152107;INF082J01499;-;QUANTUM SMALL CAP FUND;Direct Plan;Growth Option;13.8200;17-Nov-2026
                """);
        String csv = """
                Scheme Name,AMC,Category,Units,Invested Value,Current Value
                Quant Small Cap Fund Direct Plan Growth,Quant,Equity,791.547,200000,246262
                Some Unknown Fund,Other,Equity,10,1000,1200
                """;
        MockMultipartFile f = new MockMultipartFile("file", "groww.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));
        JsonNode r = call(s, multipart("/api/networth/holdings/import").file(f).param("platform", "Groww"), 200);
        assertEquals(2, r.get("created").asInt());
        assertEquals(1, r.get("linkedToAmfi").asInt(), "Quant links by name; it is not confused with Quantum");
        for (JsonNode h : getJson(s, "/api/networth/holdings")) {
            if (h.get("name").asText().startsWith("Quant")) {
                assertEquals("120828", h.get("schemeCode").asText());
                assertEquals("INF966L01689", h.get("isin").asText());
            } else {
                assertTrue(h.get("schemeCode").isNull(), "an unknown name stays unlinked rather than guessed");
            }
        }
    }

    @org.springframework.beans.factory.annotation.Autowired
    org.springframework.jdbc.core.JdbcTemplate plainJdbc;

    private String navFileDated(java.time.LocalDate d) {
        String date = d.format(java.time.format.DateTimeFormatter.ofPattern("dd-MMM-yyyy", java.util.Locale.ENGLISH));
        return """
                Scheme Code;ISIN Div Payout/ ISIN Growth;ISIN Div Reinvestment;Scheme Name;Plan;Option;Net Asset Value;Date

                Open Ended Schemes(Equity Scheme - Index Funds)

                HDFC Mutual Fund

                119063;INF179K01WM1;-;HDFC Nifty 50 Index Fund;Direct Plan;Growth Option;250.0000;%s
                """.formatted(date);
    }

    @Test
    void sipAddsUnitsAndRecordsTheBankDebitWhichMergesWithTheStatement() throws Exception {
        Session s = register();
        java.time.LocalDate today = java.time.LocalDate.now(com.expensetracker.common.TimeConfig.IST);
        priceService.loadNavFile(navFileDated(today));
        UUID bank = createAccount(s, "HDFC", "BANK", "4444", "100000");
        JsonNode fund = postJson(s, "/api/networth/holdings", Map.of("assetClass", "MUTUAL_FUND", "name", "HDFC Nifty 50", "schemeCode", "119063",
                "units", "100", "investedAmount", "20000"), 201);
        java.time.LocalDate due = today.minusDays(1).getDayOfMonth() > 28 ? today.minusDays(4) : today.minusDays(1);
        JsonNode plan = postJson(s, "/api/networth/plans", Map.of("holdingId", fund.get("id").asText(), "amount", "5000",
                "dayOfMonth", due.getDayOfMonth(), "accountId", bank.toString()), 201);
        assertEquals("SIP", plan.get("kind").asText());
        // pretend the plan was set up before this month's SIP date
        plainJdbc.update("UPDATE investment_plans SET last_run_date = ?, start_date = ? WHERE id = ?",
                due.minusDays(1), due.minusDays(1), UUID.fromString(plan.get("id").asText()));

        JsonNode list = getJson(s, "/api/networth/plans"); // runs what is due
        JsonNode inst = list.get(0).get("installments");
        assertEquals(1, inst.size());
        assertEquals(due.toString(), inst.get(0).get("dueDate").asText());
        assertEquals(0, inst.get(0).get("units").decimalValue().compareTo(new java.math.BigDecimal("20.000")), "5000 / 250 NAV");

        JsonNode h = getJson(s, "/api/networth/holdings").get(0);
        assertEquals(0, h.get("units").decimalValue().compareTo(new java.math.BigDecimal("120")));
        assertEquals(0, h.get("investedAmount").decimalValue().compareTo(new java.math.BigDecimal("25000")));
        // the bank balance fell by the SIP: 1,00,000 - 5,000
        JsonNode acc = getJson(s, "/api/accounts");
        for (JsonNode a : acc.isArray() ? acc : acc.get("content")) {
            if (a.get("id").asText().equals(bank.toString())) assertEquals(0, a.get("balance").decimalValue().compareTo(new java.math.BigDecimal("95000")));
        }
        // running again doesn't apply it twice
        getJson(s, "/api/networth/plans");
        assertEquals(0, getJson(s, "/api/networth/holdings").get(0).get("units").decimalValue().compareTo(new java.math.BigDecimal("120")));

        // the bank statement arrives a day later with the real line: it merges, no duplicate
        String csv = "Date,Narration,Withdrawal Amt.,Deposit Amt.\n" + due.plusDays(1) + ",ACH D- HDFC MF SIP 98765,5000.00,\n";
        JsonNode r = importCsv(s, bank, csv);
        assertEquals(1, r.get("merged").asInt());
        assertEquals(0, r.get("imported").asInt());
        JsonNode txns = getJson(s, "/api/transactions?accountId=" + bank + "&size=10").get("content");
        assertEquals(1, txns.size());
        assertTrue(txns.get(0).get("description").asText().contains("HDFC MF SIP"), "the bank's own description is kept");
    }

    @Test
    void pfContributionAddsYourAndEmployerPartsWithoutABankDebit() throws Exception {
        Session s = register();
        java.time.LocalDate today = java.time.LocalDate.now(com.expensetracker.common.TimeConfig.IST);
        JsonNode pf = postJson(s, "/api/networth/holdings", Map.of("assetClass", "EPF", "name", "EPF", "manualValue", "500000"), 201);
        java.time.LocalDate due = today.getDayOfMonth() > 28 ? today.minusDays(4) : today;
        JsonNode plan = postJson(s, "/api/networth/plans", Map.of("holdingId", pf.get("id").asText(), "amount", "1800", "employerAmount", "1800",
                "dayOfMonth", due.getDayOfMonth()), 201);
        assertEquals("CONTRIBUTION", plan.get("kind").asText());
        assertEquals(0, plan.get("total").decimalValue().compareTo(new java.math.BigDecimal("3600")));
        plainJdbc.update("UPDATE investment_plans SET last_run_date = ?, start_date = ? WHERE id = ?",
                due.minusDays(1), due.minusDays(1), UUID.fromString(plan.get("id").asText()));
        getJson(s, "/api/networth/plans");
        JsonNode h = getJson(s, "/api/networth/holdings").get(0);
        assertEquals(0, h.get("manualValue").decimalValue().compareTo(new java.math.BigDecimal("503600")));
        assertEquals(0, getJson(s, "/api/transactions?size=5").get("totalElements").asInt(), "PF comes out of salary: no bank debit");

        // a SIP needs a fund; a contribution needs something valued by hand
        postJson(s, "/api/networth/plans", Map.of("holdingId", pf.get("id").asText(), "kind", "SIP", "amount", "100"), 400);
    }

    @Test
    void anAlreadyImportedDebitIsLinkedNotDuplicated() throws Exception {
        Session s = register();
        java.time.LocalDate today = java.time.LocalDate.now(com.expensetracker.common.TimeConfig.IST);
        priceService.loadNavFile(navFileDated(today));
        UUID bank = createAccount(s, "ICICI", "BANK", "5555", "50000");
        java.time.LocalDate due = today.minusDays(1).getDayOfMonth() > 28 ? today.minusDays(4) : today.minusDays(1);
        importCsv(s, bank, "Date,Narration,Withdrawal Amt.,Deposit Amt.\n" + due + ",NACH DR HDFC MF,3000.00,\n");
        JsonNode fund = postJson(s, "/api/networth/holdings", Map.of("assetClass", "MUTUAL_FUND", "name", "HDFC Nifty 50", "schemeCode", "119063", "units", "10"), 201);
        JsonNode plan = postJson(s, "/api/networth/plans", Map.of("holdingId", fund.get("id").asText(), "amount", "3000",
                "dayOfMonth", due.getDayOfMonth(), "accountId", bank.toString()), 201);
        plainJdbc.update("UPDATE investment_plans SET last_run_date = ?, start_date = ? WHERE id = ?",
                due.minusDays(1), due.minusDays(1), UUID.fromString(plan.get("id").asText()));
        JsonNode inst = getJson(s, "/api/networth/plans").get(0).get("installments").get(0);
        assertFalse(inst.get("transactionId").isNull());
        assertEquals(1, getJson(s, "/api/transactions?accountId=" + bank + "&size=10").get("totalElements").asInt(), "linked to the statement line");
    }

    @Test
    void dayChangeComesFromThePreviousNavAndClose() throws Exception {
        Session s = register();
        java.time.LocalDate today = java.time.LocalDate.now(com.expensetracker.common.TimeConfig.IST);
        // two NAV loads on consecutive days: the second keeps the first as the previous NAV
        priceService.loadNavFile(navFileDated(today.minusDays(1)).replace("250.0000", "240.0000"));
        priceService.loadNavFile(navFileDated(today));
        postJson(s, "/api/networth/holdings", Map.of("assetClass", "MUTUAL_FUND", "name", "HDFC Nifty 50", "schemeCode", "119063", "units", "100"), 201);
        // a stock priced from the bhavcopy: close 1145.10, previous close 1141.50
        String csv = com.expensetracker.networth.BhavcopyParserTest.HEADER
                + "2026-11-16,2026-11-16,CM,NSE,STK,1594,INE009A01021,INFY,EQ,,,,,INFOSYS LIMITED,1,1,1,1145.10,1,1141.50,,1,,,1,1,1,F1,1,,,,,\n";
        call(s, multipart("/api/networth/prices/bhavcopy").file(new MockMultipartFile("file", "b.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8))), 200);
        postJson(s, "/api/networth/holdings", Map.of("assetClass", "STOCK", "name", "Infosys", "isin", "INE009A01021", "units", "10"), 201);

        JsonNode nw = getJson(s, "/api/networth");
        // fund: 100 × (250 − 240) = 1000; stock: 10 × (1145.10 − 1141.50) = 36.00
        assertEquals(0, nw.get("holdingsDayChange").decimalValue().compareTo(new java.math.BigDecimal("1036.00")));
        for (JsonNode g : nw.get("assetGroups")) {
            for (JsonNode i : g.get("items")) {
                if (i.get("name").asText().startsWith("HDFC")) {
                    assertEquals(0, i.get("dayChange").decimalValue().compareTo(new java.math.BigDecimal("1000.00")));
                    assertEquals(0, i.get("dayChangePct").decimalValue().compareTo(new java.math.BigDecimal("4.17")));
                    assertEquals(today.toString(), i.get("dayChangeOn").asText());
                }
                if (i.get("name").asText().equals("Infosys")) assertEquals(0, i.get("dayChange").decimalValue().compareTo(new java.math.BigDecimal("36.00")));
            }
        }
        // a price typed by hand isn't the official one, so no day change is claimed for it
        JsonNode typed = postJson(s, "/api/networth/holdings", Map.of("assetClass", "MUTUAL_FUND", "name", "Typed", "schemeCode", "119063", "folio", "F2", "units", "1", "currentPrice", "99"), 201);
        for (JsonNode g : getJson(s, "/api/networth").get("assetGroups")) {
            for (JsonNode i : g.get("items")) if (i.get("id").asText().equals(typed.get("id").asText())) assertTrue(i.get("dayChange").isNull());
        }
    }
}
