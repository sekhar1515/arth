package com.expensetracker.networth;

import com.expensetracker.common.TimeConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipInputStream;

/**
 * Closing prices for listed stocks, ETFs, sovereign gold bonds and bonds, from the daily "bhavcopy" files NSE and
 * BSE publish free on their websites after market close. No API key, no account. Holdings are matched by ISIN
 * first, then by ticker symbol. NSE wins when a security trades on both exchanges.
 */
@Service
public class StockPriceService {

    public record Security(String isin, String symbol, String exchange, String name, BigDecimal close, BigDecimal prevClose, LocalDate priceDate) {
    }

    public record RefreshResult(boolean ok, int securitiesLoaded, int holdingsUpdated, LocalDate priceDate, String message, Instant at) {
    }

    /** Asset classes that trade on an exchange and can be priced from the bhavcopy. */
    static final Set<AssetClass> LISTED = EnumSet.of(AssetClass.STOCK, AssetClass.ETF, AssetClass.SGB, AssetClass.BOND);

    private static final Logger log = LoggerFactory.getLogger(StockPriceService.class);
    private static final DateTimeFormatter YMD = DateTimeFormatter.BASIC_ISO_DATE;

    /** Newer prices replace older ones; on the same day NSE replaces BSE but not the other way round. */
    static final String UPSERT = """
            INSERT INTO security_prices (isin, symbol, exchange, series, name, close_price, prev_close, price_date, updated_at)
            VALUES (:isin, :symbol, :exchange, :series, :name, :close, :prev, :date, now())
            ON CONFLICT (isin) DO UPDATE SET
              symbol = EXCLUDED.symbol, exchange = EXCLUDED.exchange, series = EXCLUDED.series, name = COALESCE(EXCLUDED.name, security_prices.name),
              close_price = EXCLUDED.close_price, prev_close = EXCLUDED.prev_close, price_date = EXCLUDED.price_date, updated_at = now()
            WHERE EXCLUDED.price_date > security_prices.price_date
               OR (EXCLUDED.price_date = security_prices.price_date AND (EXCLUDED.exchange = 'NSE' OR security_prices.exchange = 'BSE'))
            """;
    static final String COLUMNS = "isin, symbol, exchange, name, close_price, prev_close, price_date";
    static final String SEARCH = "SELECT " + COLUMNS + """
             FROM security_prices
            WHERE isin = :exact OR upper(symbol) = :exact OR upper(symbol) LIKE :prefix OR lower(name) LIKE :like
            ORDER BY (upper(symbol) = :exact OR isin = :exact) DESC, (upper(symbol) LIKE :prefix) DESC, (exchange = 'NSE') DESC, name
            LIMIT 20
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final HoldingRepository holdings;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final String nseUrl;
    private final String bseUrl;
    private final boolean scheduleEnabled;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).followRedirects(HttpClient.Redirect.NORMAL).build();
    private final AtomicReference<RefreshResult> last = new AtomicReference<>();

    public StockPriceService(NamedParameterJdbcTemplate jdbc, HoldingRepository holdings, PlatformTransactionManager txManager, Clock clock,
                             @Value("${app.prices.nse-bhavcopy-url:https://nsearchives.nseindia.com/content/cm/BhavCopy_NSE_CM_0_0_0_%s_F_0000.csv.zip}") String nseUrl,
                             @Value("${app.prices.bse-bhavcopy-url:https://www.bseindia.com/download/BhavCopy/Equity/BhavCopy_BSE_CM_0_0_0_%s_F_0000.CSV}") String bseUrl,
                             @Value("${app.prices.stock-schedule-enabled:true}") boolean scheduleEnabled) {
        this.jdbc = jdbc;
        this.holdings = holdings;
        this.tx = new TransactionTemplate(txManager);
        this.clock = clock;
        this.nseUrl = nseUrl;
        this.bseUrl = bseUrl;
        this.scheduleEnabled = scheduleEnabled;
    }

    /** Bhavcopies appear around 6–7 pm IST on trading days; retry late at night in case they were delayed. */
    @Scheduled(cron = "0 5 18-23 * * MON-FRI", zone = "Asia/Kolkata")
    public void scheduledRefresh() {
        if (scheduleEnabled) refresh();
    }

    /** The last refresh since start-up, or else what the cached prices say (so a restart doesn't hide their date). */
    public Optional<RefreshResult> lastRefresh() {
        RefreshResult r = last.get();
        if (r != null) return Optional.of(r);
        LocalDate latest = jdbc.queryForObject("SELECT max(price_date) FROM security_prices", new MapSqlParameterSource(), LocalDate.class);
        return latest == null ? Optional.empty() : Optional.of(new RefreshResult(true, 0, 0, latest, "Cached prices", null));
    }

    /** Downloads the latest available NSE and BSE bhavcopies (looking back up to a week for holidays) and re-prices holdings. */
    public RefreshResult refresh() {
        List<BhavcopyParser.Quote> quotes = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        // BSE first so NSE prices for the same day replace them
        Optional<List<BhavcopyParser.Quote>> bse = latest(bseUrl, false, problems, "BSE");
        bse.ifPresent(quotes::addAll);
        Optional<List<BhavcopyParser.Quote>> nse = latest(nseUrl, true, problems, "NSE");
        nse.ifPresent(quotes::addAll);
        if (quotes.isEmpty()) {
            return remember(new RefreshResult(false, 0, 0, null,
                    "Couldn't download exchange prices (" + String.join("; ", problems) + "). Prices will retry this evening.", Instant.now()));
        }
        return tx.execute(status -> load(quotes));
    }

    /** Loads a bhavcopy you downloaded yourself (CSV or the zipped CSV). */
    public RefreshResult loadFile(byte[] content, String filename) {
        String csv;
        try {
            csv = filename != null && filename.toLowerCase(Locale.ROOT).endsWith(".zip") ? unzip(content) : new String(content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalArgumentException("Couldn't read the zip file");
        }
        List<BhavcopyParser.Quote> quotes = BhavcopyParser.parse(csv);
        if (quotes.isEmpty()) return remember(new RefreshResult(false, 0, 0, null, "The file had no stock prices", Instant.now()));
        quotes.sort((a, b) -> a.exchange().compareTo(b.exchange())); // BSE before NSE
        return tx.execute(status -> load(quotes));
    }

    RefreshResult load(List<BhavcopyParser.Quote> quotes) {
        LocalDate latest = null;
        List<SqlParameterSource> batch = new ArrayList<>(quotes.size());
        for (BhavcopyParser.Quote q : quotes) {
            batch.add(new MapSqlParameterSource().addValue("isin", q.isin()).addValue("symbol", q.symbol()).addValue("exchange", q.exchange())
                    .addValue("series", q.series()).addValue("name", q.name()).addValue("close", q.close()).addValue("prev", q.prevClose())
                    .addValue("date", q.date()));
            if (latest == null || q.date().isAfter(latest)) latest = q.date();
        }
        for (int i = 0; i < batch.size(); i += 2000) {
            jdbc.batchUpdate(UPSERT, batch.subList(i, Math.min(i + 2000, batch.size())).toArray(new SqlParameterSource[0]));
        }
        int updated = repriceHoldings();
        return remember(new RefreshResult(true, quotes.size(), updated, latest,
                "Loaded " + quotes.size() + " exchange prices" + (latest == null ? "" : " for " + latest), Instant.now()));
    }

    /**
     * Applies cached closing prices to every listed holding valued at market price. A price you typed or imported
     * from your broker after the market closed on that day is kept.
     */
    public int repriceHoldings() {
        List<Holding> listed = holdings.findAll().stream()
                .filter(h -> !h.isArchived() && LISTED.contains(h.getAssetClass()) && h.getValuationMethod() == ValuationMethod.MARKET_PRICE)
                .filter(h -> h.getIsin() != null || h.getSymbol() != null)
                .toList();
        if (listed.isEmpty()) return 0;
        Map<String, Security> byIsin = new HashMap<>();
        Map<String, Security> bySymbol = new HashMap<>();
        List<String> isins = listed.stream().map(Holding::getIsin).filter(s -> s != null).map(s -> s.toUpperCase(Locale.ROOT)).distinct().toList();
        List<String> symbols = listed.stream().map(Holding::getSymbol).filter(s -> s != null).map(StockPriceService::cleanSymbol).distinct().toList();
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("isins", isins.isEmpty() ? List.of("-") : isins)
                .addValue("symbols", symbols.isEmpty() ? List.of("-") : symbols);
        jdbc.query("SELECT " + COLUMNS + " FROM security_prices WHERE isin IN (:isins) OR upper(symbol) IN (:symbols) ORDER BY (exchange = 'NSE')", p, rs -> {
            Security s = map(rs);
            byIsin.put(s.isin(), s);
            bySymbol.put(s.symbol().toUpperCase(Locale.ROOT), s); // NSE rows come last, so they win
        });
        int n = 0;
        for (Holding h : listed) {
            Security s = h.getIsin() != null ? byIsin.get(h.getIsin().toUpperCase(Locale.ROOT)) : null;
            if (s == null && h.getSymbol() != null) s = bySymbol.get(cleanSymbol(h.getSymbol()));
            if (s == null) continue;
            Instant asOf = closeOf(s.priceDate());
            boolean newerOwnPrice = h.getPriceAsOf() != null && !asOf.isAfter(h.getPriceAsOf()) && h.getPriceSource() != Holding.PriceSource.EXCHANGE;
            if (newerOwnPrice) continue;
            if (h.getIsin() == null) h.setIsin(s.isin());
            h.applyPrice(s.close(), asOf, Holding.PriceSource.EXCHANGE);
            n++;
        }
        holdings.flush();
        return n;
    }

    public List<Security> search(String q) {
        if (q == null || q.trim().length() < 2) return List.of();
        String t = q.trim();
        String exact = t.toUpperCase(Locale.ROOT);
        String like = "%" + t.toLowerCase(Locale.ROOT).replace("%", "").replace("_", "").replaceAll("\\s+", "%") + "%";
        List<Security> out = new ArrayList<>();
        jdbc.query(SEARCH, new MapSqlParameterSource().addValue("exact", exact).addValue("prefix", exact.replace("%", "") + "%").addValue("like", like),
                rs -> {
                    out.add(map(rs));
                });
        return out;
    }

    public Optional<Security> find(String isin, String symbol) {
        List<Security> out = new ArrayList<>();
        jdbc.query("SELECT " + COLUMNS + " FROM security_prices WHERE isin = :isin OR upper(symbol) = :symbol ORDER BY (isin = :isin) DESC, (exchange = 'NSE') DESC LIMIT 1",
                new MapSqlParameterSource().addValue("isin", isin == null ? "-" : isin.toUpperCase(Locale.ROOT))
                        .addValue("symbol", symbol == null ? "-" : cleanSymbol(symbol)), rs -> {
                    out.add(map(rs));
                });
        return out.stream().findFirst();
    }

    /** Market close (3:30 pm IST) on the price date. */
    static Instant closeOf(LocalDate date) {
        return date.atTime(15, 30).atZone(TimeConfig.IST).toInstant();
    }

    /** "INFY.NS", "NSE:INFY", "infy-eq" → "INFY". */
    static String cleanSymbol(String symbol) {
        String s = symbol.trim().toUpperCase(Locale.ROOT);
        int colon = s.indexOf(':');
        if (colon >= 0) s = s.substring(colon + 1);
        if (s.endsWith(".NS") || s.endsWith(".BO")) s = s.substring(0, s.length() - 3);
        if (s.endsWith("-EQ") || s.endsWith("-BE")) s = s.substring(0, s.length() - 3);
        return s;
    }

    private Optional<List<BhavcopyParser.Quote>> latest(String urlPattern, boolean zipped, List<String> problems, String exchange) {
        LocalDate day = TimeConfig.today(clock);
        String lastProblem = null;
        for (int i = 0; i < 8; i++, day = day.minusDays(1)) {
            if (day.getDayOfWeek() == DayOfWeek.SATURDAY || day.getDayOfWeek() == DayOfWeek.SUNDAY) continue;
            String url = String.format(urlPattern, day.format(YMD));
            try {
                HttpResponse<byte[]> res = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60))
                        .header("User-Agent", "Mozilla/5.0 (compatible; Arth personal finance)").header("Accept", "*/*").GET().build(),
                        HttpResponse.BodyHandlers.ofByteArray());
                if (res.statusCode() == 404) continue; // holiday or not published yet
                if (res.statusCode() != 200) {
                    lastProblem = exchange + " returned HTTP " + res.statusCode();
                    continue;
                }
                String csv = zipped ? unzip(res.body()) : new String(res.body(), StandardCharsets.UTF_8);
                List<BhavcopyParser.Quote> quotes = BhavcopyParser.parse(csv);
                if (!quotes.isEmpty()) return Optional.of(quotes);
            } catch (Exception e) {
                log.warn("{} bhavcopy download failed for {}: {}", exchange, day, e.toString());
                lastProblem = exchange + " unreachable (" + e.getClass().getSimpleName() + ")";
            }
        }
        problems.add(lastProblem != null ? lastProblem : exchange + " has no file for the last week");
        return Optional.empty();
    }

    private static String unzip(byte[] zip) throws IOException {
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            if (in.getNextEntry() == null) throw new IOException("empty zip");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Security map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Security(rs.getString("isin"), rs.getString("symbol"), rs.getString("exchange"), rs.getString("name"),
                rs.getBigDecimal("close_price"), rs.getBigDecimal("prev_close"), rs.getObject("price_date", LocalDate.class));
    }

    private RefreshResult remember(RefreshResult r) {
        last.set(r);
        return r;
    }
}
