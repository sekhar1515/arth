package com.expensetracker.auth.mfa;

import java.io.ByteArrayOutputStream;
import java.util.Locale;

/** RFC 4648 base32 without padding, as used by authenticator apps. */
public final class Base32 {
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private Base32() {
    }

    public static String encode(byte[] data) {
        StringBuilder sb = new StringBuilder();
        int buffer = 0, bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                sb.append(ALPHABET.charAt((buffer >> (bits - 5)) & 31));
                bits -= 5;
            }
        }
        if (bits > 0) sb.append(ALPHABET.charAt((buffer << (5 - bits)) & 31));
        return sb.toString();
    }

    public static byte[] decode(String s) {
        String in = s.replace(" ", "").replace("=", "").toUpperCase(Locale.ROOT);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int buffer = 0, bits = 0;
        for (char ch : in.toCharArray()) {
            int v = ALPHABET.indexOf(ch);
            if (v < 0) throw new IllegalArgumentException("Not base32: " + ch);
            buffer = (buffer << 5) | v;
            bits += 5;
            if (bits >= 8) {
                out.write((buffer >> (bits - 8)) & 0xff);
                bits -= 8;
            }
        }
        return out.toByteArray();
    }
}
