package com.expensetracker.networth;

import com.expensetracker.common.ApiException;
import com.expensetracker.common.TimeConfig;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Purchase history of a holding: each buy (and sell) on its own date and price. Used for exact XIRR and to show how
 * the money put in at each stage has done: per purchase (gain and its own yearly return) and per year of buying.
 */
@Service
public class LotService {

    public enum Kind { BUY, SELL }

    public record LotRequest(LocalDate date, Kind kind, BigDecimal amount, BigDecimal units, BigDecimal price, String note) {
    }

    public record AddRequest(List<LotRequest> lots, boolean setTotals) {
    }

    /** One purchase or sale, with how a purchase has done since (when its units are known). */
    public record LotView(UUID id, LocalDate date, Kind kind, BigDecimal amount, BigDecimal units, BigDecimal price, String source, String note,
                          BigDecimal valueToday, BigDecimal gain, BigDecimal gainPct, BigDecimal yearlyReturn) {
    }

    /** Money put in during one calendar year, and what it's worth today. {@code yearlyReturn} is that money's XIRR. */
    public record YearView(int year, BigDecimal invested, BigDecimal valueToday, BigDecimal gain, BigDecimal yearlyReturn, int purchases) {
    }

    public record LotsView(UUID holdingId, String holdingName, BigDecimal currentPrice, LocalDate priceDate, List<LotView> lots, List<YearView> years,
                           BigDecimal xirr, BigDecimal boughtTotal, BigDecimal investedOnRecord, String coverageNote) {
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final HoldingRepository holdings;
    private final Clock clock;

    public LotService(NamedParameterJdbcTemplate jdbc, HoldingRepository holdings, Clock clock) {
        this.jdbc = jdbc;
        this.holdings = holdings;
        this.clock = clock;
    }

    private record Row(UUID id, LocalDate date, Kind kind, BigDecimal amount, BigDecimal units, BigDecimal price, String source, String note) {
        /** Units bought or sold: as entered, or worked out from amount ÷ price. */
        BigDecimal unitsKnown() {
            if (units != null) return units;
            if (price != null && price.signum() > 0) return amount.divide(price, 6, RoundingMode.HALF_UP);
            return null;
        }
    }

    private List<Row> rows(UUID userId, UUID holdingId) {
        return jdbc.query("""
                SELECT id, trade_date, kind, amount, units, price, source, note FROM holding_lots
                WHERE user_id = :u AND holding_id = :h ORDER BY trade_date, created_at
                """, new MapSqlParameterSource().addValue("u", userId).addValue("h", holdingId),
                (rs, i) -> new Row(rs.getObject(1, UUID.class), rs.getObject(2, LocalDate.class), Kind.valueOf(rs.getString(3)), rs.getBigDecimal(4),
                        rs.getBigDecimal(5), rs.getBigDecimal(6), rs.getString(7), rs.getString(8)));
    }

    @Transactional(readOnly = true)
    public LotsView view(UUID userId, UUID holdingId) {
        Holding h = holdings.findByIdAndUserId(holdingId, userId).orElseThrow(() -> ApiException.notFound("Investment"));
        LocalDate today = TimeConfig.today(clock);
        BigDecimal price = h.getValuationMethod() == ValuationMethod.MARKET_PRICE ? h.getCurrentPrice() : null;
        List<Row> rows = rows(userId, holdingId);
        List<LotView> lots = new ArrayList<>();
        Map<Integer, List<Row>> byYear = new TreeMap<>();
        BigDecimal bought = BigDecimal.ZERO;
        for (Row r : rows) {
            BigDecimal value = null, gain = null, gainPct = null, yearly = null;
            BigDecimal u = r.unitsKnown();
            if (r.kind() == Kind.BUY) {
                bought = bought.add(r.amount());
                byYear.computeIfAbsent(r.date().getYear(), k -> new ArrayList<>()).add(r);
                if (u != null && price != null) {
                    value = u.multiply(price).setScale(2, RoundingMode.HALF_UP);
                    gain = value.subtract(r.amount());
                    gainPct = gain.multiply(BigDecimal.valueOf(100)).divide(r.amount(), 2, RoundingMode.HALF_UP);
                    yearly = cagr(r.amount(), value, r.date(), today);
                }
            }
            lots.add(new LotView(r.id(), r.date(), r.kind(), r.amount(), u, r.price() != null ? r.price()
                    : u != null ? r.amount().divide(u, 4, RoundingMode.HALF_UP) : null, r.source(), r.note(), value, gain, gainPct, yearly));
        }
        List<YearView> years = new ArrayList<>();
        byYear.forEach((year, list) -> {
            BigDecimal invested = list.stream().map(Row::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
            boolean allUnits = price != null && list.stream().allMatch(r -> r.unitsKnown() != null);
            BigDecimal value = allUnits ? list.stream().map(r -> r.unitsKnown().multiply(price)).reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP) : null;
            BigDecimal yearly = null;
            if (value != null) {
                List<Xirr.Flow> flows = new ArrayList<>();
                list.forEach(r -> flows.add(new Xirr.Flow(r.date(), r.amount().negate())));
                flows.add(new Xirr.Flow(today, value));
                yearly = Xirr.percent(flows);
            }
            years.add(new YearView(year, invested, value, value == null ? null : value.subtract(invested), yearly, list.size()));
        });
        String coverage = null;
        if (h.getInvestedAmount() != null && !rows.isEmpty()) {
            BigDecimal sold = rows.stream().filter(r -> r.kind() == Kind.SELL).map(Row::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal net = bought.subtract(sold);
            if (net.compareTo(h.getInvestedAmount().multiply(new BigDecimal("0.98"))) < 0) {
                coverage = "These purchases add up to " + com.expensetracker.analytics.InrFormat.format(net) + " of the "
                        + com.expensetracker.analytics.InrFormat.format(h.getInvestedAmount()) + " you've put in. Add the rest for an exact XIRR.";
            }
        }
        BigDecimal xirr = null;
        if (!rows.isEmpty()) {
            BigDecimal valueNow = Valuation.value(h.valuationInputs(), today).value();
            List<Xirr.Flow> flows = new ArrayList<>();
            rows.forEach(r -> flows.add(new Xirr.Flow(r.date(), r.kind() == Kind.BUY ? r.amount().negate() : r.amount())));
            if (valueNow.signum() > 0) flows.add(new Xirr.Flow(today, valueNow));
            xirr = Xirr.percent(flows);
        }
        return new LotsView(h.getId(), h.getName(), price, h.getPriceAsOf() == null ? null : h.getPriceAsOf().atZone(TimeConfig.IST).toLocalDate(),
                lots, years, xirr, bought, h.getInvestedAmount(), coverage);
    }

    /** The return per year that turns {@code paid} on {@code from} into {@code now} today. Null under a month. */
    static BigDecimal cagr(BigDecimal paid, BigDecimal now, LocalDate from, LocalDate today) {
        long days = ChronoUnit.DAYS.between(from, today);
        if (days < Xirr.MIN_DAYS || paid.signum() <= 0 || now.signum() <= 0) return null;
        double r = Math.pow(now.doubleValue() / paid.doubleValue(), 365.0 / days) - 1;
        return BigDecimal.valueOf(r * 100).setScale(2, RoundingMode.HALF_UP);
    }

    @Transactional
    public LotsView add(UUID userId, UUID holdingId, AddRequest req) {
        Holding h = holdings.findByIdAndUserId(holdingId, userId).orElseThrow(() -> ApiException.notFound("Investment"));
        if (req.lots() == null || req.lots().isEmpty()) throw ApiException.badRequest("Add at least one purchase");
        LocalDate today = TimeConfig.today(clock);
        for (LotRequest l : req.lots()) {
            if (l.date() == null) throw ApiException.badRequest("Each purchase needs a date");
            if (l.date().isAfter(today)) throw ApiException.badRequest("A purchase date can't be in the future");
            BigDecimal amount = l.amount();
            if ((amount == null || amount.signum() <= 0) && l.units() != null && l.price() != null) amount = l.units().multiply(l.price());
            if (amount == null || amount.signum() <= 0) throw ApiException.badRequest("Enter the amount (or units and price) for each purchase");
            if (l.units() != null && l.units().signum() <= 0) throw ApiException.badRequest("Units must be more than zero");
            if (l.price() != null && l.price().signum() <= 0) throw ApiException.badRequest("Price must be more than zero");
            jdbc.update("""
                    INSERT INTO holding_lots (id, user_id, holding_id, trade_date, kind, amount, units, price, source, note)
                    VALUES (:id, :u, :h, :d, :k, :a, :units, :price, 'MANUAL', :note)
                    """, new MapSqlParameterSource().addValue("id", UUID.randomUUID()).addValue("u", userId).addValue("h", holdingId)
                    .addValue("d", l.date()).addValue("k", (l.kind() == null ? Kind.BUY : l.kind()).name())
                    .addValue("a", amount.setScale(2, RoundingMode.HALF_UP)).addValue("units", l.units()).addValue("price", l.price())
                    .addValue("note", l.note() == null || l.note().isBlank() ? null : l.note().trim().substring(0, Math.min(200, l.note().trim().length()))));
        }
        if (req.setTotals()) setTotals(userId, h);
        return view(userId, holdingId);
    }

    /** "These are all my purchases": units, amount put in (average cost of what's left) and start date from the history. */
    private void setTotals(UUID userId, Holding h) {
        List<Row> rows = rows(userId, h.getId());
        if (rows.isEmpty()) return;
        BigDecimal buyAmount = BigDecimal.ZERO, buyUnits = BigDecimal.ZERO, sellUnits = BigDecimal.ZERO, sellAmount = BigDecimal.ZERO;
        boolean unitsKnown = true;
        for (Row r : rows) {
            BigDecimal u = r.unitsKnown();
            if (u == null) unitsKnown = false;
            if (r.kind() == Kind.BUY) {
                buyAmount = buyAmount.add(r.amount());
                if (u != null) buyUnits = buyUnits.add(u);
            } else {
                sellAmount = sellAmount.add(r.amount());
                if (u != null) sellUnits = sellUnits.add(u);
            }
        }
        if (unitsKnown && buyUnits.signum() > 0) {
            BigDecimal left = buyUnits.subtract(sellUnits).max(BigDecimal.ZERO);
            if (h.getValuationMethod() == ValuationMethod.MARKET_PRICE) h.setUnits(left.setScale(6, RoundingMode.HALF_UP));
            h.setInvestedAmount(buyAmount.multiply(left).divide(buyUnits, 2, RoundingMode.HALF_UP)); // average cost of units still held
        } else {
            h.setInvestedAmount(buyAmount.subtract(sellAmount).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP));
        }
        h.setStartDate(rows.get(0).date());
    }

    @Transactional
    public LotsView delete(UUID userId, UUID lotId) {
        List<UUID> holding = jdbc.queryForList("SELECT holding_id FROM holding_lots WHERE id = :id AND user_id = :u AND source = 'MANUAL'",
                new MapSqlParameterSource().addValue("id", lotId).addValue("u", userId), UUID.class);
        if (holding.isEmpty()) throw ApiException.notFound("Purchase (SIP entries are removed by changing the SIP)");
        jdbc.update("DELETE FROM holding_lots WHERE id = :id AND user_id = :u", new MapSqlParameterSource().addValue("id", lotId).addValue("u", userId));
        return view(userId, holding.get(0));
    }
}
