package com.expensetracker.networth;

import com.expensetracker.common.TimeConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Keeps fund NAVs and exchange closes current without anyone pressing "Refresh prices": on start-up (free hosts restart
 * often), and in the background when someone signs in or opens Net Worth while prices are behind. Runs at most one
 * refresh at a time and tries at most once every 30 minutes, since every user shares the same price data.
 */
@Component
public class PriceRefresher {

    static final Duration MIN_GAP = Duration.ofMinutes(30);
    /** After these IST times the day's prices are usually out. */
    static final LocalTime NAVS_OUT = LocalTime.of(21, 30);
    static final LocalTime CLOSES_OUT = LocalTime.of(19, 0);

    private static final Logger log = LoggerFactory.getLogger(PriceRefresher.class);

    private final PriceService funds;
    private final StockPriceService stocks;
    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;
    private final boolean enabled;
    private final AtomicReference<Instant> lastAttempt = new AtomicReference<>(Instant.EPOCH);
    private Instant lastFundSync = Instant.EPOCH;
    private Instant lastStockSync = Instant.EPOCH;
    static final Duration SYNC_GAP = Duration.ofMinutes(5);
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "price-refresh");
        t.setDaemon(true);
        return t;
    });

    public PriceRefresher(PriceService funds, StockPriceService stocks, NamedParameterJdbcTemplate jdbc, Clock clock,
                          @Value("${app.prices.auto-refresh:true}") boolean enabled) {
        this.funds = funds;
        this.stocks = stocks;
        this.jdbc = jdbc;
        this.clock = clock;
        this.enabled = enabled;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        refreshIfStale("start-up");
    }

    /** Starts a background refresh if prices are behind and none was tried in the last 30 minutes. Never blocks. */
    public void refreshIfStale(String reason) {
        if (!enabled) return;
        Instant now = clock.instant();
        Instant prev = lastAttempt.get();
        if (prev.plus(MIN_GAP).isAfter(now)) return;
        boolean fundsBehind;
        boolean stocksBehind;
        try {
            fundsBehind = isBehind(latest("SELECT max(nav_date) FROM mf_schemes"), expectedDate(now, NAVS_OUT));
            stocksBehind = isBehind(latest("SELECT max(price_date) FROM security_prices"), expectedDate(now, CLOSES_OUT));
        } catch (RuntimeException e) {
            log.debug("price staleness check failed: {}", e.toString());
            return;
        }
        if (!fundsBehind && !stocksBehind) return;
        if (!lastAttempt.compareAndSet(prev, now)) return; // another request just started one
        log.info("Refreshing prices in the background ({}): funds behind={}, stocks behind={}", reason, fundsBehind, stocksBehind);
        worker.submit(() -> {
            try {
                if (fundsBehind) funds.refreshMutualFunds();
                if (stocksBehind) stocks.refresh();
            } catch (RuntimeException e) {
                log.warn("Background price refresh failed: {}", e.toString());
            }
        });
    }

    /**
     * For "Get latest price" on one holding: if the cached NAVs or closes are behind, refresh them now and wait
     * (a few seconds). Waits for a refresh already running; won't re-download more than once every 5 minutes.
     */
    public synchronized void ensureFresh(boolean wantFunds, boolean wantStocks) {
        if (!enabled) return;
        Instant now = clock.instant();
        try {
            if (wantFunds && lastFundSync.plus(SYNC_GAP).isBefore(now)
                    && isBehind(latest("SELECT max(nav_date) FROM mf_schemes"), expectedDate(now, NAVS_OUT))) {
                lastFundSync = now;
                funds.refreshMutualFunds();
            }
            if (wantStocks && lastStockSync.plus(SYNC_GAP).isBefore(now)
                    && isBehind(latest("SELECT max(price_date) FROM security_prices"), expectedDate(now, CLOSES_OUT))) {
                lastStockSync = now;
                stocks.refresh();
            }
        } catch (RuntimeException e) {
            log.warn("Price refresh for a holding failed: {}", e.toString());
        }
    }

    public LocalDate latestNavDate() {
        return latest("SELECT max(nav_date) FROM mf_schemes");
    }

    public LocalDate latestCloseDate() {
        return latest("SELECT max(price_date) FROM security_prices");
    }

    /** True when AMFI should have published a NAV newer than the one Arth has. */
    public boolean fundsBehind() {
        return isBehind(latestNavDate(), expectedDate(clock.instant(), NAVS_OUT));
    }

    /** True when NSE/BSE should have published a close newer than the one Arth has. */
    public boolean stocksBehind() {
        return isBehind(latestCloseDate(), expectedDate(clock.instant(), CLOSES_OUT));
    }

    private LocalDate latest(String sql) {
        return jdbc.queryForObject(sql, new MapSqlParameterSource(), LocalDate.class);
    }

    static boolean isBehind(LocalDate have, LocalDate expected) {
        return have == null || have.isBefore(expected);
    }

    /** The most recent weekday whose prices should be out by now: today after the cut-off, otherwise the previous weekday. */
    static LocalDate expectedDate(Instant now, LocalTime cutOff) {
        ZonedDateTime ist = now.atZone(TimeConfig.IST);
        LocalDate d = ist.toLocalTime().isBefore(cutOff) ? ist.toLocalDate().minusDays(1) : ist.toLocalDate();
        while (d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY) d = d.minusDays(1);
        return d;
    }
}
