package com.expensetracker.assistant;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.Month;
import java.time.format.TextStyle;
import java.time.temporal.TemporalAdjusters;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a question into an intent and a date range, with plain rules (no external AI). Anything it does not
 * understand becomes HELP, so the assistant never guesses at an answer.
 */
public final class IntentParser {

    public enum Type {
        SPEND_TOTAL, INCOME_TOTAL, TOP_CATEGORIES, TOP_MERCHANTS, COMPARE_MONTHS, SAVINGS_RATE, NET_WORTH, BUDGETS,
        SUBSCRIPTIONS, UPCOMING_BILLS, CARD_DUES, LARGEST, NEEDS_REVIEW, INVESTED, CAN_AFFORD, HELP
    }

    public record Range(LocalDate from, LocalDate toExclusive, String label) {
    }

    /** subject: a category or merchant phrase from the question ("food", "swiggy"), or null. amount: for CAN_AFFORD. */
    public record Intent(Type type, Range range, String subject, java.math.BigDecimal amount) {
    }

    private static final Pattern SUBJECT = Pattern.compile("\\b(?:on|at|for|in|towards)\\s+([a-z][a-z0-9 &'.-]{1,40}?)(?=\\s+(?:this|last|in|during|since|over|for|today|yesterday)\\b|[?.!]|$)");
    private static final Pattern AMOUNT = Pattern.compile("(?:₹|rs\\.?|inr)?\\s*([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*(k|lakh|lakhs|l|cr|crore)?\\b");
    private static final Pattern LAST_N = Pattern.compile("\\b(?:last|past)\\s+(\\d{1,2})\\s+(day|days|week|weeks|month|months)\\b");

    private IntentParser() {
    }

    public static Intent parse(String question, LocalDate today) {
        String q = " " + (question == null ? "" : question.toLowerCase(Locale.ROOT).replace('’', '\'').trim()) + " ";
        Range range = range(q, today);
        Type type = type(q);
        String subject = null;
        java.math.BigDecimal amount = null;
        if (type == Type.SPEND_TOTAL || type == Type.INCOME_TOTAL || type == Type.LARGEST) subject = subject(q);
        if (type == Type.CAN_AFFORD) amount = amount(q);
        return new Intent(type, range, subject, amount);
    }

    static Type type(String q) {
        if (has(q, "afford")) return Type.CAN_AFFORD;
        if (has(q, "net worth", "networth", "worth", "assets", "portfolio")) return Type.NET_WORTH;
        if (has(q, "invest", "sip")) return Type.INVESTED;
        if (has(q, "budget")) return Type.BUDGETS;
        if (has(q, "subscription", "subscriptions", "subscribed")) return Type.SUBSCRIPTIONS;
        if (has(q, "credit card", "card due", "card bill", "statement", "utilisation", "utilization")) return Type.CARD_DUES;
        if (has(q, "upcoming", "due soon", "bills", "bill due", "recurring")) return Type.UPCOMING_BILLS;
        if (has(q, "review", "uncategorised", "uncategorized", "pending")) return Type.NEEDS_REVIEW;
        if (has(q, "saving rate", "savings rate", "save", "saved", "saving")) return Type.SAVINGS_RATE;
        if (has(q, "compare", "compared", "vs", "versus", "more than last", "less than last")) return Type.COMPARE_MONTHS;
        if (has(q, "biggest", "largest", "highest", "most expensive")) {
            if (has(q, "category", "categories")) return Type.TOP_CATEGORIES;
            if (has(q, "merchant", "merchants", "shop", "where")) return Type.TOP_MERCHANTS;
            return Type.LARGEST;
        }
        if (has(q, "top categor", "categories", "category", "breakdown", "where did my money", "where does my money")) return Type.TOP_CATEGORIES;
        if (has(q, "merchant", "merchants", "shops", "stores")) return Type.TOP_MERCHANTS;
        if (has(q, "earn", "earned", "income", "salary", "received")) return Type.INCOME_TOTAL;
        if (has(q, "spend", "spent", "spending", "expense", "expenses", "pay", "paid", "cost")) return Type.SPEND_TOTAL;
        return Type.HELP;
    }

    static Range range(String q, LocalDate today) {
        LocalDate monthStart = today.withDayOfMonth(1);
        Matcher n = LAST_N.matcher(q);
        if (n.find()) {
            int k = Integer.parseInt(n.group(1));
            String unit = n.group(2);
            LocalDate from = unit.startsWith("day") ? today.minusDays(k - 1L) : unit.startsWith("week") ? today.minusWeeks(k).plusDays(1) : today.minusMonths(k).plusDays(1);
            return new Range(from, today.plusDays(1), "the last " + k + " " + unit);
        }
        if (has(q, "today")) return new Range(today, today.plusDays(1), "today");
        if (has(q, "yesterday")) return new Range(today.minusDays(1), today, "yesterday");
        if (has(q, "this week")) {
            LocalDate s = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            return new Range(s, today.plusDays(1), "this week");
        }
        if (has(q, "last week")) {
            LocalDate s = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(1);
            return new Range(s, s.plusWeeks(1), "last week");
        }
        if (has(q, "last month", "previous month")) {
            LocalDate s = monthStart.minusMonths(1);
            return new Range(s, monthStart, monthName(s));
        }
        if (has(q, "this year", "this financial year", "this fy")) {
            int fy = today.getMonthValue() >= 4 ? today.getYear() : today.getYear() - 1;
            LocalDate s = LocalDate.of(fy, 4, 1);
            return new Range(s, today.plusDays(1), "this financial year (since 1 April " + fy + ")");
        }
        if (has(q, "last year", "last financial year", "last fy")) {
            int fy = (today.getMonthValue() >= 4 ? today.getYear() : today.getYear() - 1) - 1;
            LocalDate s = LocalDate.of(fy, 4, 1);
            return new Range(s, s.plusYears(1), "FY " + fy + "-" + String.format("%02d", (fy + 1) % 100));
        }
        for (Month m : Month.values()) {
            String full = m.getDisplayName(TextStyle.FULL, Locale.ENGLISH).toLowerCase(Locale.ROOT);
            String shortName = m.getDisplayName(TextStyle.SHORT, Locale.ENGLISH).toLowerCase(Locale.ROOT);
            if (word(q, full) || (m != Month.MAY && word(q, shortName))) {
                Matcher y = Pattern.compile("\\b(20\\d{2})\\b").matcher(q);
                int year = y.find() ? Integer.parseInt(y.group(1)) : today.getYear();
                LocalDate s = LocalDate.of(year, m, 1);
                if (s.isAfter(today) && !y.reset().find()) s = s.minusYears(1);
                return new Range(s, s.plusMonths(1), monthName(s));
            }
        }
        return new Range(monthStart, today.plusDays(1), "this month");
    }

    static String subject(String q) {
        Matcher m = SUBJECT.matcher(q);
        while (m.find()) {
            String s = m.group(1).trim();
            if (s.matches("(this|last|the|my|a|an)( .*)?") && !s.startsWith("the ")) continue;
            s = s.replaceFirst("^(the|my)\\s+", "").trim();
            if (s.isEmpty() || isDateWord(s)) continue;
            return s;
        }
        return null;
    }

    static java.math.BigDecimal amount(String q) {
        Matcher m = AMOUNT.matcher(q);
        java.math.BigDecimal best = null;
        while (m.find()) {
            String digits = m.group(1).replace(",", "");
            if (digits.isEmpty()) continue;
            java.math.BigDecimal v = new java.math.BigDecimal(digits);
            String unit = m.group(2);
            if (unit != null) {
                v = switch (unit) {
                    case "k" -> v.multiply(java.math.BigDecimal.valueOf(1_000));
                    case "l", "lakh", "lakhs" -> v.multiply(java.math.BigDecimal.valueOf(100_000));
                    default -> v.multiply(java.math.BigDecimal.valueOf(10_000_000));
                };
            }
            if (v.compareTo(java.math.BigDecimal.valueOf(1900)) >= 0 && v.compareTo(java.math.BigDecimal.valueOf(2100)) <= 0 && unit == null && !digits.contains(".")
                    && !q.contains("₹") && !q.contains("rs")) {
                continue; // looks like a year
            }
            if (best == null || v.compareTo(best) > 0) best = v;
        }
        return best;
    }

    private static boolean isDateWord(String s) {
        for (Month m : Month.values()) if (s.startsWith(m.getDisplayName(TextStyle.FULL, Locale.ENGLISH).toLowerCase(Locale.ROOT))) return true;
        return s.matches("(today|yesterday|this .*|last .*|\\d{4})");
    }

    private static boolean word(String q, String w) {
        return Pattern.compile("(?<![a-z])" + Pattern.quote(w) + "(?![a-z])").matcher(q).find();
    }

    private static boolean has(String q, String... words) {
        for (String w : words) {
            if (Pattern.compile("(?<![a-z])" + Pattern.quote(w)).matcher(q).find()) return true;
        }
        return false;
    }

    private static String monthName(LocalDate d) {
        return d.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " " + d.getYear();
    }
}
