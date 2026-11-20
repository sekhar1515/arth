package com.expensetracker.networth;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Builds each holding's dated cash flows for XIRR:
 * <ul>
 *   <li>money put in before Arth tracked it, on the "investing since" date you gave (spread monthly up to the first
 *       tracked SIP when the holding has one, since SIP money goes in month by month);</li>
 *   <li>every SIP or contribution Arth applied, on its date;</li>
 *   <li>today's value.</li>
 * </ul>
 * When you've entered purchases yourself, those (plus SIPs) are used instead, which is exact. FDs use their deposit date.
 * Holdings without a start date or purchases get no XIRR (it would be a guess).
 */
@Component
public class XirrService {

    public record HoldingFlows(List<Xirr.Flow> flows, boolean estimated) {
    }

    private record Installment(UUID holdingId, LocalDate date, BigDecimal amount, boolean sip) {
    }

    private final NamedParameterJdbcTemplate jdbc;

    public XirrService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Flows per holding id, only for holdings where XIRR can be worked out. */
    public Map<UUID, HoldingFlows> flows(List<Holding> holdings, Map<UUID, BigDecimal> values, LocalDate today) {
        Map<UUID, HoldingFlows> out = new HashMap<>();
        if (holdings.isEmpty()) return out;
        Map<UUID, List<Installment>> byHolding = new HashMap<>();
        Map<UUID, List<Xirr.Flow>> manual = new HashMap<>();
        jdbc.query("""
                SELECT holding_id, trade_date, amount, kind, source FROM holding_lots WHERE holding_id IN (:ids) ORDER BY trade_date
                """, new MapSqlParameterSource("ids", holdings.stream().map(Holding::getId).toList()), rs -> {
            UUID hid = rs.getObject(1, UUID.class);
            LocalDate d = rs.getObject(2, LocalDate.class);
            BigDecimal amount = rs.getBigDecimal(3);
            boolean buy = "BUY".equals(rs.getString(4));
            if ("SIP".equals(rs.getString(5))) byHolding.computeIfAbsent(hid, k -> new ArrayList<>()).add(new Installment(hid, d, amount, true));
            // the full history (manual purchases and SIPs), used when you've entered purchases yourself
            manual.computeIfAbsent(hid, k -> new ArrayList<>()).add(new Xirr.Flow(d, buy ? amount.negate() : amount));
        });
        java.util.Set<UUID> hasManual = new java.util.HashSet<>();
        jdbc.query("SELECT DISTINCT holding_id FROM holding_lots WHERE source = 'MANUAL' AND holding_id IN (:ids)",
                new MapSqlParameterSource("ids", holdings.stream().map(Holding::getId).toList()), rs -> {
                    hasManual.add(rs.getObject(1, UUID.class));
                });
        Map<UUID, Boolean> hasSip = new HashMap<>();
        jdbc.query("SELECT holding_id FROM investment_plans WHERE kind = 'SIP' AND holding_id IN (:ids)",
                new MapSqlParameterSource("ids", holdings.stream().map(Holding::getId).toList()), rs -> {
                    hasSip.put(rs.getObject(1, UUID.class), true);
                });
        for (Holding h : holdings) {
            BigDecimal value = values.get(h.getId());
            if (hasManual.contains(h.getId()) && value != null && value.signum() > 0) {
                // exact: every purchase/sale on its own date, then today's value
                List<Xirr.Flow> exact = new ArrayList<>(manual.get(h.getId()));
                exact.add(new Xirr.Flow(today, value));
                out.put(h.getId(), new HoldingFlows(exact, false));
                continue;
            }
            if (value == null || value.signum() <= 0 || h.getInvestedAmount() == null || h.getStartDate() == null) continue;
            if (h.getStartDate().isAfter(today)) continue;
            List<Installment> tracked = byHolding.getOrDefault(h.getId(), List.of());
            List<Xirr.Flow> flows = new ArrayList<>();
            boolean estimated = false;
            BigDecimal trackedTotal = tracked.stream().map(Installment::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal before = h.getInvestedAmount().subtract(trackedTotal);
            if (before.signum() > 0) {
                LocalDate firstTracked = tracked.isEmpty() ? null : tracked.get(0).date();
                LocalDate spreadUntil = firstTracked != null ? firstTracked.minusMonths(1) : today;
                long months = ChronoUnit.MONTHS.between(h.getStartDate(), spreadUntil) + 1;
                if (Boolean.TRUE.equals(hasSip.get(h.getId())) && months > 1) {
                    // SIP money went in month by month: spread what came before tracking evenly
                    BigDecimal each = before.divide(BigDecimal.valueOf(months), 2, RoundingMode.HALF_UP);
                    for (long m = 0; m < months; m++) flows.add(new Xirr.Flow(h.getStartDate().plusMonths(m), each.negate()));
                    estimated = true;
                } else {
                    flows.add(new Xirr.Flow(h.getStartDate(), before.negate()));
                }
            }
            for (Installment in : tracked) flows.add(new Xirr.Flow(in.date(), in.amount().negate()));
            flows.add(new Xirr.Flow(today, value));
            out.put(h.getId(), new HoldingFlows(flows, estimated));
        }
        return out;
    }
}
