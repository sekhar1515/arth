package com.expensetracker.auth;

import com.expensetracker.networth.PriceRefresherAccess;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.*;

class PasswordAndPriceFreshnessTest {

    @Test
    void readsBcryptCostSoOldHashesAreUpgraded() {
        assertEquals(12, AuthService.bcryptCost("$2a$12$J2ERb9vtrWApnOX7O6MYbOWLPY8Gfx89NgvAvfuA1KwVttVvxEJV6"));
        assertEquals(10, AuthService.bcryptCost("$2a$10$abcdefghijklmnopqrstuv"));
        assertEquals(-1, AuthService.bcryptCost("plain"));
        assertEquals(-1, AuthService.bcryptCost(null));
    }

    @Test
    void expectedPriceDateSkipsWeekendsAndWaitsForTheCutOff() {
        LocalTime navs = LocalTime.of(21, 30);
        // Wednesday 18 Nov 2026, 10:00 IST (04:30Z): yesterday's NAVs are the newest expected
        assertEquals(LocalDate.of(2026, 11, 17), PriceRefresherAccess.expected(Instant.parse("2026-11-18T04:30:00Z"), navs));
        // Wednesday 22:00 IST: today's
        assertEquals(LocalDate.of(2026, 11, 18), PriceRefresherAccess.expected(Instant.parse("2026-11-18T16:30:00Z"), navs));
        // Monday morning: Friday's
        assertEquals(LocalDate.of(2026, 11, 20), PriceRefresherAccess.expected(Instant.parse("2026-11-23T04:30:00Z"), navs));
        // Sunday evening: Friday's
        assertEquals(LocalDate.of(2026, 11, 20), PriceRefresherAccess.expected(Instant.parse("2026-11-22T16:30:00Z"), navs));
        assertTrue(PriceRefresherAccess.behind(null, LocalDate.of(2026, 11, 20)));
        assertFalse(PriceRefresherAccess.behind(LocalDate.of(2026, 11, 20), LocalDate.of(2026, 11, 20)));
    }
}
