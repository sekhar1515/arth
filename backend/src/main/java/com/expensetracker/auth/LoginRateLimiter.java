package com.expensetracker.auth;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sliding-window limiter for login attempts and ingest calls. In-process is sufficient for a single backend
 * instance; replace with Redis when running more than one replica (ADR in ARCHITECTURE.md).
 */
@Component
public class LoginRateLimiter {

    private final Map<String, Deque<Instant>> attempts = new ConcurrentHashMap<>();
    private final Clock clock;

    public LoginRateLimiter(Clock clock) {
        this.clock = clock;
    }

    /** Records an attempt and returns false when the key exceeded {@code max} attempts within {@code window}. */
    public boolean tryAcquire(String key, int max, Duration window) {
        Instant now = clock.instant();
        Deque<Instant> q = attempts.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (q) {
            while (!q.isEmpty() && q.peekFirst().isBefore(now.minus(window))) q.pollFirst();
            if (q.size() >= max) return false;
            q.addLast(now);
            return true;
        }
    }

    public void reset(String key) {
        attempts.remove(key);
    }
}
