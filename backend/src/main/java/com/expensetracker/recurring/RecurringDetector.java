package com.expensetracker.recurring;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Finds payments that repeat on a steady schedule. A repeat is only proposed, never assumed: weekly, monthly and
 * quarterly patterns need three payments and yearly ones two, with at least three quarters of the gaps close to
 * the typical gap. Kind is a guess from names and categories that the user confirms or changes.
 */
public final class RecurringDetector {

    public enum Frequency {
        WEEKLY(7, 5, 9, 52), MONTHLY(30, 25, 36, 12), QUARTERLY(91, 82, 100, 4), YEARLY(365, 350, 380, 1);

        public final int days;
        final int min;
        final int max;
        public final int perYear;

        Frequency(int days, int min, int max, int perYear) {
            this.days = days;
            this.min = min;
            this.max = max;
            this.perYear = perYear;
        }

        public LocalDate next(LocalDate from) {
            return switch (this) {
                case WEEKLY -> from.plusWeeks(1);
                case MONTHLY -> from.plusMonths(1);
                case QUARTERLY -> from.plusMonths(3);
                case YEARLY -> from.plusYears(1);
            };
        }
    }

    public enum Kind { SUBSCRIPTION, BILL, EMI, RENT, INSURANCE, SIP, INCOME, OTHER }

    /** One past payment. {@code key} groups payments to the same payee (merchant or note). */
    public record Payment(String key, String name, LocalDate date, BigDecimal amount, boolean credit, String categoryName, boolean investment) {
    }

    public record Pattern(String key, String name, boolean credit, Frequency frequency, Kind kind, int occurrences,
                          BigDecimal amount, BigDecimal previousAmount, boolean amountVaries, LocalDate lastDate, LocalDate nextDue,
                          int typicalGapDays) {
        public boolean priceChanged() {
            return !amountVaries && previousAmount != null && amount.compareTo(previousAmount) != 0;
        }

        public BigDecimal annualCost() {
            return amount.multiply(BigDecimal.valueOf(frequency.perYear)).setScale(2, RoundingMode.HALF_UP);
        }
    }

    private RecurringDetector() {
    }

    public static List<Pattern> detect(List<Payment> payments) {
        Map<String, List<Payment>> groups = new LinkedHashMap<>();
        for (Payment p : payments) {
            if (p.key() == null || p.key().isBlank()) continue;
            groups.computeIfAbsent(p.key() + (p.credit() ? "|C" : "|D"), k -> new ArrayList<>()).add(p);
        }
        List<Pattern> out = new ArrayList<>();
        for (List<Payment> g : groups.values()) {
            Pattern p = analyse(g);
            if (p != null) out.add(p);
        }
        out.sort(Comparator.comparing(Pattern::nextDue));
        return out;
    }

    static Pattern analyse(List<Payment> group) {
        List<Payment> ps = new ArrayList<>(group);
        ps.sort(Comparator.comparing(Payment::date));
        // several payments on the same day (e.g. split bill) count once, with their total
        List<Payment> daily = new ArrayList<>();
        for (Payment p : ps) {
            Payment last = daily.isEmpty() ? null : daily.get(daily.size() - 1);
            if (last != null && last.date().equals(p.date())) {
                daily.set(daily.size() - 1, new Payment(last.key(), last.name(), last.date(), last.amount().add(p.amount()), last.credit(), last.categoryName(), last.investment()));
            } else {
                daily.add(p);
            }
        }
        if (daily.size() < 2) return null;
        List<Long> gaps = new ArrayList<>();
        for (int i = 1; i < daily.size(); i++) gaps.add(ChronoUnit.DAYS.between(daily.get(i - 1).date(), daily.get(i).date()));
        long median = median(gaps);
        Frequency freq = null;
        for (Frequency f : Frequency.values()) {
            if (median >= f.min && median <= f.max) {
                freq = f;
                break;
            }
        }
        if (freq == null) return null;
        int needed = freq == Frequency.YEARLY ? 2 : 3;
        if (daily.size() < needed) return null;
        final Frequency fr = freq;
        long regular = gaps.stream().filter(g -> g >= fr.min && g <= fr.max).count();
        if (regular * 4 < gaps.size() * 3L) return null;

        Payment last = daily.get(daily.size() - 1);
        Payment prev = daily.get(daily.size() - 2);
        BigDecimal min = daily.stream().map(Payment::amount).min(BigDecimal::compareTo).orElseThrow();
        BigDecimal max = daily.stream().map(Payment::amount).max(BigDecimal::compareTo).orElseThrow();
        // amounts that move around (electricity, credit card) are bills with varying amounts; a single step change is a price change
        long distinct = daily.stream().map(p -> p.amount().stripTrailingZeros()).distinct().count();
        boolean steps = distinct <= 2 && isMonotonicStep(daily);
        boolean varies = !steps && max.compareTo(min.multiply(BigDecimal.valueOf(1.10))) > 0;
        BigDecimal amount = varies ? average(daily.subList(Math.max(0, daily.size() - 3), daily.size())) : last.amount();
        Kind kind = guessKind(last.name(), last.categoryName(), last.credit(), last.investment());
        return new Pattern(last.key(), last.name(), last.credit(), freq, kind, daily.size(), amount.setScale(2, RoundingMode.HALF_UP),
                varies ? null : prev.amount().setScale(2, RoundingMode.HALF_UP), varies, last.date(), freq.next(last.date()), (int) median);
    }

    private static boolean isMonotonicStep(List<Payment> ps) {
        int changes = 0;
        for (int i = 1; i < ps.size(); i++) if (ps.get(i).amount().compareTo(ps.get(i - 1).amount()) != 0) changes++;
        return changes <= 1;
    }

    private static BigDecimal average(List<Payment> ps) {
        return ps.stream().map(Payment::amount).reduce(BigDecimal.ZERO, BigDecimal::add).divide(BigDecimal.valueOf(ps.size()), 2, RoundingMode.HALF_UP);
    }

    private static long median(List<Long> xs) {
        List<Long> s = xs.stream().sorted().toList();
        int n = s.size();
        return n % 2 == 1 ? s.get(n / 2) : (s.get(n / 2 - 1) + s.get(n / 2)) / 2;
    }

    private static final String[][] KIND_WORDS = {
            {"SUBSCRIPTION", "netflix", "spotify", "prime", "hotstar", "jiohotstar", "youtube", "apple", "icloud", "google one", "zee5", "sonyliv",
                    "audible", "kindle", "chatgpt", "openai", "claude", "anthropic", "notion", "adobe", "microsoft", "linkedin", "gym", "cult", "swiggy one",
                    "zomato gold", "times prime", "subscription", "ott", "membership"},
            {"EMI", "emi", "loan", "bajaj finserv", "home credit", "nach"},
            {"RENT", "rent", "landlord", "nobroker"},
            {"INSURANCE", "insurance", "lic", "policy", "premium", "hdfc ergo", "icici lombard", "star health", "max life", "term plan"},
            {"SIP", "sip", "mutual fund", "groww", "zerodha", "coin", "kuvera", "nps", "ppf", "recurring deposit"},
            {"BILL", "electricity", "bescom", "tneb", "msedcl", "tata power", "adani", "water", "gas", "broadband", "wifi", "airtel", "jio", "vi ",
                    "vodafone", "bsnl", "act fibernet", "mobile", "postpaid", "dth", "tata play", "maintenance", "society", "bill", "utilities",
                    "maid", "cook", "driver", "household help", "school fee", "tuition"},
    };

    public static Kind guessKind(String name, String categoryName, boolean credit, boolean investment) {
        if (credit) return Kind.INCOME;
        if (investment) return Kind.SIP;
        String hay = (" " + (name == null ? "" : name) + " " + (categoryName == null ? "" : categoryName) + " ").toLowerCase(Locale.ROOT);
        for (String[] row : KIND_WORDS) {
            for (int i = 1; i < row.length; i++) {
                if (containsWord(hay, row[i])) return Kind.valueOf(row[0]);
            }
        }
        return Kind.OTHER;
    }

    private static boolean containsWord(String hay, String word) {
        int idx = hay.indexOf(word);
        while (idx >= 0) {
            boolean startOk = idx == 0 || !Character.isLetterOrDigit(hay.charAt(idx - 1));
            int end = idx + word.length();
            boolean endOk = end >= hay.length() || !Character.isLetterOrDigit(hay.charAt(end)) || word.endsWith(" ");
            if (startOk && endOk) return true;
            idx = hay.indexOf(word, idx + 1);
        }
        return false;
    }
}
