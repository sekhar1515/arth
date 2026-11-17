package com.expensetracker.integration.parsing;

import org.apache.poi.EncryptedDocumentException;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns Excel files into the same text tables the CSV parsers read, so statements and holdings exports work the same
 * whichever format the bank or broker hands out. Handles .xlsx, legacy .xls, and the "Excel" downloads many Indian
 * banks send that are really HTML tables. Each non-empty sheet becomes one table; dates become yyyy-MM-dd and numbers
 * plain decimals, so nothing depends on the spreadsheet's display format or locale.
 */
public final class SpreadsheetReader {

    public record SheetTable(String name, String csv, int rows) {
    }

    /** Larger spreadsheets are refused: they would need a lot of memory on a small server. */
    public static final int MAX_BYTES = 5 * 1024 * 1024;
    static final int MAX_ROWS = 25_000;
    static final int MAX_COLUMNS = 60;

    private static final Pattern ROW = Pattern.compile("<tr[^>]*>(.*?)</tr>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern CELL = Pattern.compile("<t[dh][^>]*>(.*?)</t[dh]>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private SpreadsheetReader() {
    }

    public static boolean isSpreadsheet(String filename) {
        String n = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        return n.endsWith(".xlsx") || n.endsWith(".xls") || n.endsWith(".xlsm");
    }

    /** All non-empty sheets, in workbook order. Throws IllegalArgumentException with a message fit to show the user. */
    public static List<SheetTable> read(byte[] content) {
        if (content == null || content.length == 0) throw new IllegalArgumentException("The file is empty");
        if (content.length > MAX_BYTES) {
            throw new IllegalArgumentException("That spreadsheet is over 5 MB. Export a shorter period, or save it as CSV and upload that.");
        }
        if (looksLikeHtml(content)) return List.of(fromHtml(new String(content, StandardCharsets.UTF_8)));
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(content))) {
            List<SheetTable> out = new ArrayList<>();
            for (int s = 0; s < wb.getNumberOfSheets(); s++) {
                Sheet sheet = wb.getSheetAt(s);
                if (wb.isSheetHidden(s) || wb.isSheetVeryHidden(s)) continue;
                SheetTable t = fromSheet(sheet);
                if (t.rows() > 0) out.add(t);
            }
            if (out.isEmpty()) throw new IllegalArgumentException("The spreadsheet has no data");
            return out;
        } catch (EncryptedDocumentException e) {
            throw new IllegalArgumentException("This spreadsheet is password-protected. Open it in Excel or Google Sheets, "
                    + "save a copy without the password, and upload that.");
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Couldn't read that spreadsheet. Save it as .xlsx or CSV and try again.");
        }
    }

    private static SheetTable fromSheet(Sheet sheet) {
        StringBuilder csv = new StringBuilder();
        int rows = 0;
        int last = Math.min(sheet.getLastRowNum(), MAX_ROWS);
        for (int r = sheet.getFirstRowNum(); r <= last; r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            int lastCell = Math.min(row.getLastCellNum(), MAX_COLUMNS);
            if (lastCell <= 0) continue;
            List<String> cells = new ArrayList<>(lastCell);
            boolean any = false;
            for (int c = 0; c < lastCell; c++) {
                String v = text(row.getCell(c));
                if (!v.isEmpty()) any = true;
                cells.add(v);
            }
            if (!any) continue;
            appendRow(csv, cells);
            rows++;
        }
        return new SheetTable(sheet.getSheetName(), csv.toString(), rows);
    }

    static String text(Cell cell) {
        if (cell == null) return "";
        CellType type = cell.getCellType() == CellType.FORMULA ? cell.getCachedFormulaResultType() : cell.getCellType();
        return switch (type) {
            case STRING -> clean(cell.getStringCellValue());
            case NUMERIC -> {
                if (DateUtil.isCellDateFormatted(cell)) {
                    LocalDateTime dt = cell.getLocalDateTimeCellValue();
                    yield dt == null ? "" : dt.toLocalDate().toString();
                }
                double d = cell.getNumericCellValue();
                yield BigDecimal.valueOf(d).stripTrailingZeros().toPlainString();
            }
            case BOOLEAN -> cell.getBooleanCellValue() ? "TRUE" : "FALSE";
            default -> "";
        };
    }

    private static boolean looksLikeHtml(byte[] content) {
        int n = Math.min(content.length, 2048);
        String head = new String(content, 0, n, StandardCharsets.UTF_8).trim().toLowerCase(Locale.ROOT);
        if (head.startsWith("﻿")) head = head.substring(1);
        return head.startsWith("<") && (head.contains("<html") || head.contains("<table") || head.contains("<!doctype"));
    }

    static SheetTable fromHtml(String html) {
        StringBuilder csv = new StringBuilder();
        int rows = 0;
        Matcher tr = ROW.matcher(html);
        while (tr.find() && rows < MAX_ROWS) {
            Matcher td = CELL.matcher(tr.group(1));
            List<String> cells = new ArrayList<>();
            boolean any = false;
            while (td.find() && cells.size() < MAX_COLUMNS) {
                String v = clean(unescape(td.group(1).replaceAll("(?is)<br\\s*/?>", " ").replaceAll("(?s)<[^>]+>", "")));
                if (!v.isEmpty()) any = true;
                cells.add(v);
            }
            if (!any) continue;
            appendRow(csv, cells);
            rows++;
        }
        if (rows == 0) throw new IllegalArgumentException("The file has no table rows");
        return new SheetTable("Sheet1", csv.toString(), rows);
    }

    private static String unescape(String s) {
        String out = s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'");
        Matcher m = Pattern.compile("&#(\\d{1,6});").matcher(out);
        StringBuilder sb = new StringBuilder();
        while (m.find()) m.appendReplacement(sb, Matcher.quoteReplacement(String.valueOf((char) Integer.parseInt(m.group(1)))));
        m.appendTail(sb);
        return sb.toString();
    }

    private static String clean(String s) {
        return s == null ? "" : s.replace(' ', ' ').replaceAll("[\\r\\n\\t]+", " ").trim();
    }

    private static void appendRow(StringBuilder csv, List<String> cells) {
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) csv.append(',');
            String v = cells.get(i);
            if (v.indexOf(',') >= 0 || v.indexOf('"') >= 0) csv.append('"').append(v.replace("\"", "\"\"")).append('"');
            else csv.append(v);
        }
        csv.append('\n');
    }
}
