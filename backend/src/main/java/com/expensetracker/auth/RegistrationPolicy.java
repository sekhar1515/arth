package com.expensetracker.auth;

import com.expensetracker.common.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Who may create an account on a public server. REGISTRATION_MODE: open (anyone), invite (needs REGISTRATION_INVITE_CODE)
 * or closed (no new accounts). Also caps sign-ups per client address so a bot can't flood the database.
 */
@Component
public class RegistrationPolicy {

    public enum Mode { OPEN, INVITE, CLOSED }

    static final Duration WINDOW = Duration.ofHours(1);

    private final Mode mode;
    private final String inviteCode;
    private final int maxPerHour;
    private final Clock clock;
    private final Map<String, Deque<Instant>> recent = new ConcurrentHashMap<>();

    public RegistrationPolicy(@Value("${app.registration.mode:open}") String mode,
                              @Value("${app.registration.invite-code:}") String inviteCode,
                              @Value("${app.registration.max-per-hour:5}") int maxPerHour, Clock clock) {
        Mode m;
        try {
            m = Mode.valueOf(mode.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("REGISTRATION_MODE must be open, invite or closed");
        }
        if (m == Mode.INVITE && (inviteCode == null || inviteCode.isBlank())) {
            throw new IllegalStateException("REGISTRATION_MODE=invite needs REGISTRATION_INVITE_CODE");
        }
        this.mode = m;
        this.inviteCode = inviteCode == null ? "" : inviteCode.trim();
        this.maxPerHour = maxPerHour;
        this.clock = clock;
    }

    public Mode mode() {
        return mode;
    }

    /** Throws when this sign-up isn't allowed; records it otherwise. */
    public void check(String clientAddress, String suppliedCode) {
        if (mode == Mode.CLOSED) {
            throw new ApiException(HttpStatus.FORBIDDEN, "REGISTRATION_CLOSED", "This server isn't accepting new accounts.");
        }
        if (mode == Mode.INVITE) {
            byte[] a = (suppliedCode == null ? "" : suppliedCode.trim()).getBytes(StandardCharsets.UTF_8);
            if (!MessageDigest.isEqual(a, inviteCode.getBytes(StandardCharsets.UTF_8))) {
                throw new ApiException(HttpStatus.FORBIDDEN, "INVITE_REQUIRED", "That invite code isn't right.");
            }
        }
        Instant now = clock.instant();
        Deque<Instant> times = recent.computeIfAbsent(clientAddress == null ? "?" : clientAddress, k -> new ArrayDeque<>());
        synchronized (times) {
            while (!times.isEmpty() && times.peekFirst().isBefore(now.minus(WINDOW))) times.pollFirst();
            if (times.size() >= maxPerHour) {
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_SIGNUPS", "Too many new accounts from your network. Try again in an hour.");
            }
            times.addLast(now);
        }
    }
}
