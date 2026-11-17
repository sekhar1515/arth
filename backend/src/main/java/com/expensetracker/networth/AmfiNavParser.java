package com.expensetracker.networth;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Parses AMFI's public daily NAV file (NAVAll.txt). Data lines look like
 * {@code 119551;INF209KA12Z1;INF209KA13Z9;Aditya Birla Sun Life Banking & PSU Debt Fund - Direct - IDCW;105.1234;14-Nov-2026}
 * and are interleaved with category headings ("Open Ended Schemes(Debt Scheme - Banking and PSU Fund)") and AMC names.
 */
public final class AmfiNavParser {

    public record NavRecord(String schemeCode, String isinGrowth, String isinReinvest, String name, BigDecimal nav, LocalDate date,
                            String category, String amc) {
    }

    private static final DateTimeFormatter DATE = new DateTimeFormatterBuilder().parseCaseInsensitive()
            .appendPattern("dd-MMM-yyyy").toFormatter(Locale.ENGLISH);

    private AmfiNavParser() {
    }

    public static List<NavRecord> parse(String text) {
        List<NavRecord> out = new ArrayList<>();
        if (text == null) return out;
        String category = null;
        String amc = null;
        for (String raw : text.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("Scheme Code")) continue;
            String[] p = line.split(";", -1);
            if (p.length >= 6 && p[0].trim().matches("[0-9]{3,10}")) {
                // NAV and date are always the last two columns. Since 2026 AMFI adds Plan and Option columns
                // between the name and the NAV; fold them into the name ("… - Direct Plan - Growth Option").
                int n = p.length;
                BigDecimal nav = parseNav(p[n - 2]);
                LocalDate date = parseDate(p[n - 1]);
                StringBuilder name = new StringBuilder(p[3].trim());
                for (int i = 4; i < n - 2; i++) {
                    String extra = p[i].trim();
                    if (!extra.isEmpty() && !extra.equals("-") && !name.toString().toLowerCase(Locale.ROOT).contains(extra.toLowerCase(Locale.ROOT))) {
                        name.append(" - ").append(extra);
                    }
                }
                out.add(new NavRecord(p[0].trim(), isin(p[1]), isin(p[2]), name.toString(), nav, date, category, amc));
            } else if (p.length == 1) {
                if (line.contains("Schemes(") || line.contains("Scheme(") || line.startsWith("Open Ended") || line.startsWith("Close Ended")
                        || line.startsWith("Interval Fund")) {
                    int open = line.indexOf('(');
                    int close = line.lastIndexOf(')');
                    category = open >= 0 && close > open ? line.substring(open + 1, close).trim() : line;
                } else {
                    amc = line;
                }
            }
        }
        return out;
    }

    private static String isin(String s) {
        String t = s == null ? "" : s.trim();
        return t.matches("[A-Z]{2}[A-Z0-9]{9}[0-9]") ? t : null;
    }

    private static BigDecimal parseNav(String s) {
        try {
            String t = s.trim().replace(",", "");
            if (t.isEmpty() || t.equalsIgnoreCase("N.A.") || t.equals("-")) return null;
            BigDecimal v = new BigDecimal(t);
            return v.signum() > 0 ? v : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static LocalDate parseDate(String s) {
        try {
            return LocalDate.parse(s.trim(), DATE);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
