package com.expensetracker.networth;

import com.expensetracker.common.TimeConfig;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;

/**
 * Manual "Refresh prices" is allowed once before the market opens (until 9:15 am IST) and once after it closes
 * (from 3:45 pm IST). During market hours official NAVs and closing prices don't change, so there's nothing to fetch.
 * Automatic refreshes are not limited by this.
 */
public final class RefreshWindows {

    public enum Window { BEFORE_OPEN, MARKET_HOURS, AFTER_CLOSE }

    public record Status(boolean allowed, Window window, Instant nextAllowedAt, Instant lastRefreshAt, String reason) {
    }

    static final LocalTime OPEN = LocalTime.of(9, 15);
    static final LocalTime CLOSE = LocalTime.of(15, 45);

    private RefreshWindows() {
    }

    static Window windowAt(ZonedDateTime ist) {
        LocalTime t = ist.toLocalTime();
        if (t.isBefore(OPEN)) return Window.BEFORE_OPEN;
        if (t.isBefore(CLOSE)) return Window.MARKET_HOURS;
        return Window.AFTER_CLOSE;
    }

    /** Start of the window that contains this moment (today 00:00 or today 15:45), or null during market hours. */
    static ZonedDateTime windowStart(ZonedDateTime ist) {
        return switch (windowAt(ist)) {
            case BEFORE_OPEN -> ist.toLocalDate().atStartOfDay(TimeConfig.IST);
            case AFTER_CLOSE -> ist.toLocalDate().atTime(CLOSE).atZone(TimeConfig.IST);
            case MARKET_HOURS -> null;
        };
    }

    /** The next moment a refresh would be allowed again, assuming the current window is used up (or closed). */
    static ZonedDateTime nextOpening(ZonedDateTime ist) {
        LocalDate today = ist.toLocalDate();
        return switch (windowAt(ist)) {
            case BEFORE_OPEN, MARKET_HOURS -> today.atTime(CLOSE).atZone(TimeConfig.IST);
            case AFTER_CLOSE -> today.plusDays(1).atStartOfDay(TimeConfig.IST);
        };
    }

    public static Status status(Instant lastRefreshAt, Instant now) {
        ZonedDateTime ist = now.atZone(TimeConfig.IST);
        Window w = windowAt(ist);
        if (w == Window.MARKET_HOURS) {
            return new Status(false, w, nextOpening(ist).toInstant(), lastRefreshAt,
                    "Prices don't change during market hours. You can refresh again after 3:45 pm.");
        }
        ZonedDateTime start = windowStart(ist);
        boolean used = lastRefreshAt != null && !lastRefreshAt.isBefore(start.toInstant());
        if (used) {
            String when = w == Window.BEFORE_OPEN ? "after 3:45 pm today" : "tomorrow morning";
            return new Status(false, w, nextOpening(ist).toInstant(), lastRefreshAt,
                    "Already refreshed " + (w == Window.BEFORE_OPEN ? "this morning" : "this evening") + ". You can refresh again " + when + ".");
        }
        return new Status(true, w, null, lastRefreshAt, null);
    }
}
