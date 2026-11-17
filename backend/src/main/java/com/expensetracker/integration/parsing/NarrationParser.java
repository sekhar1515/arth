package com.expensetracker.integration.parsing;

import com.expensetracker.transaction.PaymentMethod;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts payment method, counterparty, reference and VPA from Indian bank statement narrations, e.g.
 * <pre>
 *   UPI/DR/412345678901/SWIGGY/YESB/swiggy@yesbank/Payment
 *   UPI-SWIGGY-SWIGGY@ICICI-ICIC0DC0099-412345678901-ORDER
 *   NEFT CR-HDFC0000001-ACME TECHNOLOGIES PVT LTD-SALARY NOV
 *   IMPS/P2A/412345678901/RAHUL KUMAR/SBIN
 *   POS 416021XXXXXX1234 AMAZON PAY INDIA
 *   ATM WDL/CASH/412345
 * </pre>
 * Pure JDK so it can be unit tested without Spring.
 */
public final class NarrationParser {

    /**
     * @param remark the note the payer typed in the UPI app ("groceries", "rent nov"), when the bank kept it in the narration
     */
    public record ParsedNarration(PaymentMethod method, String counterparty, String reference, String vpa, String remark) {
    }

    private static final java.util.Set<String> BANK_CODES = java.util.Set.of(
            "SBIN", "YESB", "UTIB", "HDFC", "ICIC", "KKBK", "PUNB", "BARB", "CNRB", "IDIB", "UBIN", "INDB", "IDFB", "AIRP", "PYTM",
            "FDRL", "IOBA", "MAHB", "BKID", "CBIN", "SCBL", "CITI", "HSBC", "RATN", "AUBL", "DBSS", "ESFB", "JIOP", "KARB", "PSIB",
            "UCBA", "SIBL", "TMBL", "AXIS", "YBL", "IBL", "AXL", "OKSBI", "OKAXIS", "OKICICI", "OKHDFCBANK");
    private static final Pattern GENERIC_REMARK = Pattern.compile(
            "^(UPI|DR|CR|NA|N A|NIL|NONE|P2P|P2M|P2A|PAY|PAID|PAYMENT|PAYMENTS|ORDER|COLLECT|TRANSFER|SENT|RECEIVED|REQUEST|UPI INTENT|"
                    + "QR|PAYTMQR|NO REMARKS?|REMARKS?|PAYMENT FROM PH.*|PAYMENT FROM PHONEPE|PAYMENT TO .*|SENT USING .*|SENT VIA .*|"
                    + "PAID VIA .*|PAID USING .*|PAY TO .*|UPI PAYMENT|BHIM.*|GPAY.*|GOOGLE PAY.*|PHONEPE.*|PAYTM.*|CRED.*|MOB.*|INB|IB|MB)$");
    private static final Pattern VPA = Pattern.compile("([A-Za-z0-9._\\-]{2,64}@[A-Za-z][A-Za-z0-9]{1,30})");
    private static final Pattern REF12 = Pattern.compile("(?<![0-9])([0-9]{12})(?![0-9])");
    private static final Pattern REF_GENERIC = Pattern.compile("(?<![A-Z0-9])((?=[A-Z]{4}[A-Z0-9]*[0-9])[A-Z]{4}[A-Z0-9]{8,18}|[0-9]{9,22})(?![A-Z0-9])");
    private static final Pattern IFSC = Pattern.compile("^[A-Z]{4}0[A-Z0-9]{6}$");
    private static final Pattern NOISE_TOKEN = Pattern.compile(
            "^(UPI|DR|CR|P2A|P2M|P2P|NEFT|IMPS|RTGS|PAYMENT|PAY|ORDER|SENT|RECEIVED|FROM|TO|TXN|TRANSFER|NA|INB|MOB|MB|IB|BIL|ONL|POS|ECOM|REF|OK|COLLECT|REQUEST|PAYMENT FROM PHONE|[A-Z]{4}|[0-9X*]+)$");

    private NarrationParser() {
    }

    public static ParsedNarration parse(String narration) {
        if (narration == null || narration.isBlank()) {
            return new ParsedNarration(PaymentMethod.OTHER, null, null, null, null);
        }
        String text = narration.trim().replaceAll("\\s+", " ");
        String upper = text.toUpperCase(Locale.ROOT);
        PaymentMethod method = detectMethod(upper);
        String vpa = firstGroup(VPA, text);
        String reference = method == PaymentMethod.UPI || method == PaymentMethod.IMPS
                ? firstGroup(REF12, upper) : null;
        if (reference == null && (method == PaymentMethod.NEFT || method == PaymentMethod.RTGS || method == PaymentMethod.IMPS)) {
            reference = findGenericReference(upper);
        }
        String counterparty = switch (method) {
            case ATM -> "ATM Cash Withdrawal";
            case CARD -> cardMerchant(upper);
            default -> counterpartyFromTokens(text, vpa);
        };
        String remark = method == PaymentMethod.UPI ? upiRemark(text, reference, counterparty) : null;
        if (remark != null && remark.equalsIgnoreCase(counterparty)) {
            counterparty = vpa != null ? vpa.substring(0, vpa.indexOf('@')) : null;
        }
        return new ParsedNarration(method, counterparty, reference, vpa, remark);
    }

    /**
     * Finds the payer's note in a UPI narration. Banks place it differently:
     * <pre>
     *   SBI    UPI/DR/412345678901/RAMESH K/SBIN/rameshk@oksbi/groceries
     *   HDFC   UPI-RAMESH K-RAMESHK@OKSBI-SBIN0001234-412345678901-groceries
     *   ICICI  UPI/412345678901/groceries/rameshk@oksbi/State Bank Of I/ICI1a2b3c
     * </pre>
     * Generic app texts ("Payment from Phone", "Sent using Paytm") are not notes.
     */
    static String upiRemark(String text, String reference, String counterparty) {
        String[] raw = text.split(text.indexOf('/') >= 0 ? "/" : "-");
        java.util.List<String> tokens = new java.util.ArrayList<>();
        for (String t : raw) {
            String s = t.trim();
            if (!s.isEmpty()) tokens.add(s);
        }
        int vpaIdx = -1;
        for (int i = 0; i < tokens.size(); i++) {
            if (tokens.get(i).contains("@")) {
                vpaIdx = i;
                break;
            }
        }
        if (vpaIdx >= 0) {
            java.util.List<String> after = new java.util.ArrayList<>();
            for (int i = vpaIdx + 1; i < tokens.size(); i++) {
                if (usableRemark(tokens.get(i), counterparty)) after.add(tokens.get(i));
            }
            if (!after.isEmpty()) return clip(String.join(" ", after));
        }
        if (reference != null) {
            for (int i = 0; i < tokens.size() - 1; i++) {
                String digits = tokens.get(i).replaceFirst("^0+", "");
                if (digits.equals(reference.replaceFirst("^0+", ""))) {
                    String next = tokens.get(i + 1);
                    // ICICI style: the note sits between the reference and the VPA (no payee name in between)
                    boolean iciciLayout = i == 1 && tokens.get(0).toUpperCase(Locale.ROOT).startsWith("UPI") && vpaIdx == i + 2;
                    if (iciciLayout && usableRemark(next, null)) return clip(next);
                    break;
                }
            }
        }
        return null;
    }

    private static boolean usableRemark(String token, String counterparty) {
        String up = token.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9 ]", " ").replaceAll("\\s+", " ").trim();
        if (up.length() < 2) return false;
        if (token.contains("@")) return false;
        if (IFSC.matcher(up).matches() || BANK_CODES.contains(up)) return false;
        if (up.matches(".*[0-9]{5,}.*") || up.matches("[0-9 ]+")) return false;
        if (up.contains("BANK") || up.startsWith("STATE BANK") || up.matches("ICI[A-Z0-9]*") && up.matches(".*[0-9].*")) return false;
        if (GENERIC_REMARK.matcher(up).matches()) return false;
        if (counterparty != null && up.equals(counterparty.toUpperCase(Locale.ROOT).trim())) return false;
        return true;
    }

    private static String clip(String s) {
        String t = s.trim();
        return t.length() > 100 ? t.substring(0, 100) : t;
    }

    static PaymentMethod detectMethod(String upper) {
        if (upper.startsWith("UPI") || upper.contains("/UPI/") || upper.contains(" UPI ") || upper.contains("UPI/")) return PaymentMethod.UPI;
        if (upper.startsWith("IMPS") || upper.contains("IMPS/") || upper.contains("MMT/IMPS")) return PaymentMethod.IMPS;
        if (upper.startsWith("NEFT") || upper.contains("NEFT/") || upper.contains("NEFT-")) return PaymentMethod.NEFT;
        if (upper.startsWith("RTGS") || upper.contains("RTGS/") || upper.contains("RTGS-")) return PaymentMethod.RTGS;
        if (upper.contains("ATM WDL") || upper.contains("ATW") || upper.contains("NWD") || upper.contains("CASH WITHDRAWAL") || upper.startsWith("ATM")) return PaymentMethod.ATM;
        if (upper.startsWith("POS") || upper.contains("ECOM") || upper.contains(" PCD ") || upper.startsWith("PCD") || upper.contains("DEBIT CARD")) return PaymentMethod.CARD;
        if (upper.startsWith("ACH") || upper.contains("NACH") || upper.contains("ECS")) return PaymentMethod.NACH;
        if (upper.startsWith("CHQ") || upper.contains("CHEQUE") || upper.startsWith("CLG")) return PaymentMethod.CHEQUE;
        return PaymentMethod.OTHER;
    }

    private static String cardMerchant(String upper) {
        // POS 416021XXXXXX1234 AMAZON PAY INDIA  -> AMAZON PAY INDIA
        String cleaned = upper.replaceFirst("^(POS|ECOM|PCD)\\s*", "")
                .replaceAll("[0-9]{4,}[X*]+[0-9]{2,4}", " ")
                .replaceAll("\\b[0-9]{6,}\\b", " ")
                .replaceAll("\\s+", " ").trim();
        return cleaned.isEmpty() ? null : cleaned;
    }

    private static String counterpartyFromTokens(String text, String vpa) {
        String[] tokens = text.split("[/\\-|:]");
        String best = null;
        for (String raw : tokens) {
            String token = raw.trim();
            if (token.isEmpty()) continue;
            String up = token.toUpperCase(Locale.ROOT);
            if (vpa != null && token.equalsIgnoreCase(vpa)) continue;
            if (token.contains("@")) continue;
            if (IFSC.matcher(up).matches()) continue;
            if (NOISE_TOKEN.matcher(up).matches() || allWordsNoise(up)) continue;
            if (up.matches(".*[0-9]{5,}.*")) continue;
            if (up.length() < 3) continue;
            best = token;
            break;
        }
        if (best == null && vpa != null) {
            best = vpa.substring(0, vpa.indexOf('@'));
        }
        return best;
    }

    private static boolean allWordsNoise(String upperToken) {
        for (String w : upperToken.split(" ")) {
            if (!w.isEmpty() && !NOISE_TOKEN.matcher(w).matches()) return false;
        }
        return true;
    }

    private static String findGenericReference(String upper) {
        Matcher m = REF_GENERIC.matcher(upper);
        while (m.find()) {
            String candidate = m.group(1);
            if (IFSC.matcher(candidate).matches()) continue;
            return candidate;
        }
        return null;
    }

    private static String firstGroup(Pattern p, String text) {
        Matcher m = p.matcher(text);
        return m.find() ? m.group(1) : null;
    }
}
