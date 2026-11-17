package com.expensetracker.networth;

import com.expensetracker.integration.parsing.SimpleCsv;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Parses the exchanges' daily cash-market "bhavcopy" in the common UDiFF layout that NSE and BSE both publish
 * (TradDt, FinInstrmTp, ISIN, TckrSymb, SctySrs, FinInstrmNm, ClsPric, PrvsClsgPric …). Columns are found by name,
 * so extra or reordered columns don't matter. Only instruments of type STK (equities, ETFs, SGBs, listed bonds) are kept.
 */
public final class BhavcopyParser {

    public record Quote(String isin, String symbol, String series, String name, BigDecimal close, BigDecimal prevClose,
                        LocalDate date, String exchange) {
    }

    private BhavcopyParser() {
    }

    public static List<Quote> parse(String csv) {
        List<List<String>> table = SimpleCsv.parse(csv);
        if (table.isEmpty()) return List.of();
        Map<String, Integer> col = new HashMap<>();
        List<String> header = table.get(0);
        for (int i = 0; i < header.size(); i++) col.put(header.get(i).trim().toLowerCase(Locale.ROOT).replace("﻿", ""), i);
        Integer cIsin = col.get("isin"), cSym = col.get("tckrsymb"), cClose = col.get("clspric"), cDate = col.get("traddt");
        if (cIsin == null || cSym == null || cClose == null || cDate == null) {
            throw new IllegalArgumentException("Not a bhavcopy file: expected ISIN, TckrSymb, ClsPric and TradDt columns");
        }
        Integer cType = col.get("fininstrmtp"), cSeries = col.get("sctysrs"), cName = col.get("fininstrmnm"),
                cPrev = col.get("prvsclsgpric"), cSrc = col.get("src");
        List<Quote> out = new ArrayList<>();
        for (int i = 1; i < table.size(); i++) {
            List<String> r = table.get(i);
            String type = cell(r, cType);
            if (type != null && !type.equalsIgnoreCase("STK")) continue;
            String isin = cell(r, cIsin);
            String symbol = cell(r, cSym);
            BigDecimal close = decimal(cell(r, cClose));
            LocalDate date = date(cell(r, cDate));
            if (isin == null || isin.length() != 12 || symbol == null || close == null || close.signum() < 0 || date == null) continue;
            String src = cell(r, cSrc);
            out.add(new Quote(isin.toUpperCase(Locale.ROOT), symbol.toUpperCase(Locale.ROOT), trim(cell(r, cSeries), 4),
                    trim(cell(r, cName), 200), close, decimal(cell(r, cPrev)), date,
                    src != null && src.equalsIgnoreCase("BSE") ? "BSE" : "NSE"));
        }
        return out;
    }

    private static String cell(List<String> r, Integer idx) {
        if (idx == null || idx >= r.size()) return null;
        String v = r.get(idx).trim();
        return v.isEmpty() ? null : v;
    }

    private static BigDecimal decimal(String s) {
        if (s == null) return null;
        try {
            return new BigDecimal(s.replace(",", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static LocalDate date(String s) {
        if (s == null) return null;
        try {
            return LocalDate.parse(s.length() > 10 ? s.substring(0, 10) : s);
        } catch (Exception e) {
            return null;
        }
    }

    private static String trim(String s, int max) {
        return s == null ? null : (s.length() > max ? s.substring(0, max) : s);
    }
}
