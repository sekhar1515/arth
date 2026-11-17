package com.expensetracker.auth;

import com.expensetracker.common.ApiException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

class RegistrationPolicyTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-11-17T10:00:00Z"), ZoneOffset.UTC);

    @Test
    void openAllowsAnyoneUpToTheHourlyCap() {
        var p = new RegistrationPolicy("open", "", 3, clock);
        for (int i = 0; i < 3; i++) p.check("1.2.3.4", null);
        var e = assertThrows(ApiException.class, () -> p.check("1.2.3.4", null));
        assertEquals(429, e.getStatus().value());
        p.check("5.6.7.8", null); // a different address is unaffected
    }

    @Test
    void inviteNeedsTheRightCode() {
        var p = new RegistrationPolicy("invite", "family-2026", 5, clock);
        assertEquals(403, assertThrows(ApiException.class, () -> p.check("1.2.3.4", "wrong")).getStatus().value());
        assertEquals(403, assertThrows(ApiException.class, () -> p.check("1.2.3.4", null)).getStatus().value());
        p.check("1.2.3.4", " family-2026 ");
        assertThrows(IllegalStateException.class, () -> new RegistrationPolicy("invite", "", 5, clock), "invite mode needs a code");
    }

    @Test
    void closedRefusesEveryone() {
        var p = new RegistrationPolicy("CLOSED", "", 5, clock);
        assertEquals(403, assertThrows(ApiException.class, () -> p.check("1.2.3.4", null)).getStatus().value());
        assertThrows(IllegalStateException.class, () -> new RegistrationPolicy("sometimes", "", 5, clock));
    }
}
