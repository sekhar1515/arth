package com.expensetracker.integration.parsing;

import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.time.LocalDate;

/** Builds Excel files the way banks and brokers export them, for tests. */
public final class SpreadsheetFixtures {

    private SpreadsheetFixtures() {
    }

    /** Summary sheet first, then a transactions sheet with a preamble, real date cells and numeric amounts. */
    public static byte[] statement(boolean legacyXls) throws Exception {
        try (Workbook wb = legacyXls ? new HSSFWorkbook() : new XSSFWorkbook()) {
            Sheet summary = wb.createSheet("Summary");
            summary.createRow(0).createCell(0).setCellValue("Account summary");
            summary.createRow(1).createCell(0).setCellValue("Opening balance: 50,000");

            Sheet s = wb.createSheet("Transactions");
            CellStyle date = wb.createCellStyle();
            date.setDataFormat(wb.getCreationHelper().createDataFormat().getFormat("dd-mmm-yy"));
            s.createRow(0).createCell(0).setCellValue("Statement of account for 1234");
            Row h = s.createRow(2);
            String[] head = {"Date", "Description", "Amount", "Type"};
            for (int i = 0; i < head.length; i++) h.createCell(i).setCellValue(head[i]);
            Object[][] rows = {
                    {LocalDate.of(2026, 11, 12), "maid salary", 3000, "Debit"},
                    {LocalDate.of(2026, 11, 13), "haircut", 250.5, "Debit"},
                    {LocalDate.of(2026, 11, 14), "freelance logo project", 8000, "Credit"},
            };
            for (int i = 0; i < rows.length; i++) {
                Row r = s.createRow(3 + i);
                var c0 = r.createCell(0);
                c0.setCellValue((LocalDate) rows[i][0]);
                c0.setCellStyle(date);
                r.createCell(1).setCellValue((String) rows[i][1]);
                r.createCell(2).setCellValue(((Number) rows[i][2]).doubleValue());
                r.createCell(3).setCellValue((String) rows[i][3]);
            }
            return bytes(wb);
        }
    }

    /** Zerodha Console style: a few title lines, then Equity and Mutual Funds sheets. */
    public static byte[] holdings() throws Exception {
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet eq = wb.createSheet("Equity");
            eq.createRow(0).createCell(1).setCellValue("Client ID AB1234");
            eq.createRow(1).createCell(1).setCellValue("Equity Holdings Statement as on 2026-11-17");
            Row h = eq.createRow(4);
            String[] head = {"", "Symbol", "ISIN", "Quantity Available", "Average Price", "Previous Closing Price"};
            for (int i = 0; i < head.length; i++) h.createCell(i).setCellValue(head[i]);
            Row r = eq.createRow(5);
            r.createCell(1).setCellValue("INFY");
            r.createCell(2).setCellValue("INE009A01021");
            r.createCell(3).setCellValue(10);
            r.createCell(4).setCellValue(1450.5);
            r.createCell(5).setCellValue(1145.1);

            Sheet mf = wb.createSheet("Mutual Funds");
            Row mh = mf.createRow(2);
            String[] mhead = {"Scheme Name", "ISIN", "Folio No", "Units", "Avg NAV", "Current NAV"};
            for (int i = 0; i < mhead.length; i++) mh.createCell(i).setCellValue(mhead[i]);
            Row m = mf.createRow(3);
            m.createCell(0).setCellValue("Parag Parikh Flexi Cap Fund - Direct Plan - Growth");
            m.createCell(1).setCellValue("INF879O01027");
            m.createCell(2).setCellValue("12345678");
            m.createCell(3).setCellValue(250.512);
            m.createCell(4).setCellValue(60);
            m.createCell(5).setCellValue(94.4817);
            return bytes(wb);
        }
    }

    public static final String HTML_XLS = """
            <html><body><table border="1">
            <tr><td>Statement for account XXXX1234</td></tr>
            <tr><th>Txn Date</th><th>Narration</th><th>Withdrawal Amt.</th><th>Deposit Amt.</th><th>Closing Balance</th></tr>
            <tr><td>12/11/2026</td><td>UPI/DR/431212345678/SWIGGY/YESB/swiggy@yesbank/Payment</td><td>1,250.00</td><td>&nbsp;</td><td>48,750.00</td></tr>
            <tr><td>13/11/2026</td><td>NEFT CR-CITI0000002-ACME TECHNOLOGIES &amp; CO-SALARY</td><td></td><td>92,000.00</td><td>1,40,750.00</td></tr>
            </table></body></html>
            """;

    private static byte[] bytes(Workbook wb) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        wb.write(out);
        return out.toByteArray();
    }
}
