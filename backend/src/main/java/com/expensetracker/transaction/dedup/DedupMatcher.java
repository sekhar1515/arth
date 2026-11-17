package com.expensetracker.transaction.dedup;

import com.expensetracker.transaction.Direction;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Decides whether an incoming transaction is the same real-world payment as an existing one.
 * <ul>
 *   <li><b>EXACT</b>: both have a reference number, they are equal, and amount + direction match → merge sources.</li>
 *   <li><b>FUZZY</b>: no conflicting references, same account + direction + amount, timestamps within tolerance,
 *       merchants compatible → insert as SUSPECTED duplicate (excluded from totals until the user resolves it).</li>
 *   <li>Different, non-empty reference numbers always mean different transactions.</li>
 * </ul>
 */
public final class DedupMatcher {

    public enum MatchType { EXACT, FUZZY, NONE }

    public record Candidate(UUID id, UUID accountId, Direction direction, BigDecimal amount, Instant txnAt,
                            boolean timeKnown, String reference, String merchantKey) {
    }

    public record Match(MatchType type, UUID existingId, String reason) {
        public static final Match NONE = new Match(MatchType.NONE, null, null);
    }

    /** Time window when both sides carry a real time of day. */
    public static final Duration TIME_TOLERANCE = Duration.ofMinutes(20);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private DedupMatcher() {
    }

    public static Match match(Candidate incoming, List<Candidate> existing) {
        String incomingRef = normaliseRef(incoming.reference());
        if (incomingRef != null) {
            for (Candidate c : existing) {
                String ref = normaliseRef(c.reference());
                if (incomingRef.equals(ref) && sameMoney(incoming, c)) {
                    return new Match(MatchType.EXACT, c.id(), "reference number " + incoming.reference() + " matches");
                }
            }
        }
        Match best = Match.NONE;
        long bestGap = Long.MAX_VALUE;
        for (Candidate c : existing) {
            if (!sameMoney(incoming, c)) continue;
            if (incoming.accountId() != null && c.accountId() != null && !incoming.accountId().equals(c.accountId())) continue;
            String ref = normaliseRef(c.reference());
            if (incomingRef != null && ref != null && !incomingRef.equals(ref)) continue;
            if (!withinTolerance(incoming, c)) continue;
            if (!merchantsCompatible(incoming.merchantKey(), c.merchantKey())) continue;
            long gap = Math.abs(Duration.between(incoming.txnAt(), c.txnAt()).toMinutes());
            if (gap < bestGap) {
                bestGap = gap;
                best = new Match(MatchType.FUZZY, c.id(), "same account, amount and direction within tolerance");
            }
        }
        return best;
    }

    private static boolean sameMoney(Candidate a, Candidate b) {
        return a.direction() == b.direction() && a.amount().compareTo(b.amount()) == 0;
    }

    static boolean withinTolerance(Candidate a, Candidate b) {
        if (a.timeKnown() && b.timeKnown()) {
            return Duration.between(a.txnAt(), b.txnAt()).abs().compareTo(TIME_TOLERANCE) <= 0;
        }
        // Statement rows carry only a date; alerts may post a day apart around midnight.
        long days = Math.abs(ChronoUnit.DAYS.between(a.txnAt().atZone(IST).toLocalDate(), b.txnAt().atZone(IST).toLocalDate()));
        return days <= 1;
    }

    static boolean merchantsCompatible(String a, String b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) return true;
        return a.contains(b) || b.contains(a);
    }

    public static String normaliseRef(String ref) {
        if (ref == null) return null;
        // statements often zero-pad UPI references ("0000431212345678"), alerts do not
        String r = ref.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "").replaceFirst("^0+", "");
        if (r.length() < 6) return null;
        return r;
    }

    public static Optional<Candidate> findById(List<Candidate> list, UUID id) {
        return list.stream().filter(c -> c.id().equals(id)).findFirst();
    }
}
