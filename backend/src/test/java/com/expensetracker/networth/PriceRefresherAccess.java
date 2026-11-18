package com.expensetracker.networth;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

/** Test access to PriceRefresher's package-private date logic. */
public final class PriceRefresherAccess {
    private PriceRefresherAccess() {
    }

    public static LocalDate expected(Instant now, LocalTime cutOff) {
        return PriceRefresher.expectedDate(now, cutOff);
    }

    public static boolean behind(LocalDate have, LocalDate expected) {
        return PriceRefresher.isBehind(have, expected);
    }
}
