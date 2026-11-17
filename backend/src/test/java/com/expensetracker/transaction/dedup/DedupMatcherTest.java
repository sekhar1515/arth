package com.expensetracker.transaction.dedup;

import com.expensetracker.transaction.Direction;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class DedupMatcherTest {

    private static final UUID ACC = UUID.randomUUID();
    private static final UUID OTHER_ACC = UUID.randomUUID();
    private static final Instant T = Instant.parse("2026-11-17T07:00:00Z");

    private DedupMatcher.Candidate c(UUID acc, String amount, Instant at, boolean timeKnown, String ref, String merchant) {
        return new DedupMatcher.Candidate(UUID.randomUUID(), acc, Direction.DEBIT, new BigDecimal(amount), at, timeKnown, ref, merchant);
    }

    @Test
    void sameReferenceIsExactEvenAcrossFormats() {
        var existing = c(ACC, "450.00", T, true, "431212345678", "swiggy");
        var incoming = c(ACC, "450", T.plusSeconds(86400), false, "0000431212345678", "swiggy");
        var m = DedupMatcher.match(incoming, List.of(existing));
        assertEquals(DedupMatcher.MatchType.EXACT, m.type());
        assertEquals(existing.id(), m.existingId());
    }

    @Test
    void differentReferencesNeverMatch() {
        var existing = c(ACC, "100.00", T, true, "431200000001", "chai");
        var incoming = c(ACC, "100.00", T.plusSeconds(60), true, "431200000002", "chai");
        assertEquals(DedupMatcher.MatchType.NONE, DedupMatcher.match(incoming, List.of(existing)).type());
    }

    @Test
    void alertAndStatementRowWithoutRefIsFuzzy() {
        var alert = c(ACC, "450.00", T, true, null, "swiggy");
        var statementRow = c(ACC, "450.00", Instant.parse("2026-11-16T18:30:00Z"), false, null, "swiggy");
        assertEquals(DedupMatcher.MatchType.FUZZY, DedupMatcher.match(statementRow, List.of(alert)).type());
    }

    @Test
    void outsideTimeToleranceIsNotDuplicate() {
        var a = c(ACC, "450.00", T, true, null, "swiggy");
        var b = c(ACC, "450.00", T.plusSeconds(3600), true, null, "swiggy");
        assertEquals(DedupMatcher.MatchType.NONE, DedupMatcher.match(b, List.of(a)).type());
    }

    @Test
    void differentAccountsNeverMatchFuzzily() {
        var a = c(ACC, "450.00", T, true, null, "swiggy");
        var b = c(OTHER_ACC, "450.00", T, true, null, "swiggy");
        assertEquals(DedupMatcher.MatchType.NONE, DedupMatcher.match(b, List.of(a)).type());
    }

    @Test
    void incompatibleMerchantsAreNotDuplicates() {
        var a = c(ACC, "200.00", T, true, null, "uber");
        var b = c(ACC, "200.00", T.plusSeconds(120), true, null, "zomato");
        assertEquals(DedupMatcher.MatchType.NONE, DedupMatcher.match(b, List.of(a)).type());
    }

    @Test
    void oppositeDirectionIsNotDuplicate() {
        var a = c(ACC, "200.00", T, true, "431211112222", null);
        var b = new DedupMatcher.Candidate(UUID.randomUUID(), ACC, Direction.CREDIT, new BigDecimal("200.00"), T, true, "431211112222", null);
        assertEquals(DedupMatcher.MatchType.NONE, DedupMatcher.match(b, List.of(a)).type());
    }
}
