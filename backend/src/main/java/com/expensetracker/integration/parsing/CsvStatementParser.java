package com.expensetracker.integration.parsing;

import com.expensetracker.transaction.Direction;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Parses bank statement CSV exports. Detects the header row automatically (statements often have a preamble)
 * and understands common Indian bank column names (HDFC, ICICI, SBI, Axis, Kotak style), either as separate
 * withdrawal/deposit columns or as an amount column plus a Dr/Cr column.
 */
public final class CsvStatementParser {

    /**
     * @param note     what the money was for, from a Note/Purpose column you added (preferred over the bank's text)
     * @param category a category you typed in a Category column ("Groceries" or "Food & Dining > Cafes")
     */
    public record StatementRow(int rowNumber, LocalDate date, String description, Direction direction,
                               BigDecimal amount, String reference, BigDecimal balance, String note, String category) {
    }

    public record RowError(int rowNumber, String message) {
    }

    public record Result(List<StatementRow> rows, List<RowError> errors, String detectedLayout) {
    }

    public static class UnrecognisedFormatException extends RuntimeException {
        public UnrecognisedFormatException(String message) {
            super(message);
        }
    }

    private static final String[] DATE = {"txndate", "transactiondate", "trandate", "date", "postingdate", "valuedate", "valuedt"};
    private static final String[] DESC = {"narration", "description", "transactionremarks", "particulars", "transactiondetails", "details", "remarks"};
    private static final String[] DEBIT = {"withdrawalamt", "withdrawalamount", "withdrawalamountinr", "withdrawals", "withdrawal", "debitamount", "debit", "dr", "debitamt"};
    private static final String[] CREDIT = {"depositamt", "depositamount", "depositamountinr", "deposits", "deposit", "creditamount", "credit", "cr", "creditamt"};
    private static final String[] AMOUNT = {"amount", "transactionamount", "amountinr", "txnamount"};
    private static final String[] TYPE = {"drcr", "crdr", "type", "debitcredit", "transactiontype", "txntype"};
    private static final String[] REF = {"chqrefno", "refnochequeno", "chequerefno", "referenceno", "refno", "reference", "chqrefnumber", "utr", "utrno", "chequeno", "chqno", "transactionid"};
    private static final String[] NOTE = {"note", "notes", "mynote", "mynotes", "purpose", "whatfor", "forwhat", "comment", "comments",
            "memo", "label", "usernote", "reason", "spenton", "paidfor"};
    private static final String[] CATEGORY = {"category", "mycategory", "categoryname", "head", "expensehead", "expensetype"};
    private static final String[] BAL = {"closingbalance", "balance", "balanceinr", "runningbalance", "availablebalance"};

    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            f("d/M/uuuu"), f("d/M/uu"), f("d-M-uuuu"), f("d-M-uu"), f("uuuu-M-d"), f("d.M.uuuu"),
            f("d MMM uuuu"), f("d-MMM-uuuu"), f("d-MMM-uu"), f("d MMM uu"), f("d/MMM/uuuu"), f("MMM d, uuuu"));

    private CsvStatementParser() {
    }

    private static DateTimeFormatter f(String p) {
        return new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(p).toFormatter(Locale.ENGLISH)
                .withResolverStyle(java.time.format.ResolverStyle.STRICT);
    }

    public static Result parse(String content) {
        List<List<String>> table = SimpleCsv.parse(content);
        int headerIdx = -1;
        int[] cols = null;
        for (int i = 0; i < Math.min(table.size(), 40); i++) {
            int[] candidate = mapColumns(table.get(i));
            if (candidate != null) {
                headerIdx = i;
                cols = candidate;
                break;
            }
        }
        if (cols == null) {
            throw new UnrecognisedFormatException(
                    "Could not find a header row with date, description and amount columns. "
                            + "Expected columns like 'Date', 'Narration', 'Withdrawal Amt.', 'Deposit Amt.'.");
        }
        int cDate = cols[0], cDesc = cols[1], cDebit = cols[2], cCredit = cols[3], cAmount = cols[4], cType = cols[5], cRef = cols[6], cBal = cols[7];
        int cNote = cols[8], cCat = cols[9];
        String layout = cDebit >= 0 ? "separate withdrawal/deposit columns" : "amount + Dr/Cr column";

        List<StatementRow> rows = new ArrayList<>();
        List<RowError> errors = new ArrayList<>();
        for (int i = headerIdx + 1; i < table.size(); i++) {
            List<String> r = table.get(i);
            int rowNumber = i + 1;
            String dateText = cell(r, cDate);
            LocalDate date = parseDate(dateText);
            BigDecimal debit = cDebit >= 0 ? parseAmount(cell(r, cDebit)) : null;
            BigDecimal credit = cCredit >= 0 ? parseAmount(cell(r, cCredit)) : null;
            Direction direction = null;
            BigDecimal amount = null;
            if (cDebit >= 0) {
                if (debit != null && debit.signum() > 0) {
                    direction = Direction.DEBIT;
                    amount = debit;
                } else if (credit != null && credit.signum() > 0) {
                    direction = Direction.CREDIT;
                    amount = credit;
                }
            } else {
                BigDecimal raw = parseSignedAmount(cell(r, cAmount));
                if (raw != null && raw.signum() != 0) {
                    String type = cType >= 0 ? cell(r, cType).trim().toUpperCase(Locale.ROOT) : "";
                    if (type.startsWith("DEP") || type.startsWith("C") || type.startsWith("I") || type.startsWith("R")) direction = Direction.CREDIT;
                    else if (type.startsWith("D") || type.startsWith("W") || type.startsWith("E") || type.startsWith("OUT") || type.startsWith("SPENT") || type.startsWith("PAID")) direction = Direction.DEBIT;
                    else direction = raw.signum() < 0 ? Direction.DEBIT : Direction.CREDIT;
                    amount = raw.abs();
                }
            }
            if (date == null && amount == null) continue; // footer / blank / summary lines
            if (date == null) {
                errors.add(new RowError(rowNumber, "Unrecognised date '" + dateText + "'"));
                continue;
            }
            if (amount == null) {
                errors.add(new RowError(rowNumber, "No withdrawal or deposit amount"));
                continue;
            }
            String desc = cell(r, cDesc).trim();
            String note = cNote >= 0 ? cell(r, cNote).trim() : "";
            // A personal sheet often has only a Description you typed yourself ("petrol for bike", "maid salary").
            // Treat that as your note so note rules, note learning and the note dictionary apply to it.
            if (cNote < 0 && looksHandWritten(desc)) note = desc;
            String category = cCat >= 0 ? cell(r, cCat).trim() : "";
            if (desc.isEmpty()) desc = note;
            String ref = cRef >= 0 ? cell(r, cRef).trim() : "";
            if (ref.matches("0+")) ref = "";
            rows.add(new StatementRow(rowNumber, date, desc.isEmpty() ? null : desc, direction,
                    amount.setScale(2, java.math.RoundingMode.UNNECESSARY), ref.isEmpty() ? null : ref,
                    cBal >= 0 ? parseSignedAmount(cell(r, cBal)) : null,
                    note.isEmpty() ? null : note, category.isEmpty() ? null : category));
        }
        return new Result(rows, errors, layout);
    }

    private static final java.util.regex.Pattern BANK_NARRATION = java.util.regex.Pattern.compile(
            "(?i)(\\bupi\\b|\\bneft\\b|\\bimps\\b|\\brtgs\\b|\\bnach\\b|\\bach\\b|\\becs\\b|\\bpos\\b|\\batm\\b|\\bmmt\\b|"
                    + "\\bbil\\b|\\bint\\.?pd\\b|\\btrf\\b|\\bvps\\b|\\bipay\\b|[/@#|]|\\d{6,}|\\b[A-Z]{4}0[A-Z0-9]{6}\\b)");

    /**
     * True when a description reads like something a person typed rather than a bank narration: short, a few words,
     * no channel codes (UPI/NEFT/IMPS/POS…), no slashes, VPAs or long reference numbers.
     */
    public static boolean looksHandWritten(String desc) {
        if (desc == null) return false;
        String d = desc.trim();
        if (d.length() < 2 || d.length() > 80) return false;
        if (d.split("\\s+").length > 10) return false;
        if (!d.matches(".*[\\p{L}].*")) return false;
        return !BANK_NARRATION.matcher(d).find();
    }

    /** Returns column indexes [date, desc, debit, credit, amount, type, ref, balance, note, category] or null if not a header. */
    private static int[] mapColumns(List<String> header) {
        List<String> norm = header.stream().map(CsvStatementParser::normaliseHeader).toList();
        int date = find(norm, DATE);
        int desc = find(norm, DESC);
        int debit = find(norm, DEBIT);
        int credit = find(norm, CREDIT);
        int amount = find(norm, AMOUNT);
        int type = find(norm, TYPE);
        int ref = find(norm, REF);
        int bal = find(norm, BAL);
        int note = find(norm, NOTE);
        int category = find(norm, CATEGORY);
        if (desc < 0) desc = note; // a personal sheet may have only a Note column
        if (date < 0 || desc < 0) return null;
        boolean separate = debit >= 0 && credit >= 0;
        if (!separate && amount < 0) return null;
        if (!separate) {
            debit = -1;
            credit = -1;
        }
        return new int[]{date, desc, debit, credit, amount, type, ref, bal, note, category};
    }

    private static int find(List<String> norm, String[] aliases) {
        for (String alias : aliases) {
            for (int i = 0; i < norm.size(); i++) {
                if (norm.get(i).equals(alias)) return i;
            }
        }
        return -1;
    }

    static String normaliseHeader(String h) {
        return h == null ? "" : h.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
    }

    private static String cell(List<String> row, int idx) {
        return idx >= 0 && idx < row.size() && row.get(idx) != null ? row.get(idx) : "";
    }

    static LocalDate parseDate(String text) {
        if (text == null) return null;
        String t = text.trim();
        if (t.isEmpty()) return null;
        // drop a trailing time component: "17/11/2026 10:15:00" or "2026-11-17T10:15"
        t = t.replaceFirst("[ T][0-9]{1,2}:[0-9]{2}(:[0-9]{2})?(\\s*[AaPp][Mm])?$", "").trim();
        for (DateTimeFormatter fmt : DATE_FORMATS) {
            try {
                LocalDate d = LocalDate.parse(t, fmt);
                if (d.getYear() < 100) d = d.plusYears(2000);
                return d;
            } catch (DateTimeParseException ignored) {
                // next
            }
        }
        return null;
    }

    static BigDecimal parseAmount(String text) {
        BigDecimal v = parseSignedAmount(text);
        return v == null ? null : v.abs();
    }

    static BigDecimal parseSignedAmount(String text) {
        if (text == null) return null;
        String t = text.trim().replace(",", "").replace("₹", "").replaceAll("(?i)inr|rs\\.?", "").trim();
        boolean negative = false;
        if (t.endsWith("Dr") || t.endsWith("DR")) {
            negative = true;
            t = t.substring(0, t.length() - 2).trim();
        } else if (t.endsWith("Cr") || t.endsWith("CR")) {
            t = t.substring(0, t.length() - 2).trim();
        }
        if (t.startsWith("(") && t.endsWith(")")) {
            negative = true;
            t = t.substring(1, t.length() - 1);
        }
        if (t.isEmpty() || t.equals("-")) return null;
        try {
            BigDecimal v = new BigDecimal(t);
            if (v.scale() > 2) return null;
            return negative ? v.negate() : v;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
