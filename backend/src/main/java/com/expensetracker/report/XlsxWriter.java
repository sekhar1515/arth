package com.expensetracker.report;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Minimal Office Open XML (.xlsx) writer: one worksheet per report section, bold headers, numbers stored as
 * numbers with Indian-style two-decimal format, frozen header row. No external library needed.
 */
public final class XlsxWriter {

    private XlsxWriter() {
    }

    public static byte[] write(ReportTable report) {
        List<String> names = sheetNames(report);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            put(zip, "[Content_Types].xml", contentTypes(names.size()));
            put(zip, "_rels/.rels", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""");
            put(zip, "xl/workbook.xml", workbook(names));
            put(zip, "xl/_rels/workbook.xml.rels", workbookRels(names.size()));
            put(zip, "xl/styles.xml", STYLES);
            for (int i = 0; i < report.sections().size(); i++) {
                put(zip, "xl/worksheets/sheet" + (i + 1) + ".xml", sheet(report, i));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    static List<String> sheetNames(ReportTable report) {
        List<String> names = new ArrayList<>();
        Set<String> used = new HashSet<>();
        for (ReportTable.Section s : report.sections()) {
            String base = (s.heading() == null || s.heading().isBlank() ? "Sheet" : s.heading()).replaceAll("[\\[\\]:*?/\\\\]", " ").trim();
            if (base.length() > 28) base = base.substring(0, 28).trim();
            String name = base;
            int n = 2;
            while (!used.add(name.toLowerCase())) name = base + " " + n++;
            names.add(name);
        }
        return names;
    }

    private static String sheet(ReportTable report, int index) {
        ReportTable.Section s = report.sections().get(index);
        StringBuilder rows = new StringBuilder();
        int r = 1;
        if (index == 0) {
            rows.append(rowOf(r++, List.of(report.title()), 3));
            if (report.subtitle() != null) rows.append(rowOf(r++, List.of(report.subtitle()), 0));
            for (String line : report.summary()) rows.append(rowOf(r++, List.of(line), 0));
            r++;
        }
        int headerRow = r;
        rows.append(rowOf(r++, new ArrayList<>(s.columns()), 1));
        for (List<Object> row : s.rows()) rows.append(rowOf(r++, row, -1));
        int[] widths = new int[s.columns().size()];
        for (int c = 0; c < widths.length; c++) {
            int w = s.columns().get(c).length();
            for (List<Object> row : s.rows()) if (c < row.size() && row.get(c) != null) w = Math.max(w, display(row.get(c)).length());
            widths[c] = Math.min(60, Math.max(8, w + 2));
        }
        StringBuilder cols = new StringBuilder("<cols>");
        for (int c = 0; c < widths.length; c++) cols.append("<col min=\"").append(c + 1).append("\" max=\"").append(c + 1).append("\" width=\"").append(widths[c]).append("\" customWidth=\"1\"/>");
        cols.append("</cols>");
        String pane = "<sheetViews><sheetView workbookViewId=\"0\"><pane ySplit=\"" + headerRow + "\" topLeftCell=\"A" + (headerRow + 1)
                + "\" activePane=\"bottomLeft\" state=\"frozen\"/></sheetView></sheetViews>";
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">" + pane
                + (widths.length > 0 ? cols : "") + "<sheetData>" + rows + "</sheetData></worksheet>";
    }

    /** style: -1 = auto (numbers get the money format), 0 = plain, 1 = bold header, 3 = title. */
    private static String rowOf(int r, List<Object> cells, int style) {
        StringBuilder sb = new StringBuilder("<row r=\"").append(r).append("\">");
        for (int c = 0; c < cells.size(); c++) {
            Object v = cells.get(c);
            if (v == null) continue;
            String ref = col(c) + r;
            if (v instanceof Number num && style == -1) {
                String plain = num instanceof BigDecimal b ? b.toPlainString() : num.toString();
                int st = num instanceof BigDecimal b && b.scale() > 0 ? 2 : 4;
                sb.append("<c r=\"").append(ref).append("\" s=\"").append(st).append("\"><v>").append(plain).append("</v></c>");
            } else {
                int st = style < 0 ? 0 : style;
                sb.append("<c r=\"").append(ref).append("\" t=\"inlineStr\"").append(st > 0 ? " s=\"" + st + "\"" : "")
                        .append("><is><t xml:space=\"preserve\">").append(esc(display(v))).append("</t></is></c>");
            }
        }
        return sb.append("</row>").toString();
    }

    private static String display(Object v) {
        return v instanceof BigDecimal b ? b.toPlainString() : String.valueOf(v);
    }

    static String col(int c) {
        StringBuilder sb = new StringBuilder();
        int n = c + 1;
        while (n > 0) {
            int rem = (n - 1) % 26;
            sb.insert(0, (char) ('A' + rem));
            n = (n - 1) / 26;
        }
        return sb.toString();
    }

    static String esc(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (char ch : s.toCharArray()) {
            switch (ch) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                default -> {
                    if (ch < 0x20 && ch != '\t' && ch != '\n' && ch != '\r') continue;
                    sb.append(ch);
                }
            }
        }
        return sb.toString();
    }

    private static String contentTypes(int sheets) {
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"
                + "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>");
        for (int i = 1; i <= sheets; i++) {
            sb.append("<Override PartName=\"/xl/worksheets/sheet").append(i)
                    .append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>");
        }
        return sb.append("</Types>").toString();
    }

    private static String workbook(List<String> names) {
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets>");
        for (int i = 0; i < names.size(); i++) {
            sb.append("<sheet name=\"").append(esc(names.get(i))).append("\" sheetId=\"").append(i + 1).append("\" r:id=\"rId").append(i + 1).append("\"/>");
        }
        return sb.append("</sheets></workbook>").toString();
    }

    private static String workbookRels(int sheets) {
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">");
        for (int i = 1; i <= sheets; i++) {
            sb.append("<Relationship Id=\"rId").append(i).append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet")
                    .append(i).append(".xml\"/>");
        }
        sb.append("<Relationship Id=\"rId").append(sheets + 1).append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>");
        return sb.append("</Relationships>").toString();
    }

    // 0 plain, 1 bold, 2 money #,##,##0.00, 3 large bold title, 4 integer
    private static final String STYLES = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
            + "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
            + "<numFmts count=\"1\"><numFmt numFmtId=\"164\" formatCode=\"[&gt;=10000000]##\\,##\\,##\\,##0.00;[&gt;=100000]##\\,##\\,##0.00;##,##0.00\"/></numFmts>"
            + "<fonts count=\"3\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font><font><b/><sz val=\"11\"/><name val=\"Calibri\"/></font>"
            + "<font><b/><sz val=\"14\"/><name val=\"Calibri\"/></font></fonts>"
            + "<fills count=\"2\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill></fills>"
            + "<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>"
            + "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>"
            + "<cellXfs count=\"5\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>"
            + "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/>"
            + "<xf numFmtId=\"164\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\"/>"
            + "<xf numFmtId=\"0\" fontId=\"2\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/>"
            + "<xf numFmtId=\"1\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\"/></cellXfs>"
            + "<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles></styleSheet>";

    private static void put(ZipOutputStream zip, String name, String xml) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(xml.strip().getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
