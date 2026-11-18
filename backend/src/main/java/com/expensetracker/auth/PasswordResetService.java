package com.expensetracker.auth;

import com.expensetracker.audit.AuditService;
import com.expensetracker.common.ApiException;
import com.expensetracker.common.Hashing;
import com.expensetracker.notification.Mailer;
import com.expensetracker.user.User;
import com.expensetracker.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * "Forgot password": e-mails a single-use link that expires after 30 minutes. Only a SHA-256 of the token is stored.
 * The response never says whether an account exists. Resetting signs out every existing session; two-step sign-in
 * stays on, so a reset link alone can't get past it.
 */
@Service
public class PasswordResetService {

    static final Duration LIFETIME = Duration.ofMinutes(30);
    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository users;
    private final NamedParameterJdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final Mailer mailer;
    private final LoginRateLimiter limiter;
    private final SessionGuard sessions;
    private final AuditService audit;
    private final Clock clock;

    public PasswordResetService(UserRepository users, NamedParameterJdbcTemplate jdbc, PasswordEncoder encoder, Mailer mailer,
                                LoginRateLimiter limiter, SessionGuard sessions, AuditService audit, Clock clock) {
        this.users = users;
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.mailer = mailer;
        this.limiter = limiter;
        this.sessions = sessions;
        this.audit = audit;
        this.clock = clock;
    }

    public boolean emailAvailable() {
        return mailer.available();
    }

    /** Sends a reset link if the account exists. Same outcome either way, apart from rate limiting. */
    @Transactional
    public void request(String email, String clientIp) {
        String normalised = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        if (!limiter.tryAcquire("reset-ip|" + clientIp, 10, Duration.ofHours(1))
                || !limiter.tryAcquire("reset-email|" + normalised, 3, Duration.ofHours(1))) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "Too many reset requests. Try again in an hour.");
        }
        User user = users.findByEmailIgnoreCase(normalised).orElse(null);
        if (user == null || user.isDemo()) return;

        Instant now = clock.instant();
        // a new link replaces any earlier unused one
        jdbc.update("UPDATE password_reset_tokens SET used_at = :now WHERE user_id = :u AND used_at IS NULL",
                new MapSqlParameterSource().addValue("u", user.getId()).addValue("now", java.sql.Timestamp.from(now)));
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        jdbc.update("""
                INSERT INTO password_reset_tokens (id, user_id, token_hash, expires_at, requested_ip, created_at)
                VALUES (:id, :u, :hash, :expires, :ip, :now)
                """, new MapSqlParameterSource().addValue("id", UUID.randomUUID()).addValue("u", user.getId())
                .addValue("hash", Hashing.sha256Hex(token)).addValue("expires", java.sql.Timestamp.from(now.plus(LIFETIME)))
                .addValue("ip", clientIp == null ? null : clientIp.substring(0, Math.min(64, clientIp.length())))
                .addValue("now", java.sql.Timestamp.from(now)));
        audit.record(user.getId(), "PASSWORD_RESET_REQUESTED", "user", user.getId(), null);

        String link = mailer.appUrl().replaceAll("/+$", "") + "/reset-password?token=" + token;
        String body = "Hi " + user.getDisplayName() + ",\n\n"
                + "Someone (hopefully you) asked to reset your Arth password. Open this link within 30 minutes to choose a new one:\n\n"
                + link + "\n\n"
                + "If you didn't ask for this, ignore this e-mail. Your password stays the same.";
        if (!mailer.sendAccountEmail(user.getEmail(), "reset your password", body)) {
            // No e-mail server configured (typical for a local or personal install): the operator can pass the link on.
            log.warn("E-mail isn't configured, so this password reset link was not sent. Give it to the account owner only: {}", link);
        }
    }

    /** Sets the new password if the link is valid, then signs out every session. */
    @Transactional
    public void reset(String token, String newPassword) {
        if (token == null || token.isBlank()) throw invalid();
        List<UUID> found = jdbc.queryForList("""
                SELECT user_id FROM password_reset_tokens
                WHERE token_hash = :hash AND used_at IS NULL AND expires_at > :now
                """, new MapSqlParameterSource().addValue("hash", Hashing.sha256Hex(token.trim()))
                .addValue("now", java.sql.Timestamp.from(clock.instant())), UUID.class);
        if (found.isEmpty()) throw invalid();
        AuthService.validatePassword(newPassword);
        User user = users.findById(found.get(0)).orElseThrow(PasswordResetService::invalid);
        user.setPasswordHash(encoder.encode(newPassword));
        sessions.revokeAll(user);
        jdbc.update("UPDATE password_reset_tokens SET used_at = :now WHERE user_id = :u AND used_at IS NULL",
                new MapSqlParameterSource().addValue("u", user.getId()).addValue("now", java.sql.Timestamp.from(clock.instant())));
        limiter.reset("reset-email|" + user.getEmail());
        audit.record(user.getId(), "PASSWORD_RESET", "user", user.getId(), null);
    }

    private static ApiException invalid() {
        return new ApiException(HttpStatus.BAD_REQUEST, "RESET_LINK_INVALID", "This reset link is invalid or has expired. Ask for a new one.");
    }
}
