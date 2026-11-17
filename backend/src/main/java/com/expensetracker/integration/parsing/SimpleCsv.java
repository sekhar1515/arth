package com.expensetracker.integration.parsing;

import java.util.ArrayList;
import java.util.List;

/** Minimal RFC 4180 CSV reader (quoted fields, escaped quotes, CRLF/LF, embedded newlines). */
public final class SimpleCsv {

    private SimpleCsv() {
    }

    public static List<List<String>> parse(String content) {
        List<List<String>> rows = new ArrayList<>();
        if (content == null) return rows;
        if (!content.isEmpty() && content.charAt(0) == '﻿') content = content.substring(1);
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < content.length(); i++) {
            char ch = content.charAt(i);
            if (inQuotes) {
                if (ch == '"') {
                    if (i + 1 < content.length() && content.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    field.append(ch);
                }
            } else if (ch == '"') {
                inQuotes = true;
            } else if (ch == ',') {
                row.add(field.toString());
                field.setLength(0);
            } else if (ch == '\n' || ch == '\r') {
                if (ch == '\r' && i + 1 < content.length() && content.charAt(i + 1) == '\n') i++;
                row.add(field.toString());
                field.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
            } else {
                field.append(ch);
            }
        }
        if (field.length() > 0 || !row.isEmpty()) {
            row.add(field.toString());
            rows.add(row);
        }
        rows.removeIf(r -> r.stream().allMatch(String::isBlank));
        return rows;
    }
}
