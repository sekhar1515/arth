package com.expensetracker.report;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ReportWritersTest {

    static ReportTable sample(int rows) {
        List<List<Object>> r = new ArrayList<>();
        for (int i = 0; i < rows; i++) r.add(List.of("2026-10-" + String.format("%02d", 1 + i % 28), "Swiggy, \"Bengaluru\" #" + i, "Food & Dining", new BigDecimal("1234567.50")));
        return new ReportTable("Transactions", "1 Oct 2026 to 31 Oct 2026", List.of("Total spent: ₹12,34,568"),
                List.of(new ReportTable.Section("Transactions", List.of("Date", "Description", "Category", "Amount (₹)"), r),
                        new ReportTable.Section("Categories", List.of("Category", "Amount (₹)"), List.of(List.of("=cmd()", new BigDecimal("10.00"))))));
    }

    @Test
    void csvEscapesQuotesAndFormulas() {
        String csv = new String(CsvWriter.write(sample(1)), StandardCharsets.UTF_8);
        assertTrue(csv.startsWith("﻿Transactions\r\nDate,Description"));
        assertTrue(csv.contains("\"Swiggy, \"\"Bengaluru\"\" #0\""));
        assertTrue(csv.contains("'=cmd()"));
        assertTrue(csv.contains("1234567.50"));
    }

    @Test
    void pdfFormattingHelpers() {
        assertEquals("12,34,567.50", PdfWriter.indian(new BigDecimal("1234567.5")));
        assertEquals("-999", PdfWriter.indian(new BigDecimal("-999")));
        assertEquals("1,000", PdfWriter.indian(new BigDecimal("1000")));
        assertEquals("Rs 500 \\(net\\)", PdfWriter.escape(PdfWriter.latin("₹500 (net)")));
        byte[] pdf = PdfWriter.write(sample(150));
        String head = new String(pdf, 0, 8, StandardCharsets.ISO_8859_1);
        assertEquals("%PDF-1.4", head);
        String all = new String(pdf, StandardCharsets.ISO_8859_1);
        assertTrue(all.contains("/Count 3"), "150 rows should need three pages");
        assertTrue(all.trim().endsWith("%%EOF"));
    }

    @Test
    void xlsxHasOneSheetPerSection() throws Exception {
        byte[] x = XlsxWriter.write(sample(3));
        var zip = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(x));
        List<String> names = new ArrayList<>();
        for (var e = zip.getNextEntry(); e != null; e = zip.getNextEntry()) names.add(e.getName());
        assertTrue(names.contains("xl/worksheets/sheet2.xml"), names.toString());
        assertEquals("AA", XlsxWriter.col(26));
        assertEquals("a &amp; &lt;b&gt;", XlsxWriter.esc("a & <b>"));
    }
}
