package com.expensetracker.auth;

import com.expensetracker.account.AccountService;
import com.expensetracker.audit.AuditService;
import com.expensetracker.category.CategoryService;
import com.expensetracker.common.ApiException;
import com.expensetracker.user.User;
import com.expensetracker.user.UserPreferences;
import com.expensetracker.user.UserPreferencesRepository;
import com.expensetracker.user.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

@Service
public class AuthService {

    public record UserDto(UUID id, String email, String displayName, boolean demo, boolean mfaEnabled) {
        public static UserDto of(User u) {
            return new UserDto(u.getId(), u.getEmail(), u.getDisplayName(), u.isDemo(), u.isMfaEnabled());
        }
    }

    private static final int MAX_LOGIN_ATTEMPTS = 8;
    private static final Duration LOGIN_WINDOW = Duration.ofMinutes(15);
    /** Pre-computed hash used to keep timing similar when the e-mail does not exist. */
    private static final String DUMMY_HASH = "$2a$12$J2ERb9vtrWApnOX7O6MYbOWLPY8Gfx89NgvAvfuA1KwVttVvxEJV6";

    private final UserRepository users;
    private final UserPreferencesRepository preferences;
    private final CategoryService categories;
    private final AccountService accounts;
    private final PasswordEncoder encoder;
    private final LoginRateLimiter limiter;
    private final AuditService audit;
    private final com.expensetracker.auth.mfa.MfaService mfa;

    public AuthService(UserRepository users, UserPreferencesRepository preferences, CategoryService categories,
                       AccountService accounts, PasswordEncoder encoder, LoginRateLimiter limiter, AuditService audit,
                       com.expensetracker.auth.mfa.MfaService mfa) {
        this.users = users;
        this.preferences = preferences;
        this.categories = categories;
        this.accounts = accounts;
        this.encoder = encoder;
        this.limiter = limiter;
        this.audit = audit;
        this.mfa = mfa;
    }

    @Transactional
    public User register(String email, String password, String displayName) {
        return createUser(email, password, displayName, false);
    }

    @Transactional
    public User createUser(String email, String password, String displayName, boolean demo) {
        String normalised = email.trim().toLowerCase(Locale.ROOT);
        if (users.existsByEmailIgnoreCase(normalised)) {
            throw ApiException.conflict("An account with this e-mail already exists");
        }
        validatePassword(password);
        User user = users.save(new User(normalised, encoder.encode(password), displayName.trim(), demo));
        preferences.save(new UserPreferences(user.getId()));
        categories.seedDefaults(user.getId());
        accounts.createCashAccount(user.getId());
        audit.record(user.getId(), "USER_REGISTERED", "user", user.getId(), null);
        return user;
    }

    @Transactional(noRollbackFor = ApiException.class)
    public User login(String email, String password, String code, String clientIp) {
        String normalised = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        String key = clientIp + "|" + normalised;
        if (!limiter.tryAcquire(key, MAX_LOGIN_ATTEMPTS, LOGIN_WINDOW)) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "Too many sign-in attempts. Try again in 15 minutes.");
        }
        User user = users.findByEmailIgnoreCase(normalised).orElse(null);
        boolean ok = encoder.matches(password == null ? "" : password, user != null ? user.getPasswordHash() : DUMMY_HASH);
        if (user == null || !ok) {
            audit.record(user != null ? user.getId() : null, "LOGIN_FAILED", "user", null, normalised);
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "E-mail or password is incorrect");
        }
        if (user.isMfaEnabled()) {
            if (code == null || code.isBlank()) {
                throw new ApiException(HttpStatus.UNAUTHORIZED, "MFA_REQUIRED", "Enter the 6-digit code from your authenticator app");
            }
            if (!mfa.verify(user, code)) {
                audit.record(user.getId(), "LOGIN_FAILED_MFA", "user", user.getId(), null);
                throw new ApiException(HttpStatus.UNAUTHORIZED, "MFA_INVALID", "That code didn't match. Try the newest code, or a recovery code.");
            }
        }
        limiter.reset(key);
        user.setLastLoginAt(Instant.now());
        audit.record(user.getId(), "LOGIN", "user", user.getId(), null);
        return user;
    }

    @Transactional
    public void changePassword(UUID userId, String current, String next) {
        User user = users.findById(userId).orElseThrow(() -> ApiException.notFound("User"));
        if (user.isDemo()) throw ApiException.badRequest("The demo account's password can't be changed");
        if (current == null || !encoder.matches(current, user.getPasswordHash())) throw ApiException.badRequest("Current password is incorrect");
        validatePassword(next);
        user.setPasswordHash(encoder.encode(next));
        audit.record(userId, "PASSWORD_CHANGED", "user", userId, null);
    }

    @Transactional(readOnly = true)
    public User get(UUID id) {
        return users.findById(id).orElseThrow(() -> ApiException.notFound("User"));
    }

    static void validatePassword(String password) {
        if (password == null || password.length() < 10) {
            throw ApiException.badRequest("Password must be at least 10 characters");
        }
        if (password.length() > 72) {
            throw ApiException.badRequest("Password must be at most 72 characters");
        }
        boolean letter = password.chars().anyMatch(Character::isLetter);
        boolean digit = password.chars().anyMatch(Character::isDigit);
        if (!letter || !digit) {
            throw ApiException.badRequest("Password must contain letters and numbers");
        }
    }
}
