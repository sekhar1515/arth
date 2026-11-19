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
 * FDs use their deposit date. Holdings without a start date get no XIRR (it would be a guess).
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
        jdbc.query("""
                SELECT p.holding_id, i.due_date, i.amount, p.kind FROM investment_installments i
                JOIN investment_plans p ON p.id = i.plan_id WHERE p.holding_id IN (:ids) ORDER BY i.due_date
                """, new MapSqlParameterSource("ids", holdings.stream().map(Holding::getId).toList()), rs -> {
            Installment in = new Installment(rs.getObject(1, UUID.class), rs.getObject(2, LocalDate.class), rs.getBigDecimal(3), "SIP".equals(rs.getString(4)));
            byHolding.computeIfAbsent(in.holdingId(), k -> new ArrayList<>()).add(in);
        });
        Map<UUID, Boolean> hasSip = new HashMap<>();
        jdbc.query("SELECT holding_id FROM investment_plans WHERE kind = 'SIP' AND holding_id IN (:ids)",
                new MapSqlParameterSource("ids", holdings.stream().map(Holding::getId).toList()), rs -> {
                    hasSip.put(rs.getObject(1, UUID.class), true);
                });
        for (Holding h : holdings) {
            BigDecimal value = values.get(h.getId());
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
