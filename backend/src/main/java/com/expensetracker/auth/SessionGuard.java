package com.expensetracker.auth;

import com.expensetracker.user.UserRepository;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Session tokens are self-contained, so on their own they stay valid until they expire. This rejects tokens issued
 * before the user's "sessions valid after" time (set when a password is reset or changed) and tokens of deleted
 * accounts. Looks are cached for a minute so ordinary requests don't hit the database.
 */
@Component
public class SessionGuard {

    private record Entry(boolean exists, Instant validAfter, Instant loadedAt) {
    }

    static final Duration CACHE = Duration.ofMinutes(1);

    private final UserRepository users;
    private final Clock clock;
    private final Map<UUID, Entry> cache = new ConcurrentHashMap<>();

    public SessionGuard(UserRepository users, Clock clock) {
        this.users = users;
        this.clock = clock;
    }

    public boolean accepts(AuthenticatedUser user) {
        Instant now = clock.instant();
        Entry e = cache.get(user.id());
        if (e == null || e.loadedAt().plus(CACHE).isBefore(now)) {
            var u = users.findById(user.id());
            e = new Entry(u.isPresent(), u.map(com.expensetracker.user.User::getSessionsValidAfter).orElse(null), now);
            cache.put(user.id(), e);
        }
        if (!e.exists()) return false;
        if (e.validAfter() == null) return true;
        return user.issuedAt() != null && !user.issuedAt().isBefore(e.validAfter());
    }

    /** Ends every existing session of this user from now on (this instance immediately, others within a minute). */
    public void revokeAll(com.expensetracker.user.User user) {
        Instant now = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        user.setSessionsValidAfter(now);
        cache.put(user.getId(), new Entry(true, now, clock.instant()));
    }

    public void forget(UUID userId) {
        cache.remove(userId);
    }

    Optional<Instant> cachedValidAfter(UUID userId) {
        return Optional.ofNullable(cache.get(userId)).map(Entry::validAfter);
    }
}
