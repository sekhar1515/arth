package com.expensetracker.networth;

import java.time.LocalDate;
import com.expensetracker.auth.CurrentUser;
import com.expensetracker.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/networth")
public class NetWorthController {

    public record PriceRequest(BigDecimal price) {
    }

    private final NetWorthService netWorth;
    private final HoldingService holdings;
    private final LiabilityService liabilities;
    private final PriceService prices;
    private final StockPriceService stockPrices;
    private final PriceRefresher refresher;
    private final ManualRefreshLimiter manualRefresh;
    private final InvestmentPlanService plans;
    private final InterestAccrualService interest;
    private final LotService lots;

    public NetWorthController(NetWorthService netWorth, HoldingService holdings, LiabilityService liabilities, PriceService prices,
                              StockPriceService stockPrices, PriceRefresher refresher, ManualRefreshLimiter manualRefresh,
                              InvestmentPlanService plans, InterestAccrualService interest,
                              LotService lots) {
        this.netWorth = netWorth;
        this.holdings = holdings;
        this.liabilities = liabilities;
        this.prices = prices;
        this.stockPrices = stockPrices;
        this.refresher = refresher;
        this.manualRefresh = manualRefresh;
        this.plans = plans;
        this.interest = interest;
        this.lots = lots;
    }

    @GetMapping
    public NetWorthService.Summary summary() {
        refresher.refreshIfStale("net worth opened");
        UUID userId = CurrentUser.id();
        interest.accrueFor(userId); // PF/PPF interest up to the last completed month
        return netWorth.current(userId); // also saves today's point, so history and comparisons are current
    }

    @GetMapping("/history")
    public List<NetWorthService.Point> history(@RequestParam(defaultValue = "12") int months) {
        UUID userId = CurrentUser.id();
        netWorth.snapshot(userId); // today's point always reflects the latest data
        return netWorth.history(userId, months);
    }

    // ---------------------------------------------------------------- holdings

    @GetMapping("/holdings")
    public List<HoldingService.HoldingDto> listHoldings() {
        return holdings.list(CurrentUser.id());
    }

    @PostMapping("/holdings")
    @ResponseStatus(HttpStatus.CREATED)
    public HoldingService.HoldingDto createHolding(@RequestBody HoldingService.HoldingRequest req) {
        UUID userId = CurrentUser.id();
        var dto = holdings.create(userId, req);
        netWorth.snapshot(userId);
        return dto;
    }

    @PutMapping("/holdings/{id}")
    public HoldingService.HoldingDto updateHolding(@PathVariable UUID id, @RequestBody HoldingService.HoldingRequest req) {
        UUID userId = CurrentUser.id();
        var dto = holdings.update(userId, id, req);
        netWorth.snapshot(userId);
        return dto;
    }

    @PostMapping("/holdings/{id}/price")
    public HoldingService.HoldingDto updatePrice(@PathVariable UUID id, @RequestBody PriceRequest req) {
        UUID userId = CurrentUser.id();
        var dto = holdings.updatePrice(userId, id, req.price());
        netWorth.snapshot(userId);
        return dto;
    }

    /** "Get latest price": makes sure today's official NAVs/closes are loaded, then prices this holding from them. */
    @PostMapping("/holdings/{id}/latest-price")
    public HoldingService.LatestPrice latestPrice(@PathVariable UUID id) {
        UUID userId = CurrentUser.id();
        // no download here: applies the NAV/close Arth already loaded (refreshed automatically each day)
        var result = holdings.applyLatestPrice(userId, id);
        netWorth.snapshot(userId);
        return result;
    }

    @PostMapping("/holdings/{id}/archive")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void archiveHolding(@PathVariable UUID id) {
        UUID userId = CurrentUser.id();
        holdings.setArchived(userId, id, true);
        netWorth.snapshot(userId);
    }

    @DeleteMapping("/holdings/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteHolding(@PathVariable UUID id) {
        UUID userId = CurrentUser.id();
        holdings.delete(userId, id);
        netWorth.snapshot(userId);
    }

    @PostMapping(value = "/holdings/import", consumes = "multipart/form-data")
    public HoldingService.ImportResult importHoldings(@RequestPart("file") MultipartFile file,
                                                      @RequestParam(required = false) String platform,
                                                      @RequestParam(required = false) AssetClass assetClass) throws IOException {
        if (file.isEmpty()) throw ApiException.badRequest("The file is empty");
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();
        UUID userId = CurrentUser.id();
        HoldingService.ImportResult result;
        if (com.expensetracker.integration.parsing.SpreadsheetReader.isSpreadsheet(name)) {
            result = importSpreadsheet(userId, file.getBytes(), platform, assetClass);
        } else if (name.endsWith(".csv") || name.endsWith(".txt")) {
            result = holdings.importCsv(userId, new String(file.getBytes(), StandardCharsets.UTF_8), platform, assetClass);
        } else {
            throw ApiException.badRequest("Upload your holdings as Excel (.xlsx or .xls) or CSV");
        }
        netWorth.snapshot(userId);
        return result;
    }

    /** Imports every sheet that holds a holdings table (e.g. Zerodha Console's Equity and Mutual Funds sheets) and adds up the results. */
    private HoldingService.ImportResult importSpreadsheet(UUID userId, byte[] bytes, String platform, AssetClass assetClass) {
        List<com.expensetracker.integration.parsing.SpreadsheetReader.SheetTable> sheets;
        try {
            sheets = com.expensetracker.integration.parsing.SpreadsheetReader.read(bytes);
        } catch (IllegalArgumentException e) {
            throw ApiException.unprocessable(e.getMessage());
        }
        int rows = 0, created = 0, updated = 0, linked = 0;
        List<String> errors = new java.util.ArrayList<>();
        String firstProblem = null;
        boolean any = false;
        for (var sheet : sheets) {
            try {
                HoldingsCsvParser.parse(sheet.csv());
            } catch (IllegalArgumentException e) {
                if (firstProblem == null) firstProblem = e.getMessage();
                continue;
            }
            var r = holdings.importCsv(userId, sheet.csv(), platform, assetClass);
            any = true;
            rows += r.rows();
            created += r.created();
            updated += r.updated();
            linked += r.linkedToAmfi();
            String prefix = sheets.size() > 1 ? sheet.name() + ": " : "";
            r.errors().forEach(e -> errors.add(prefix + e));
        }
        if (!any) throw ApiException.unprocessable(firstProblem != null ? firstProblem : "No sheet in this file looks like a holdings list");
        return new HoldingService.ImportResult(rows, created, updated, linked, errors);
    }

    // ---------------------------------------------------------------- purchase history

    @GetMapping("/holdings/{id}/lots")
    public LotService.LotsView lots(@PathVariable UUID id) {
        return lots.view(CurrentUser.id(), id);
    }

    @PostMapping("/holdings/{id}/lots")
    public LotService.LotsView addLots(@PathVariable UUID id, @RequestBody LotService.AddRequest req) {
        UUID userId = CurrentUser.id();
        var v = lots.add(userId, id, req);
        netWorth.snapshot(userId);
        return v;
    }

    @DeleteMapping("/lots/{id}")
    public LotService.LotsView deleteLot(@PathVariable UUID id) {
        UUID userId = CurrentUser.id();
        var v = lots.delete(userId, id);
        netWorth.snapshot(userId);
        return v;
    }

    // ---------------------------------------------------------------- SIPs and contributions

    @GetMapping("/plans")
    public List<InvestmentPlanService.PlanDto> listPlans() {
        UUID userId = CurrentUser.id();
        var list = plans.list(userId); // applies anything that has become due first
        netWorth.snapshot(userId);
        return list;
    }

    @PostMapping("/plans")
    @ResponseStatus(HttpStatus.CREATED)
    public InvestmentPlanService.PlanDto createPlan(@RequestBody InvestmentPlanService.PlanRequest req) {
        return plans.create(CurrentUser.id(), req);
    }

    @PutMapping("/plans/{id}")
    public InvestmentPlanService.PlanDto updatePlan(@PathVariable UUID id, @RequestBody InvestmentPlanService.PlanRequest req) {
        return plans.update(CurrentUser.id(), id, req);
    }

    @DeleteMapping("/plans/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deletePlan(@PathVariable UUID id) {
        plans.delete(CurrentUser.id(), id);
    }

    // ---------------------------------------------------------------- liabilities

    @GetMapping("/liabilities")
    public List<LiabilityService.LiabilityDto> listLiabilities() {
        return liabilities.list(CurrentUser.id());
    }

    @PostMapping("/liabilities")
    @ResponseStatus(HttpStatus.CREATED)
    public LiabilityService.LiabilityDto createLiability(@RequestBody LiabilityService.LiabilityRequest req) {
        UUID userId = CurrentUser.id();
        var dto = liabilities.create(userId, req);
        netWorth.snapshot(userId);
        return dto;
    }

    @PutMapping("/liabilities/{id}")
    public LiabilityService.LiabilityDto updateLiability(@PathVariable UUID id, @RequestBody LiabilityService.LiabilityRequest req) {
        UUID userId = CurrentUser.id();
        var dto = liabilities.update(userId, id, req);
        netWorth.snapshot(userId);
        return dto;
    }

    @DeleteMapping("/liabilities/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteLiability(@PathVariable UUID id) {
        UUID userId = CurrentUser.id();
        liabilities.delete(userId, id);
        netWorth.snapshot(userId);
    }

    // ---------------------------------------------------------------- prices

    @GetMapping("/mf-schemes")
    public List<PriceService.Scheme> searchSchemes(@RequestParam String q) {
        return prices.search(q);
    }

    public record CombinedRefresh(boolean ok, int holdingsUpdated, LocalDate latestNavDate, String message,
                                  PriceService.RefreshResult funds, StockPriceService.RefreshResult stocks) {
    }

    /**
     * "Refresh prices": downloads only what is actually behind (fund NAVs from AMFI, closes from NSE/BSE). When Arth
     * already has the newest published prices it makes no outside call, re-applies what it has, and says when newer
     * prices will exist; such a press doesn't use up the once-per-window allowance.
     */
    @PostMapping("/prices/refresh")
    public CombinedRefresh refreshPrices() {
        UUID userId = CurrentUser.id();
        RefreshWindows.Status window = manualRefresh.status(userId);
        // Outside the refresh windows (or once used) the button still answers: it shows the latest values Arth has and
        // says when newer ones come, without downloading anything.
        boolean fundsBehind = window.allowed() && refresher.fundsBehind();
        boolean stocksBehind = window.allowed() && refresher.stocksBehind();
        if (fundsBehind || stocksBehind) manualRefresh.claim(userId); // a real download counts as this window's refresh

        PriceService.RefreshResult funds = fundsBehind ? friendly(prices.refreshMutualFunds()) : cachedFunds();
        StockPriceService.RefreshResult stocks = stocksBehind ? friendly(stockPrices.refresh()) : cachedStocks();
        List<String> parts = new java.util.ArrayList<>();
        parts.add(funds.message());
        parts.add(stocks.message());
        return new CombinedRefresh(funds.ok() || stocks.ok(), funds.holdingsUpdated() + stocks.holdingsUpdated(), funds.latestNavDate(),
                String.join(" ", parts), funds, stocks);
    }

    private static PriceService.RefreshResult friendly(PriceService.RefreshResult r) {
        if (!r.ok()) return r;
        return new PriceService.RefreshResult(true, r.schemesLoaded(), r.holdingsUpdated(), r.latestNavDate(),
                "Fund NAVs updated" + (r.latestNavDate() == null ? "." : " to " + InrDates.shortDate(r.latestNavDate()) + "."), r.at());
    }

    private static StockPriceService.RefreshResult friendly(StockPriceService.RefreshResult r) {
        if (!r.ok()) return r;
        return new StockPriceService.RefreshResult(true, r.securitiesLoaded(), r.holdingsUpdated(), r.priceDate(),
                "Stock prices updated" + (r.priceDate() == null ? "." : " to the " + InrDates.shortDate(r.priceDate()) + " close."), r.at());
    }

    private PriceService.RefreshResult cachedFunds() {
        int n = prices.repriceFundHoldings();
        java.time.LocalDate d = refresher.latestNavDate();
        String loaded = prices.lastLoadedAt().map(NetWorthController::when).orElse(null);
        String msg;
        if (d == null) msg = "Fund NAVs aren't loaded yet; they arrive with the next AMFI update.";
        else if (!refresher.fundsBehind()) msg = "Already refreshed: the " + InrDates.shortDate(d) + " NAVs" + (loaded == null ? "" : " were loaded " + loaded)
                + ". New NAVs come once AMFI publishes them (usually by 10 pm) and Arth loads them automatically.";
        else msg = "Fund NAVs shown are for " + InrDates.shortDate(d) + ". Newer NAVs are being published; Arth loads them automatically.";
        return new PriceService.RefreshResult(true, 0, n, d, msg, java.time.Instant.now());
    }

    /** "today at 10:12 pm" / "on 17 Nov at 6:40 pm", in IST. */
    private static String when(java.time.Instant at) {
        java.time.ZonedDateTime t = at.atZone(com.expensetracker.common.TimeConfig.IST);
        String time = t.format(java.time.format.DateTimeFormatter.ofPattern("h:mm a", java.util.Locale.ENGLISH)).toLowerCase(java.util.Locale.ROOT);
        java.time.LocalDate today = java.time.LocalDate.now(com.expensetracker.common.TimeConfig.IST);
        String day = t.toLocalDate().equals(today) ? "today" : t.toLocalDate().equals(today.minusDays(1)) ? "yesterday" : "on " + InrDates.shortDate(t.toLocalDate());
        return day + " at " + time;
    }

    private StockPriceService.RefreshResult cachedStocks() {
        int n = stockPrices.repriceHoldings();
        java.time.LocalDate d = refresher.latestCloseDate();
        java.time.ZonedDateTime ist = java.time.ZonedDateTime.now(com.expensetracker.common.TimeConfig.IST);
        String loaded = stockPrices.lastLoadedAt().map(NetWorthController::when).orElse(null);
        String msg;
        if (d == null) msg = "Stock prices aren't loaded yet; they arrive after the next market close.";
        else if (d.equals(ist.toLocalDate())) msg = "Already refreshed: today's closing prices" + (loaded == null ? "" : " were loaded " + loaded) + ".";
        else msg = "Already refreshed: the " + InrDates.shortDate(d) + " closing prices" + (loaded == null ? "" : " were loaded " + loaded)
                + ". Stock prices update once a day after the 3:45 pm close on working days, automatically.";
        return new StockPriceService.RefreshResult(true, 0, n, d, msg, java.time.Instant.now());
    }

    public record PriceStatus(boolean allowed, RefreshWindows.Window window, java.time.Instant nextAllowedAt, String reason,
                              java.time.LocalDate latestNavDate, java.time.LocalDate latestCloseDate) {
    }

    /** Whether "Refresh prices" can be pressed now (and if not, when), plus the dates of the newest NAVs and closes. */
    @GetMapping("/prices/refresh-status")
    public PriceStatus refreshStatus() {
        var s = manualRefresh.status(CurrentUser.id());
        return new PriceStatus(s.allowed(), s.window(), s.nextAllowedAt(), s.reason(), refresher.latestNavDate(), refresher.latestCloseDate());
    }

    @GetMapping("/securities")
    public List<StockPriceService.Security> searchSecurities(@RequestParam String q) {
        CurrentUser.id();
        return stockPrices.search(q);
    }

    /** Upload an NSE/BSE bhavcopy yourself (CSV or zip) if the server can't reach the exchanges. */
    @PostMapping(value = "/prices/bhavcopy", consumes = "multipart/form-data")
    public StockPriceService.RefreshResult uploadBhavcopy(@RequestPart("file") MultipartFile file) throws java.io.IOException {
        CurrentUser.id();
        if (file.isEmpty()) throw ApiException.badRequest("The file is empty");
        try {
            return stockPrices.loadFile(file.getBytes(), file.getOriginalFilename());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(e.getMessage());
        }
    }
}
