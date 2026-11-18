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

    public NetWorthController(NetWorthService netWorth, HoldingService holdings, LiabilityService liabilities, PriceService prices,
                              StockPriceService stockPrices, PriceRefresher refresher) {
        this.netWorth = netWorth;
        this.holdings = holdings;
        this.liabilities = liabilities;
        this.prices = prices;
        this.stockPrices = stockPrices;
        this.refresher = refresher;
    }

    @GetMapping
    public NetWorthService.Summary summary() {
        refresher.refreshIfStale("net worth opened");
        return netWorth.summary(CurrentUser.id());
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

    /** Refreshes mutual fund NAVs (AMFI) and exchange closing prices (NSE/BSE) together. */
    @PostMapping("/prices/refresh")
    public CombinedRefresh refreshPrices() {
        CurrentUser.id();
        PriceService.RefreshResult funds = prices.refreshMutualFunds();
        StockPriceService.RefreshResult stocks = stockPrices.refresh();
        List<String> parts = new java.util.ArrayList<>();
        parts.add(funds.ok() ? "Fund NAVs" + (funds.latestNavDate() == null ? "" : " for " + funds.latestNavDate()) : "Fund NAVs failed: " + funds.message());
        parts.add(stocks.ok() ? "stock prices" + (stocks.priceDate() == null ? "" : " for " + stocks.priceDate()) : "stock prices failed: " + stocks.message());
        return new CombinedRefresh(funds.ok() || stocks.ok(), funds.holdingsUpdated() + stocks.holdingsUpdated(), funds.latestNavDate(),
                String.join("; ", parts), funds, stocks);
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
