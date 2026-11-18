package com.expensetracker.networth;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class RefreshWindowsTest {

    // IST = UTC+5:30
    private static Instant ist(String localDateTime) {
        return java.time.LocalDateTime.parse(localDateTime).atZone(com.expensetracker.common.TimeConfig.IST).toInstant();
    }

    @Test
    void oncBeforeOpenOnceAfterCloseNoneDuringMarket() {
        // 8:00 am, never refreshed: allowed
        assertTrue(RefreshWindows.status(null, ist("2026-11-18T08:00")).allowed());
        // refreshed at 7:30 am, now 9:00 am: used up until 3:45 pm
        var used = RefreshWindows.status(ist("2026-11-18T07:30"), ist("2026-11-18T09:00"));
        assertFalse(used.allowed());
        assertEquals(ist("2026-11-18T15:45"), used.nextAllowedAt());
        assertTrue(used.reason().contains("after 3:45 pm"));
        // market hours: never allowed
        var market = RefreshWindows.status(null, ist("2026-11-18T11:00"));
        assertFalse(market.allowed());
        assertEquals(RefreshWindows.Window.MARKET_HOURS, market.window());
        assertEquals(ist("2026-11-18T15:45"), market.nextAllowedAt());
        // the morning refresh doesn't use up the evening one
        assertTrue(RefreshWindows.status(ist("2026-11-18T07:30"), ist("2026-11-18T16:00")).allowed());
        // refreshed at 4 pm, now 10 pm: next tomorrow morning
        var evening = RefreshWindows.status(ist("2026-11-18T16:00"), ist("2026-11-18T22:00"));
        assertFalse(evening.allowed());
        assertEquals(ist("2026-11-19T00:00"), evening.nextAllowedAt());
        // yesterday evening's refresh doesn't block this morning
        assertTrue(RefreshWindows.status(ist("2026-11-18T22:00"), ist("2026-11-19T06:00")).allowed());
        // edges: 9:15 is market hours, 3:45 pm is open
        assertFalse(RefreshWindows.status(null, ist("2026-11-18T09:15")).allowed());
        assertTrue(RefreshWindows.status(null, ist("2026-11-18T15:45")).allowed());
    }
}
