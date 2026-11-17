package com.expensetracker.auth.mfa;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;

/** RFC 6238 time-based one-time passwords (HMAC-SHA1, 6 digits, 30 s), accepting one step of clock drift. */
public final class Totp {
    private static final SecureRandom RANDOM = new SecureRandom();
    public static final int STEP_SECONDS = 30;

    private Totp() {
    }

    public static byte[] newSecret() {
        byte[] b = new byte[20];
        RANDOM.nextBytes(b);
        return b;
    }

    public static String code(byte[] secret, long counter, int digits) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret, "HmacSHA1"));
            byte[] h = mac.doFinal(ByteBuffer.allocate(8).putLong(counter).array());
            int o = h[h.length - 1] & 0x0f;
            int bin = ((h[o] & 0x7f) << 24) | ((h[o + 1] & 0xff) << 16) | ((h[o + 2] & 0xff) << 8) | (h[o + 3] & 0xff);
            int mod = (int) Math.pow(10, digits);
            return String.format("%0" + digits + "d", bin % mod);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static boolean verify(byte[] secret, String code, Instant now) {
        if (code == null) return false;
        String c = code.replace(" ", "").trim();
        if (!c.matches("\\d{6}")) return false;
        long counter = now.getEpochSecond() / STEP_SECONDS;
        for (long d = -1; d <= 1; d++) {
            if (MessageDigest.isEqual(code(secret, counter + d, 6).getBytes(StandardCharsets.US_ASCII), c.getBytes(StandardCharsets.US_ASCII))) return true;
        }
        return false;
    }

    public static String otpauthUri(String issuer, String account, byte[] secret) {
        String label = URLEncoder.encode(issuer + ":" + account, StandardCharsets.UTF_8).replace("+", "%20");
        return "otpauth://totp/" + label + "?secret=" + Base32.encode(secret) + "&issuer=" + URLEncoder.encode(issuer, StandardCharsets.UTF_8)
                + "&algorithm=SHA1&digits=6&period=" + STEP_SECONDS;
    }

    /** Recovery codes like "k7q2-9xmd" (lowercase letters and digits without look-alikes). */
    public static String recoveryCode() {
        String alphabet = "abcdefghjkmnpqrstuvwxyz23456789";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            if (i == 4) sb.append('-');
            sb.append(alphabet.charAt(RANDOM.nextInt(alphabet.length())));
        }
        return sb.toString();
    }
}
