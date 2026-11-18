package com.expensetracker.networth;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** "17 Nov" style dates for messages. */
final class InrDates {
    private static final DateTimeFormatter SHORT = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);

    private InrDates() {
    }

    static String shortDate(LocalDate d) {
        return d.format(SHORT);
    }
}
