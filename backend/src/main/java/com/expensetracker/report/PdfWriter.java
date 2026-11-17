package com.expensetracker.report;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Small dependency-free PDF writer for tabular reports: A4, built-in Helvetica, repeated table headers on each
 * page, right-aligned numbers with Indian digit grouping, "Page x of y" footer. The built-in fonts cannot show
 * the rupee sign, so amounts are written as "Rs".
 */
public final class PdfWriter {

    private static final float W = 595.28f, H = 841.89f, M = 40f;
    private static final float TABLE_FONT = 8.5f, ROW_H = 13f;
    private static final int[] WIDTHS = {
            278, 278, 355, 556, 556, 889, 667, 191, 333, 333, 389, 584, 278, 333, 278, 278, 556, 556, 556, 556, 556, 556, 556, 556, 556, 556,
            278, 278, 584, 584, 584, 556, 1015, 667, 667, 722, 722, 667, 611, 778, 722, 278, 500, 667, 556, 833, 722, 778, 667, 778, 722, 667,
            611, 722, 667, 944, 667, 667, 611, 278, 278, 278, 469, 556, 333, 556, 556, 500, 556, 556, 278, 556, 556, 222, 222, 500, 222, 833,
            556, 556, 556, 556, 333, 500, 278, 556, 500, 722, 500, 500, 500, 334, 260, 334, 584};

    private PdfWriter() {
    }

    public static byte[] write(ReportTable report) {
        List<StringBuilder> pages = new ArrayList<>();
        Layout l = new Layout(pages);
        l.newPage();
        l.text(M, l.y, 16, true, report.title());
        l.y -= 20;
        if (report.subtitle() != null) {
            l.text(M, l.y, 10, false, report.subtitle());
            l.y -= 16;
        }
        for (String s : report.summary()) {
            l.ensure(14);
            l.text(M, l.y, 10, false, s);
            l.y -= 14;
        }
        l.y -= 8;
        for (ReportTable.Section s : report.sections()) l.table(s);

        int n = pages.size();
        for (int i = 0; i < n; i++) {
            String footer = "Page " + (i + 1) + " of " + n;
            pages.get(i).append(textOp(W - M - width(footer, 8, false), 22, 8, false, footer));
            pages.get(i).append(textOp(M, 22, 8, false, "Arth personal finance"));
        }
        return assemble(pages);
    }

    private static final class Layout {
        final List<StringBuilder> pages;
        StringBuilder page;
        float y;

        Layout(List<StringBuilder> pages) {
            this.pages = pages;
        }

        void newPage() {
            page = new StringBuilder();
            pages.add(page);
            y = H - M - 10;
        }

        boolean ensure(float needed) {
            if (y - needed < M + 10) {
                newPage();
                return true;
            }
            return false;
        }

        void text(float x, float yy, float size, boolean bold, String s) {
            page.append(textOp(x, yy, size, bold, s));
        }

        void table(ReportTable.Section s) {
            int cols = s.columns().size();
            if (cols == 0) return;
            float avail = W - 2 * M;
            float[] want = new float[cols];
            boolean[] numeric = new boolean[cols];
            for (int c = 0; c < cols; c++) {
                want[c] = width(s.columns().get(c), TABLE_FONT, true) + 8;
                boolean allNum = !s.rows().isEmpty();
                for (List<Object> r : s.rows()) {
                    Object v = c < r.size() ? r.get(c) : null;
                    want[c] = Math.max(want[c], Math.min(220, width(cell(v), TABLE_FONT, false) + 8));
                    if (v != null && !(v instanceof Number)) allNum = false;
                }
                numeric[c] = allNum;
            }
            float total = 0;
            for (float w : want) total += w;
            float scale = total > avail ? avail / total : 1f;
            float[] cw = new float[cols];
            for (int c = 0; c < cols; c++) cw[c] = want[c] * scale;

            ensure(ROW_H * 3 + 18);
            if (s.heading() != null && !s.heading().isBlank()) {
                text(M, y, 11, true, s.heading());
                y -= 16;
            }
            header(s, cw, numeric);
            for (List<Object> r : s.rows()) {
                if (ensure(ROW_H)) header(s, cw, numeric);
                float x = M;
                for (int c = 0; c < cols; c++) {
                    Object v = c < r.size() ? r.get(c) : null;
                    String t = fit(cell(v), cw[c] - 6, false);
                    float tx = numeric[c] || v instanceof Number ? x + cw[c] - 3 - width(t, TABLE_FONT, false) : x + 3;
                    text(tx, y, TABLE_FONT, false, t);
                    x += cw[c];
                }
                page.append(String.format(java.util.Locale.ROOT, "0.85 G 0.4 w %.2f %.2f m %.2f %.2f l S 0 G%n", M, y - 4, M + avail, y - 4));
                y -= ROW_H;
            }
            if (s.rows().isEmpty()) {
                text(M + 3, y, TABLE_FONT, false, "Nothing to show for this period.");
                y -= ROW_H;
            }
            y -= 14;
        }

        void header(ReportTable.Section s, float[] cw, boolean[] numeric) {
            float x = M;
            page.append(String.format(java.util.Locale.ROOT, "0.93 g %.2f %.2f %.2f %.2f re f 0 g%n", M, y - 4, W - 2 * M, ROW_H));
            for (int c = 0; c < cw.length; c++) {
                String t = fit(s.columns().get(c), cw[c] - 6, true);
                float tx = numeric[c] ? x + cw[c] - 3 - width(t, TABLE_FONT, true) : x + 3;
                text(tx, y, TABLE_FONT, true, t);
                x += cw[c];
            }
            y -= ROW_H;
        }
    }

    static String cell(Object v) {
        if (v == null) return "";
        if (v instanceof BigDecimal b) return indian(b);
        return latin(String.valueOf(v));
    }

    /** 1234567.5 → 12,34,567.50 */
    static String indian(BigDecimal b) {
        BigDecimal r = b.setScale(b.scale() > 0 ? 2 : 0, RoundingMode.HALF_UP);
        boolean neg = r.signum() < 0;
        String plain = r.abs().toPlainString();
        String intPart = plain.contains(".") ? plain.substring(0, plain.indexOf('.')) : plain;
        String frac = plain.contains(".") ? plain.substring(plain.indexOf('.')) : "";
        StringBuilder sb = new StringBuilder();
        int len = intPart.length();
        if (len <= 3) sb.append(intPart);
        else {
            String head = intPart.substring(0, len - 3);
            for (int i = 0; i < head.length(); i++) {
                sb.append(head.charAt(i));
                int left = head.length() - i - 1;
                if (left > 0 && left % 2 == 0) sb.append(',');
            }
            sb.append(',').append(intPart.substring(len - 3));
        }
        return (neg ? "-" : "") + sb + frac;
    }

    static String latin(String s) {
        StringBuilder sb = new StringBuilder();
        for (char ch : s.replaceAll("₹\\s*(?=[0-9])", "Rs ").replace("₹", "Rs").replace('−', '-').replace('–', '-').replace('—', '-').replace('×', 'x')
                .replace('‘', '\'').replace('’', '\'').replace('“', '"').replace('”', '"').replace('…', '.').toCharArray()) {
            sb.append(ch >= 32 && ch <= 126 ? ch : (ch == '\t' ? ' ' : '?'));
        }
        return sb.toString();
    }

    static float width(String s, float size, boolean bold) {
        float w = 0;
        for (char ch : latin(s).toCharArray()) w += ch >= 32 && ch <= 126 ? WIDTHS[ch - 32] : 556;
        return w * size / 1000f * (bold ? 1.07f : 1f);
    }

    static String fit(String s, float max, boolean bold) {
        String t = latin(s);
        if (width(t, TABLE_FONT, bold) <= max) return t;
        while (t.length() > 1 && width(t + "...", TABLE_FONT, bold) > max) t = t.substring(0, t.length() - 1);
        return t + "...";
    }

    private static String textOp(float x, float y, float size, boolean bold, String s) {
        return String.format(java.util.Locale.ROOT, "BT /%s %.1f Tf %.2f %.2f Td (%s) Tj ET%n", bold ? "F2" : "F1", size, x, y, escape(latin(s)));
    }

    static String escape(String s) {
        return s.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)");
    }

    private static byte[] assemble(List<StringBuilder> pages) {
        // objects: 1 catalog, 2 pages, 3 F1, 4 F2, then (page, content) pairs
        List<String> objs = new ArrayList<>();
        int n = pages.size();
        StringBuilder kids = new StringBuilder();
        for (int i = 0; i < n; i++) kids.append(5 + 2 * i).append(" 0 R ");
        objs.add("<< /Type /Catalog /Pages 2 0 R >>");
        objs.add("<< /Type /Pages /Kids [" + kids.toString().trim() + "] /Count " + n + " >>");
        objs.add("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>");
        objs.add("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold /Encoding /WinAnsiEncoding >>");
        for (int i = 0; i < n; i++) {
            objs.add(String.format(java.util.Locale.ROOT, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 %.2f %.2f] /Resources << /Font << /F1 3 0 R /F2 4 0 R >> >> /Contents %d 0 R >>", W, H, 6 + 2 * i));
            byte[] content = pages.get(i).toString().getBytes(StandardCharsets.ISO_8859_1);
            objs.add("<< /Length " + content.length + " >>\nstream\n" + pages.get(i) + "endstream");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        List<Integer> offsets = new ArrayList<>();
        write(out, "%PDF-1.4\n%âãÏÓ\n");
        for (int i = 0; i < objs.size(); i++) {
            offsets.add(out.size());
            write(out, (i + 1) + " 0 obj\n" + objs.get(i) + "\nendobj\n");
        }
        int xref = out.size();
        StringBuilder sb = new StringBuilder("xref\n0 " + (objs.size() + 1) + "\n0000000000 65535 f \n");
        for (int off : offsets) sb.append(String.format("%010d 00000 n \n", off));
        sb.append("trailer\n<< /Size ").append(objs.size() + 1).append(" /Root 1 0 R >>\nstartxref\n").append(xref).append("\n%%EOF\n");
        write(out, sb.toString());
        return out.toByteArray();
    }

    private static void write(ByteArrayOutputStream out, String s) {
        byte[] b = s.getBytes(StandardCharsets.ISO_8859_1);
        out.write(b, 0, b.length);
    }
}
