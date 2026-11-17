package com.expensetracker.integration.parsing;

import com.expensetracker.transaction.Direction;
import com.expensetracker.transaction.PaymentMethod;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses Indian bank / card transaction alerts (SMS or e-mail text) into a structured {@link ParsedAlert}.
 * Rejects OTP / verification messages outright so they are never stored.
 */
public final class AlertParser {

    public record ParsedAlert(
            Direction direction,
            BigDecimal amount,
            String accountLast4,
            LocalDate date,
            LocalTime time,
            String counterparty,
            String vpa,
            String reference,
            PaymentMethod method,
            String paymentProvider,
            boolean failed) {
    }

    public sealed interface Result permits Parsed, Rejected {
    }

    public record Parsed(ParsedAlert alert) implements Result {
    }

    public record Rejected(String reason) implements Result {
    }

    private static final Pattern OTP = Pattern.compile(
            "\\b(OTP|one[ -]?time[ -]?password|verification code|passcode|do not share|never share)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern AMOUNT = Pattern.compile(
            "(?:Rs\\.?|INR|₹)\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)", Pattern.CASE_INSENSITIVE);
    private static final Pattern DEBIT_WORDS = Pattern.compile(
            "\\b(debited|debit|spent|sent|paid|withdrawn|withdrawal|purchase|deducted|transferred to|payment of)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CREDIT_WORDS = Pattern.compile(
            "\\b(credited|credit(?!\\s*card)|received|deposited|refund(?:ed)?|reversed|added to)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern FAILED_WORDS = Pattern.compile(
            "\\b(failed|declined|unsuccessful|could not be processed)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern ACCOUNT = Pattern.compile(
            "(?:A/?c|Acct|Account|Card)(?:\\s*(?:no\\.?|number|ending(?:\\s*with)?))?\\s*[:\\s]*(?:XX|xx|X+|\\*+|x+)?\\s*([0-9]{3,4})\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern VPA = Pattern.compile("([A-Za-z0-9._\\-]{2,64}@[A-Za-z][A-Za-z0-9]{1,30})");
    private static final Pattern REFERENCE = Pattern.compile(
            "(?:UPI\\s*Ref(?:erence)?\\.?\\s*(?:No\\.?|Number)?|Ref(?:erence)?\\.?\\s*(?:No\\.?|Number|ID)?|RRN|UTR|Txn\\s*(?:ID|No\\.?))\\s*[:#.\\-]?\\s*([A-Za-z0-9]{6,24})",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern COUNTERPARTY = Pattern.compile(
            "\\b(?:to|at|towards|by|from|Info:?)\\s+(?:VPA\\s+)?([A-Za-z0-9@._&'*\\- ]{2,60}?)(?=\\s+(?:on|ref|upi|via|using|avl|avbl|bal|is|has|for|thru|through|with)\\b|\\s*[(.,;]|\\s*$)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern DATE_TOKEN = Pattern.compile(
            "\\b([0-9]{1,2}[-/ ](?:[0-9]{1,2}|[A-Za-z]{3})[-/ ](?:[0-9]{4}|[0-9]{2})|[0-9]{4}-[0-9]{2}-[0-9]{2})\\b");
    private static final Pattern TIME_TOKEN = Pattern.compile("\\b([01]?[0-9]|2[0-3]):([0-5][0-9])(?::([0-5][0-9]))?\\b");
    private static final Pattern WORD_SELF = Pattern.compile(
            "^(your|you|a/?c|account|card|bank|the|self|xx[0-9]+|[0-9]+)$", Pattern.CASE_INSENSITIVE);

    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            fmt("d-M-uuuu"), fmt("d/M/uuuu"), fmt("d-M-uu"), fmt("d/M/uu"),
            fmt("d-MMM-uuuu"), fmt("d-MMM-uu"), fmt("d MMM uuuu"), fmt("d MMM uu"), fmt("d/MMM/uu"),
            fmt("uuuu-MM-dd"));

    private AlertParser() {
    }

    private static DateTimeFormatter fmt(String pattern) {
        return new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(pattern).toFormatter(Locale.ENGLISH)
                .withResolverStyle(java.time.format.ResolverStyle.STRICT);
    }

    public static Result parse(String text) {
        if (text == null || text.isBlank()) return new Rejected("empty message");
        String msg = text.replaceAll("\\s+", " ").trim();
        if (OTP.matcher(msg).find()) return new Rejected("looks like an OTP or verification message; not stored");

        Matcher amountMatcher = AMOUNT.matcher(msg);
        if (!amountMatcher.find()) return new Rejected("no amount found");
        BigDecimal amount = new BigDecimal(amountMatcher.group(1).replace(",", ""));
        if (amount.signum() <= 0) return new Rejected("amount must be positive");

        Direction direction = detectDirection(msg, amountMatcher.start());
        if (direction == null) return new Rejected("could not tell whether this is a debit or a credit");

        String vpa = group(VPA, msg);
        String upper = msg.toUpperCase(Locale.ROOT);
        PaymentMethod method = detectMethod(upper, vpa);
        String reference = group(REFERENCE, msg);
        if (reference != null && reference.matches("(?i)no|number|id")) reference = null;
        String counterparty = findCounterparty(msg, vpa);
        LocalDate date = findDate(msg);
        LocalTime time = findTime(msg);
        String provider = UpiHandles.appForVpa(vpa).orElse(null);
        if (provider != null && direction == Direction.DEBIT) {
            // In debit alerts the VPA shown is normally the *payee*; it does not tell us which app the user paid with.
            provider = null;
        }
        boolean failed = FAILED_WORDS.matcher(msg).find();
        return new Parsed(new ParsedAlert(direction, amount.setScale(2), group(ACCOUNT, msg), date, time,
                counterparty, vpa, reference, method, provider, failed));
    }

    private static Direction detectDirection(String msg, int amountPos) {
        Matcher d = DEBIT_WORDS.matcher(msg);
        Matcher c = CREDIT_WORDS.matcher(msg);
        int dPos = d.find() ? d.start() : -1;
        int cPos = c.find() ? c.start() : -1;
        if (dPos < 0 && cPos < 0) return null;
        if (dPos < 0) return Direction.CREDIT;
        if (cPos < 0) return Direction.DEBIT;
        // Both present (e.g. "debited ... credited to beneficiary"): the verb closest to the amount wins.
        return Math.abs(dPos - amountPos) <= Math.abs(cPos - amountPos) ? Direction.DEBIT : Direction.CREDIT;
    }

    private static PaymentMethod detectMethod(String upper, String vpa) {
        if (vpa != null || upper.contains("UPI")) return PaymentMethod.UPI;
        if (upper.contains("IMPS")) return PaymentMethod.IMPS;
        if (upper.contains("NEFT")) return PaymentMethod.NEFT;
        if (upper.contains("RTGS")) return PaymentMethod.RTGS;
        if (upper.contains("ATM") || upper.contains("WITHDRAWN") || upper.contains("WITHDRAWAL")) return PaymentMethod.ATM;
        if (upper.contains("CARD") || upper.contains("SPENT")) return PaymentMethod.CARD;
        if (upper.contains("NACH") || upper.contains("ECS") || upper.contains("MANDATE")) return PaymentMethod.NACH;
        return PaymentMethod.OTHER;
    }

    private static String findCounterparty(String msg, String vpa) {
        Matcher m = COUNTERPARTY.matcher(msg);
        while (m.find()) {
            String candidate = m.group(1).trim().replaceAll("[.\\-*]+$", "").trim()
                    .replaceFirst("(?i)^(NEFT|IMPS|RTGS|UPI)(\\s+(from|to|by))?\\s+", "").trim();
            if (candidate.isEmpty() || WORD_SELF.matcher(candidate).matches()) continue;
            String lower = candidate.toLowerCase(Locale.ROOT);
            if (lower.startsWith("a/c") || lower.startsWith("your ") || lower.startsWith("ac ") || lower.startsWith("account")
                    || lower.matches(".*\\b(bank a/?c|a/c|card)\\b.*[0-9]{3,}.*")) continue;
            if (lower.matches("^(rs\\.?|inr|₹).*")) continue;
            if (candidate.equalsIgnoreCase(vpa)) return vpa.substring(0, vpa.indexOf('@'));
            if (candidate.contains("@")) return candidate.substring(0, candidate.indexOf('@'));
            return candidate;
        }
        return vpa != null ? vpa.substring(0, vpa.indexOf('@')) : null;
    }

    private static LocalDate findDate(String msg) {
        Matcher m = DATE_TOKEN.matcher(msg);
        while (m.find()) {
            String token = m.group(1);
            for (DateTimeFormatter f : DATE_FORMATS) {
                try {
                    LocalDate d = LocalDate.parse(token, f);
                    if (d.getYear() < 100) d = d.plusYears(2000);
                    return d;
                } catch (DateTimeParseException ignored) {
                    // try next format
                }
            }
        }
        return null;
    }

    private static LocalTime findTime(String msg) {
        Matcher m = TIME_TOKEN.matcher(msg);
        if (!m.find()) return null;
        int seconds = m.group(3) == null ? 0 : Integer.parseInt(m.group(3));
        return LocalTime.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), seconds);
    }

    private static String group(Pattern p, String text) {
        Matcher m = p.matcher(text);
        return m.find() ? m.group(1) : null;
    }

    /** Convenience for callers that want a timestamp, falling back to the receive time when the alert has none. */
    public static LocalDateTime timestamp(ParsedAlert alert, LocalDateTime receivedAt) {
        LocalDate date = alert.date() != null ? alert.date() : receivedAt.toLocalDate();
        LocalTime time = alert.time() != null ? alert.time()
                : (alert.date() == null || alert.date().equals(receivedAt.toLocalDate()) ? receivedAt.toLocalTime() : LocalTime.NOON);
        return LocalDateTime.of(date, time);
    }

    public static Optional<ParsedAlert> parsedOrEmpty(String text) {
        return parse(text) instanceof Parsed p ? Optional.of(p.alert()) : Optional.empty();
    }
}
