package com.expensetracker.analytics;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Formats rupee amounts with Indian digit grouping: 1234567.5 → ₹12,34,568. */
public final class InrFormat {
    private InrFormat() {
    }

    public static String format(BigDecimal amount) {
        BigDecimal rounded = amount.setScale(0, RoundingMode.HALF_UP);
        boolean negative = rounded.signum() < 0;
        String digits = rounded.abs().toPlainString();
        StringBuilder sb = new StringBuilder();
        int len = digits.length();
        if (len <= 3) {
            sb.append(digits);
        } else {
            String head = digits.substring(0, len - 3);
            String tail = digits.substring(len - 3);
            StringBuilder h = new StringBuilder();
            for (int i = head.length(); i > 0; i -= 2) {
                int start = Math.max(0, i - 2);
                h.insert(0, head.substring(start, i));
                if (start > 0) h.insert(0, ',');
            }
            sb.append(h).append(',').append(tail);
        }
        return (negative ? "-₹" : "₹") + sb;
    }
}
