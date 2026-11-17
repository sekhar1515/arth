package com.expensetracker.auth.mfa;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class MfaTest {

    private static final byte[] RFC_SECRET = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

    @Test
    void rfc6238Vectors() {
        // RFC 6238 appendix B (SHA1, 8 digits): T=59 → 94287082, T=1111111109 → 07081804
        assertEquals("94287082", Totp.code(RFC_SECRET, 59 / 30, 8));
        assertEquals("07081804", Totp.code(RFC_SECRET, 1111111109L / 30, 8));
        assertEquals("287082", Totp.code(RFC_SECRET, 1, 6));
    }

    @Test
    void verifyAllowsOneStepDrift() {
        Instant now = Instant.ofEpochSecond(1_700_000_000L);
        String code = Totp.code(RFC_SECRET, now.getEpochSecond() / 30 - 1, 6);
        assertTrue(Totp.verify(RFC_SECRET, code, now));
        assertFalse(Totp.verify(RFC_SECRET, Totp.code(RFC_SECRET, now.getEpochSecond() / 30 - 3, 6), now));
        assertFalse(Totp.verify(RFC_SECRET, "abc", now));
    }

    @Test
    void base32RoundTripAndCipher() {
        assertEquals("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", Base32.encode(RFC_SECRET));
        assertArrayEquals(RFC_SECRET, Base32.decode("gezd gnbv gy3t qojq gezd gnbv gy3t qojq"));
        SecretCipher c = new SecretCipher("a-long-passphrase-for-tests");
        String enc = c.encrypt(RFC_SECRET);
        assertTrue(enc.startsWith("v1:"));
        assertArrayEquals(RFC_SECRET, c.decrypt(enc));
        assertThrows(IllegalStateException.class, () -> new SecretCipher("another-long-passphrase").decrypt(enc));
        assertTrue(Totp.otpauthUri("Arth", "me@example.com", RFC_SECRET).startsWith("otpauth://totp/Arth%3Ame%40example.com?secret=GEZD"));
        assertTrue(Totp.recoveryCode().matches("[a-z2-9]{4}-[a-z2-9]{4}"));
    }

    private static void assertArrayEquals(byte[] a, byte[] b) {
        assertTrue(java.util.Arrays.equals(a, b), "arrays differ");
    }
}
