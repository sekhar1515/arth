package com.expensetracker.networth;

import com.expensetracker.common.TimeConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Daily value of each portfolio (yours, your parents'), kept separately from net worth so a tab can show its own
 * day-by-day and month-by-month change. Saved whenever the portfolio is opened and every night.
 */
@Service
public class PortfolioHistoryService {

    public record Point(LocalDate date, BigDecimal value, BigDecimal invested, BigDecimal gain) {
    }

    private static final Logger log = LoggerFactory.getLogger(PortfolioHistoryService.class);

    private final NamedParameterJdbcTemplate jdbc;
    private final NetWorthService netWorth;
    private final Clock clock;

    public PortfolioHistoryService(NamedParameterJdbcTemplate jdbc, NetWorthService netWorth, Clock clock) {
        this.jdbc = jdbc;
        this.netWorth = netWorth;
        this.clock = clock;
    }

    /** Records today's point for this portfolio (replacing an earlier one today). */
    public void save(UUID userId, HoldingOwner owner, NetWorthService.Summary s) {
        if (s.holdingsCount() == 0) return;
        jdbc.update("""
                INSERT INTO portfolio_snapshots (user_id, owner, snapshot_date, value, invested, gain)
                VALUES (:u, :o, :d, :v, :i, :g)
                ON CONFLICT (user_id, owner, snapshot_date) DO UPDATE SET value = EXCLUDED.value, invested = EXCLUDED.invested, gain = EXCLUDED.gain
                """, new MapSqlParameterSource("u", userId).addValue("o", owner.name()).addValue("d", TimeConfig.today(clock))
                .addValue("v", s.holdingsValue()).addValue("i", s.investedInHoldings()).addValue("g", s.holdingsGain() == null ? BigDecimal.ZERO : s.holdingsGain()));
    }

    public List<Point> history(UUID userId, HoldingOwner owner, int months) {
        LocalDate from = TimeConfig.today(clock).minusMonths(Math.max(1, Math.min(months, 120)));
        return jdbc.query("""
                SELECT snapshot_date, value, invested, gain FROM portfolio_snapshots
                WHERE user_id = :u AND owner = :o AND snapshot_date >= :from ORDER BY snapshot_date
                """, new MapSqlParameterSource("u", userId).addValue("o", owner.name()).addValue("from", from),
                (rs, i) -> new Point(rs.getObject(1, LocalDate.class), rs.getBigDecimal(2), rs.getBigDecimal(3), rs.getBigDecimal(4)));
    }

    /** Nightly, after the NAV and stock price refreshes: a point for every portfolio that has holdings. */
    @Scheduled(cron = "0 50 23 * * *", zone = "Asia/Kolkata")
    public void nightly() {
        List<UUID> users = jdbc.queryForList("SELECT DISTINCT user_id FROM holdings WHERE NOT archived", new MapSqlParameterSource(), UUID.class);
        for (UUID u : users) {
            for (HoldingOwner o : HoldingOwner.values()) {
                try {
                    save(u, o, netWorth.portfolio(u, o));
                } catch (RuntimeException e) {
                    log.warn("Portfolio snapshot failed for user {} ({}): {}", u, o, e.toString());
                }
            }
        }
    }
}
