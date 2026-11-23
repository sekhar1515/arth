package com.expensetracker.networth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Mutual fund NAVs from AMFI's public daily NAV file (official, free, no key). Stock and ETF prices come from your
 * broker holdings export or manual updates: there is no free official real-time stock price API for India.
 */
@Service
public class PriceService {

    public record Scheme(String schemeCode, String name, String amc, String category, String isinGrowth, String isinReinvest,
                         BigDecimal nav, LocalDate navDate, BigDecimal prevNav, LocalDate prevNavDate) {
    }

    public record RefreshResult(boolean ok, int schemesLoaded, int holdingsUpdated, LocalDate latestNavDate, String message, Instant at) {
    }

    private static final Logger log = LoggerFactory.getLogger(PriceService.class);

    static final String UPSERT_SCHEME = """
            INSERT INTO mf_schemes (scheme_code, isin_growth, isin_reinvest, name, amc, category, nav, nav_date, updated_at)
            VALUES (:code, :isinG, :isinR, :name, :amc, :category, :nav, :navDate, now())
            ON CONFLICT (scheme_code) DO UPDATE SET
              isin_growth = EXCLUDED.isin_growth, isin_reinvest = EXCLUDED.isin_reinvest, name = EXCLUDED.name,
              amc = COALESCE(EXCLUDED.amc, mf_schemes.amc), category = COALESCE(EXCLUDED.category, mf_schemes.category),
              prev_nav = CASE WHEN EXCLUDED.nav_date > mf_schemes.nav_date THEN mf_schemes.nav ELSE mf_schemes.prev_nav END,
              prev_nav_date = CASE WHEN EXCLUDED.nav_date > mf_schemes.nav_date THEN mf_schemes.nav_date ELSE mf_schemes.prev_nav_date END,
              nav = COALESCE(EXCLUDED.nav, mf_schemes.nav), nav_date = COALESCE(EXCLUDED.nav_date, mf_schemes.nav_date), updated_at = now()
            """;
    static final String SEARCH = """
            SELECT scheme_code, name, amc, category, isin_growth, isin_reinvest, nav, nav_date, prev_nav, prev_nav_date FROM mf_schemes
            WHERE scheme_code = :exact OR isin_growth = :exactUpper OR isin_reinvest = :exactUpper OR lower(name) LIKE :like
            ORDER BY (lower(name) LIKE '%direct%' AND lower(name) LIKE '%growth%') DESC, name
            LIMIT 25
            """;
    static final String BY_CODES = "SELECT scheme_code, name, amc, category, isin_growth, isin_reinvest, nav, nav_date, prev_nav, prev_nav_date FROM mf_schemes WHERE scheme_code IN (:codes)";
    static final String BY_ISIN = "SELECT scheme_code, name, amc, category, isin_growth, isin_reinvest, nav, nav_date, prev_nav, prev_nav_date FROM mf_schemes WHERE isin_growth = :isin OR isin_reinvest = :isin LIMIT 1";

    private final NamedParameterJdbcTemplate jdbc;
    private final HoldingRepository holdings;
    private final org.springframework.transaction.support.TransactionTemplate tx;
    private final String amfiUrl;
    private final boolean scheduleEnabled;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).followRedirects(HttpClient.Redirect.NORMAL).build();
    private final AtomicReference<RefreshResult> last = new AtomicReference<>();
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.context.ApplicationEventPublisher events;

    public PriceService(NamedParameterJdbcTemplate jdbc, HoldingRepository holdings,
                        org.springframework.transaction.PlatformTransactionManager txManager,
                        @Value("${app.prices.amfi-url:https://portal.amfiindia.com/spages/NAVAll.txt}") String amfiUrl,
                        @Value("${app.prices.amfi-schedule-enabled:true}") boolean scheduleEnabled) {
        this.jdbc = jdbc;
        this.holdings = holdings;
        this.tx = new org.springframework.transaction.support.TransactionTemplate(txManager);
        this.amfiUrl = amfiUrl;
        this.scheduleEnabled = scheduleEnabled;
    }

    /**
     * Most Indian funds publish the day's NAV by about 10 pm; funds holding US/international stocks and fund-of-funds
     * publish after midnight. Main check at 2:30 am (gets everything), evening checks so most funds update the same
     * night, and a 7:30 am catch-up for stragglers.
     */
    @Scheduled(cron = "0 30 2,7,21,23 * * *", zone = "Asia/Kolkata")
    public void scheduledRefresh() {
        if (scheduleEnabled) refreshMutualFunds();
    }

    /** The last refresh since start-up, or else what the cached NAVs say (so a restart doesn't hide their date). */
    public Optional<RefreshResult> lastRefresh() {
        RefreshResult r = last.get();
        if (r != null) return Optional.of(r);
        LocalDate latest = jdbc.queryForObject("SELECT max(nav_date) FROM mf_schemes", new MapSqlParameterSource(), LocalDate.class);
        return latest == null ? Optional.empty() : Optional.of(new RefreshResult(true, 0, 0, latest, "Cached NAVs", null));
    }

    public RefreshResult refreshMutualFunds() {
        String body;
        try {
            HttpResponse<String> res = http.send(HttpRequest.newBuilder(URI.create(amfiUrl)).timeout(Duration.ofSeconds(90))
                    .header("User-Agent", "Arth personal finance").GET().build(), HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                return remember(new RefreshResult(false, 0, 0, null, "AMFI returned HTTP " + res.statusCode(), Instant.now()));
            }
            body = res.body();
        } catch (Exception e) {
            log.warn("AMFI NAV download failed: {}", e.toString());
            return remember(new RefreshResult(false, 0, 0, null, "Couldn't reach AMFI (" + e.getClass().getSimpleName()
                    + "). Check the server's internet access; prices will retry tonight.", Instant.now()));
        }
        final String file = body;
        return tx.execute(status -> loadNavFile(file)); // self-call: run inside an explicit transaction
    }

    /** Loads a NAVAll.txt body: upserts the scheme cache and re-prices every mutual fund holding. Public for tests and manual uploads. */
    @Transactional
    public RefreshResult loadNavFile(String body) {
        List<AmfiNavParser.NavRecord> records = AmfiNavParser.parse(body);
        if (records.isEmpty()) return remember(new RefreshResult(false, 0, 0, null, "The NAV file had no schemes", Instant.now()));
        List<SqlParameterSource> batch = new ArrayList<>(records.size());
        LocalDate latest = null;
        for (AmfiNavParser.NavRecord r : records) {
            batch.add(new MapSqlParameterSource().addValue("code", r.schemeCode()).addValue("isinG", r.isinGrowth())
                    .addValue("isinR", r.isinReinvest()).addValue("name", trim(r.name(), 300)).addValue("amc", trim(r.amc(), 150))
                    .addValue("category", trim(r.category(), 200)).addValue("nav", r.nav()).addValue("navDate", r.date()));
            if (r.date() != null && (latest == null || r.date().isAfter(latest))) latest = r.date();
        }
        for (int i = 0; i < batch.size(); i += 2000) {
            jdbc.batchUpdate(UPSERT_SCHEME, batch.subList(i, Math.min(i + 2000, batch.size())).toArray(new SqlParameterSource[0]));
        }
        int updated = repriceFundHoldings();
        recordHistory();
        if (events != null) events.publishEvent(new PricesUpdatedEvent("fund NAVs"));
        return remember(new RefreshResult(true, records.size(), updated, latest, "Loaded " + records.size() + " schemes", Instant.now()));
    }

    /** Applies cached NAVs to fund holdings; links holdings that only have an ISIN to their AMFI scheme code. */
    @Transactional
    public int repriceFundHoldings() {
        for (Holding h : holdings.findByArchivedFalseAndAssetClassAndSchemeCodeIsNullAndIsinIsNotNull(AssetClass.MUTUAL_FUND)) {
            byIsin(h.getIsin()).ifPresent(s -> h.setSchemeCode(s.schemeCode()));
        }
        // funds added by name only: link them when the name points to exactly one scheme
        for (Holding h : holdings.findAll()) {
            if (h.isArchived() || h.getAssetClass() != AssetClass.MUTUAL_FUND || h.getSchemeCode() != null || h.getIsin() != null) continue;
            matchByName(h.getName()).ifPresent(s -> link(h, s));
        }
        holdings.flush();
        List<Holding> funds = holdings.findAll().stream()
                .filter(h -> !h.isArchived() && h.getSchemeCode() != null && h.getValuationMethod() == ValuationMethod.MARKET_PRICE).toList();
        if (funds.isEmpty()) return 0;
        Map<String, Scheme> schemes = new HashMap<>();
        jdbc.query(BY_CODES, new MapSqlParameterSource("codes", funds.stream().map(Holding::getSchemeCode).distinct().toList()), rs -> {
            Scheme s = map(rs);
            schemes.put(s.schemeCode(), s);
        });
        int n = 0;
        for (Holding h : funds) {
            Scheme s = schemes.get(h.getSchemeCode());
            if (s == null || s.nav() == null || s.navDate() == null) continue;
            Instant asOf = s.navDate().atTime(23, 0).atZone(com.expensetracker.common.TimeConfig.IST).toInstant();
            if (h.getPriceAsOf() != null && !asOf.isAfter(h.getPriceAsOf()) && h.getPriceSource() != Holding.PriceSource.AMFI) continue;
            h.applyPrice(s.nav(), asOf, Holding.PriceSource.AMFI);
            n++;
        }
        return n;
    }

    /**
     * Fund search: an exact scheme code or ISIN, or every word of the query somewhere in the name, in any order
     * ("hdfc index nifty 50" finds "HDFC NIFTY 50 Index Fund"). Direct-Growth plans come first.
     */
    public List<Scheme> search(String q) {
        if (q == null || q.trim().length() < 2) return List.of();
        String t = q.trim();
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("exact", t).addValue("exactUpper", t.toUpperCase(java.util.Locale.ROOT));
        List<String> words = new ArrayList<>();
        for (String w : t.toLowerCase(java.util.Locale.ROOT).replace("&", " ").split("[^a-z0-9]+")) {
            if (!w.isBlank() && !SEARCH_NOISE.contains(w) && !words.contains(w)) words.add(w);
            if (words.size() == 8) break;
        }
        StringBuilder where = new StringBuilder();
        for (int i = 0; i < words.size(); i++) {
            String w = words.get(i);
            if (i > 0) where.append(" AND ");
            if (w.equals("idcw") || w.equals("dividend")) {
                where.append("(lower(name) LIKE '%idcw%' OR lower(name) LIKE '%dividend%')");
            } else {
                where.append("lower(name) LIKE :w").append(i);
                p.addValue("w" + i, "%" + w + "%");
            }
        }
        String sql = "SELECT scheme_code, name, amc, category, isin_growth, isin_reinvest, nav, nav_date, prev_nav, prev_nav_date FROM mf_schemes "
                + "WHERE scheme_code = :exact OR isin_growth = :exactUpper OR isin_reinvest = :exactUpper"
                + (where.isEmpty() ? "" : " OR (" + where + ")")
                + " ORDER BY COALESCE(scheme_code = :exact OR isin_growth = :exactUpper OR isin_reinvest = :exactUpper, false) DESC, (nav IS NOT NULL) DESC,"
                + " (lower(name) LIKE '%direct%' AND lower(name) LIKE '%growth%') DESC, length(name), name LIMIT 25";
        List<Scheme> out = new ArrayList<>();
        jdbc.query(sql, p, rs -> {
            out.add(map(rs));
        });
        return out;
    }

    private static final java.util.Set<String> SEARCH_NOISE = java.util.Set.of("fund", "plan", "option", "the", "of", "scheme", "mf");

    /** Cached schemes by code, for pricing many holdings at once. */
    public Map<String, Scheme> byCodes(java.util.Collection<String> codes) {
        Map<String, Scheme> out = new HashMap<>();
        if (codes.isEmpty()) return out;
        jdbc.query(BY_CODES, new MapSqlParameterSource("codes", List.copyOf(codes)), rs -> {
            Scheme sc = map(rs);
            out.put(sc.schemeCode(), sc);
        });
        return out;
    }

    /** When the fund list was last loaded from AMFI. */
    public Optional<Instant> lastLoadedAt() {
        return Optional.ofNullable(jdbc.queryForObject("SELECT max(updated_at) FROM mf_schemes", new MapSqlParameterSource(), java.sql.Timestamp.class))
                .map(java.sql.Timestamp::toInstant);
    }

    /** Keeps each held fund's NAVs by date (latest and previous), for allotting SIPs on the right day. */
    void recordHistory() {
        for (String col : new String[]{"nav, nav_date", "prev_nav, prev_nav_date"}) {
            String[] c = col.split(", ");
            jdbc.update("INSERT INTO mf_nav_history (scheme_code, nav_date, nav) SELECT scheme_code, " + c[1] + ", " + c[0]
                    + " FROM mf_schemes WHERE " + c[0] + " IS NOT NULL AND " + c[1] + " IS NOT NULL"
                    + " AND scheme_code IN (SELECT scheme_code FROM holdings WHERE scheme_code IS NOT NULL) ON CONFLICT DO NOTHING",
                    new MapSqlParameterSource());
        }
    }

    public record DatedNav(LocalDate date, BigDecimal nav) {
    }

    /**
     * The first NAV published on or after {@code date}: what a SIP due that day is allotted at. Weekends and market
     * holidays have no NAV, so this is the next working day's. Empty if it isn't known yet.
     */
    public Optional<DatedNav> firstNavOnOrAfter(String schemeCode, LocalDate date) {
        List<DatedNav> out = new ArrayList<>();
        jdbc.query("SELECT nav_date, nav FROM mf_nav_history WHERE scheme_code = :c AND nav_date >= :d ORDER BY nav_date LIMIT 1",
                new MapSqlParameterSource().addValue("c", schemeCode).addValue("d", date), rs -> {
                    out.add(new DatedNav(rs.getObject(1, LocalDate.class), rs.getBigDecimal(2)));
                });
        return out.stream().findFirst();
    }

    public Optional<Scheme> byCode(String code) {
        if (code == null) return Optional.empty();
        List<Scheme> out = new ArrayList<>();
        jdbc.query(BY_CODES, new MapSqlParameterSource("codes", List.of(code)), rs -> {
            out.add(map(rs));
        });
        return out.stream().findFirst();
    }

    public Optional<Scheme> byIsin(String isin) {
        if (isin == null) return Optional.empty();
        List<Scheme> out = new ArrayList<>();
        jdbc.query(BY_ISIN, new MapSqlParameterSource("isin", isin), rs -> {
            out.add(map(rs));
        });
        return out.stream().findFirst();
    }

    private static final java.util.Set<String> NAME_NOISE = java.util.Set.of("fund", "plan", "option", "scheme", "the", "of", "and", "mf",
            "mutual", "series", "an", "open", "ended");

    static java.util.Set<String> nameTokens(String name) {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        if (name == null) return out;
        String n = name.toLowerCase(java.util.Locale.ROOT).replace("&", " and ").replaceAll("[^a-z0-9 ]", " ")
                .replace("dividend", "idcw").replace("payout", "idcw").replace("reinvestment", "idcw");
        for (String t : n.split("\\s+")) if (!t.isBlank() && !NAME_NOISE.contains(t)) out.add(t);
        return out;
    }

    /**
     * Finds the AMFI scheme a fund's name refers to, only when there is no doubt: every word of the name must appear in
     * the scheme's name and exactly one scheme may fit. Direct and Regular plans (and Growth and IDCW options) have
     * different NAVs, so a name that doesn't say which is left for you to pick.
     */
    public Optional<Scheme> matchByName(String name) {
        java.util.Set<String> want = nameTokens(name);
        if (want.size() < 2) return Optional.empty();
        List<Scheme> fits = new ArrayList<>();
        for (Scheme s : search(String.join(" ", want))) {
            java.util.Set<String> have = nameTokens(s.name());
            if (have.containsAll(want)) fits.add(s);
        }
        boolean saysPlan = want.contains("direct") || want.contains("regular");
        boolean saysOption = want.contains("growth") || want.contains("idcw");
        if (!saysPlan || !saysOption) {
            // without the plan and option the name fits several schemes, unless the fund only has one
            return fits.size() == 1 ? Optional.of(fits.get(0)) : Optional.empty();
        }
        return fits.size() == 1 ? Optional.of(fits.get(0)) : fits.stream()
                .filter(s -> nameTokens(s.name()).size() == want.size()).findFirst()
                .filter(s -> fits.stream().filter(f -> nameTokens(f.name()).size() == want.size()).count() == 1);
    }

    public boolean hasSchemeCache() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM (SELECT 1 FROM mf_schemes LIMIT 1) x", new MapSqlParameterSource(), Integer.class);
        return n != null && n > 0;
    }

    private static Scheme map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Scheme(rs.getString("scheme_code"), rs.getString("name"), rs.getString("amc"), rs.getString("category"),
                rs.getString("isin_growth"), rs.getString("isin_reinvest"), rs.getBigDecimal("nav"), rs.getObject("nav_date", LocalDate.class),
                rs.getBigDecimal("prev_nav"), rs.getObject("prev_nav_date", LocalDate.class));
    }

    static void link(Holding h, Scheme s) {
        h.setSchemeCode(s.schemeCode());
        if (h.getIsin() == null) h.setIsin(s.isinGrowth() != null ? s.isinGrowth() : s.isinReinvest());
    }

    private RefreshResult remember(RefreshResult r) {
        last.set(r);
        return r;
    }

    private static String trim(String s, int max) {
        return s == null ? null : (s.length() > max ? s.substring(0, max) : s);
    }
}
