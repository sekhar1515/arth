package com.expensetracker.auth.mfa;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/** AES-256-GCM for small secrets at rest (the TOTP seed). Output: base64(iv || ciphertext+tag), prefixed "v1:". */
public final class SecretCipher {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final SecretKeySpec key;

    /** @param keyMaterial a base64 32-byte key, or any passphrase (hashed with SHA-256) */
    public SecretCipher(String keyMaterial) {
        if (keyMaterial == null || keyMaterial.length() < 16) throw new IllegalArgumentException("Encryption key must be at least 16 characters");
        byte[] k;
        try {
            byte[] decoded = Base64.getDecoder().decode(keyMaterial);
            k = decoded.length == 32 ? decoded : sha256(keyMaterial);
        } catch (IllegalArgumentException e) {
            k = sha256(keyMaterial);
        }
        this.key = new SecretKeySpec(k, "AES");
    }

    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(("arth-mfa:" + s).getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public String encrypt(byte[] plain) {
        try {
            byte[] iv = new byte[12];
            RANDOM.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] ct = c.doFinal(plain);
            return "v1:" + Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + ct.length).put(iv).put(ct).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public byte[] decrypt(String token) {
        if (token == null || !token.startsWith("v1:")) throw new IllegalArgumentException("Unknown secret format");
        try {
            byte[] all = Base64.getDecoder().decode(token.substring(3));
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, all, 0, 12));
            return c.doFinal(all, 12, all.length - 12);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not decrypt the two-factor secret. Was MFA_ENCRYPTION_KEY changed?", e);
        }
    }
}
