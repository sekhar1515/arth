package com.expensetracker.report;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** CSV (RFC 4180) with a UTF-8 BOM so Excel shows ₹ correctly. Cells starting with = + - @ are escaped. */
public final class CsvWriter {

    private CsvWriter() {
    }

    public static byte[] write(ReportTable report) {
        StringBuilder sb = new StringBuilder("﻿");
        boolean multi = report.sections().size() > 1;
        for (int i = 0; i < report.sections().size(); i++) {
            ReportTable.Section s = report.sections().get(i);
            if (multi) {
                if (i > 0) sb.append("\r\n");
                sb.append(cell(s.heading())).append("\r\n");
            }
            row(sb, s.columns().stream().map(c -> (Object) c).toList());
            for (List<Object> r : s.rows()) row(sb, r);
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void row(StringBuilder sb, List<Object> cells) {
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(cell(cells.get(i)));
        }
        sb.append("\r\n");
    }

    public static String cell(Object v) {
        if (v == null) return "";
        if (v instanceof BigDecimal b) return b.toPlainString();
        if (v instanceof Number) return v.toString();
        String s = v.toString();
        if (!s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0) s = "'" + s;
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }
}
