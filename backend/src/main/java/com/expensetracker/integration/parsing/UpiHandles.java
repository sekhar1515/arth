package com.expensetracker.integration.parsing;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Maps the PSP handle of a UPI VPA (the part after '@') to the consumer app that issued it.
 * This is a heuristic: it is only meaningful for the *user's own* VPA, and only when an alert includes it.
 */
public final class UpiHandles {

    private static final Map<String, String> HANDLE_TO_APP = new LinkedHashMap<>();

    static {
        for (String h : new String[]{"ybl", "ibl", "axl"}) HANDLE_TO_APP.put(h, "PhonePe");
        for (String h : new String[]{"okaxis", "okhdfcbank", "okicici", "oksbi"}) HANDLE_TO_APP.put(h, "Google Pay");
        for (String h : new String[]{"paytm", "ptyes", "ptaxis", "pthdfc", "ptsbi"}) HANDLE_TO_APP.put(h, "Paytm");
        HANDLE_TO_APP.put("upi", "BHIM");
        HANDLE_TO_APP.put("apl", "Amazon Pay");
        HANDLE_TO_APP.put("yapl", "Amazon Pay");
        HANDLE_TO_APP.put("rapl", "Amazon Pay");
        HANDLE_TO_APP.put("waicici", "WhatsApp Pay");
        HANDLE_TO_APP.put("wahdfcbank", "WhatsApp Pay");
        HANDLE_TO_APP.put("cnrb", "Canara ai1");
        HANDLE_TO_APP.put("superyes", "super.money"); // Flipkart's super.money (Yes Bank)
    }

    private UpiHandles() {
    }

    public static Optional<String> appForVpa(String vpa) {
        if (vpa == null) return Optional.empty();
        int at = vpa.indexOf('@');
        if (at < 0 || at == vpa.length() - 1) return Optional.empty();
        String handle = vpa.substring(at + 1).toLowerCase(Locale.ROOT).trim();
        return Optional.ofNullable(HANDLE_TO_APP.get(handle));
    }
}
