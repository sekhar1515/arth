package com.expensetracker.auth.mfa;

import com.expensetracker.audit.AuditService;
import com.expensetracker.common.ApiException;
import com.expensetracker.common.Hashing;
import com.expensetracker.user.User;
import com.expensetracker.user.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Optional two-factor sign-in with an authenticator app (TOTP). Setup stores an encrypted secret that only becomes
 * active after you confirm a code. Ten one-time recovery codes are shown once; only their hashes are stored.
 */
@Service
public class MfaService {

    public record SetupDto(String secret, String otpauthUri) {
    }

    public record StatusDto(boolean enabled, int recoveryCodesLeft) {
    }

    private final UserRepository users;
    private final SecretCipher cipher;
    private final PasswordEncoder encoder;
    private final AuditService audit;
    private final Clock clock;

    public MfaService(UserRepository users, PasswordEncoder encoder, AuditService audit, Clock clock,
                      @Value("${app.security.mfa-key:}") String mfaKey, @Value("${app.security.jwt-secret}") String jwtSecret) {
        this.users = users;
        this.encoder = encoder;
        this.audit = audit;
        this.clock = clock;
        // a dedicated key is recommended; without one the key is derived from the JWT secret (so rotating that secret disables MFA)
        this.cipher = new SecretCipher(mfaKey != null && !mfaKey.isBlank() ? mfaKey : "jwt:" + jwtSecret);
    }

    @Transactional(readOnly = true)
    public StatusDto status(UUID userId) {
        User u = user(userId);
        return new StatusDto(u.isMfaEnabled(), u.getMfaRecoveryHashes() == null ? 0 : (int) Arrays.stream(u.getMfaRecoveryHashes().split(",")).filter(s -> !s.isBlank()).count());
    }

    @Transactional
    public SetupDto begin(UUID userId) {
        User u = user(userId);
        if (u.isDemo()) throw ApiException.badRequest("Two-factor sign-in isn't available on the demo account");
        if (u.isMfaEnabled()) throw ApiException.conflict("Two-factor sign-in is already on. Turn it off first to set it up again.");
        byte[] secret = Totp.newSecret();
        u.setMfaSecretEnc(cipher.encrypt(secret));
        return new SetupDto(Base32.encode(secret), Totp.otpauthUri("Arth", u.getEmail(), secret));
    }

    /** Confirms setup with a code from the app and returns the recovery codes (shown once). */
    @Transactional
    public List<String> enable(UUID userId, String code) {
        User u = user(userId);
        if (u.isMfaEnabled()) throw ApiException.conflict("Two-factor sign-in is already on");
        if (u.getMfaSecretEnc() == null) throw ApiException.badRequest("Start setup first");
        if (!Totp.verify(cipher.decrypt(u.getMfaSecretEnc()), code, Instant.now(clock))) throw ApiException.badRequest("That code didn't match. Check the time on your phone and try the newest code.");
        List<String> codes = new ArrayList<>();
        List<String> hashes = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            String c = Totp.recoveryCode();
            codes.add(c);
            hashes.add(Hashing.sha256Hex("arth-recovery:" + c));
        }
        u.setMfaRecoveryHashes(String.join(",", hashes));
        u.setMfaEnabled(true);
        audit.record(userId, "MFA_ENABLED", "user", userId, null);
        return codes;
    }

    @Transactional
    public void disable(UUID userId, String password, String code) {
        User u = user(userId);
        if (!u.isMfaEnabled()) return;
        if (password == null || !encoder.matches(password, u.getPasswordHash())) throw ApiException.badRequest("Password is incorrect");
        if (!verify(u, code)) throw ApiException.badRequest("Enter a current code from your app or a recovery code");
        u.setMfaEnabled(false);
        u.setMfaSecretEnc(null);
        u.setMfaRecoveryHashes(null);
        audit.record(userId, "MFA_DISABLED", "user", userId, null);
    }

    /** Checks a TOTP code, or consumes a recovery code. Caller must be in a transaction for recovery-code use to stick. */
    public boolean verify(User u, String code) {
        if (!u.isMfaEnabled() || code == null) return false;
        String c = code.trim().toLowerCase();
        if (c.matches("\\d{3} ?\\d{3}")) return Totp.verify(cipher.decrypt(u.getMfaSecretEnc()), c.replace(" ", ""), Instant.now(clock));
        if (u.getMfaRecoveryHashes() == null) return false;
        String h = Hashing.sha256Hex("arth-recovery:" + c);
        List<String> left = new ArrayList<>(Arrays.asList(u.getMfaRecoveryHashes().split(",")));
        if (!left.remove(h)) return false;
        u.setMfaRecoveryHashes(String.join(",", left));
        audit.record(u.getId(), "MFA_RECOVERY_CODE_USED", "user", u.getId(), left.size() + " left");
        return true;
    }

    private User user(UUID id) {
        return users.findById(id).orElseThrow(() -> ApiException.notFound("User"));
    }
}
