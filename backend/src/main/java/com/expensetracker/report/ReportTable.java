package com.expensetracker.report;

import java.util.List;

/**
 * A report as a title, summary lines and one or more tables. Cells are strings or numbers (BigDecimal/Number);
 * renderers decide formatting.
 */
public record ReportTable(String title, String subtitle, List<String> summary, List<Section> sections) {

    public record Section(String heading, List<String> columns, List<List<Object>> rows) {
    }
}
