package com.expensetracker.networth;

import com.expensetracker.audit.AuditService;
import com.expensetracker.common.ApiException;
import com.expensetracker.common.TimeConfig;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Service
public class HoldingService {

    public record HoldingRequest(AssetClass assetClass, String name, String symbol, String isin, String schemeCode, String folio,
                                 String platform, Allocation allocation, ValuationMethod valuationMethod, BigDecimal units,
                                 BigDecimal investedAmount, BigDecimal currentPrice, BigDecimal manualValue, BigDecimal interestRate,
                                 Integer compoundingPerYear, LocalDate startDate, LocalDate maturityDate, String notes, Long version,
                                 HoldingOwner owner) {
    }

    public record HoldingDto(UUID id, AssetClass assetClass, String assetClassLabel, String name, String symbol, String isin, String schemeCode,
                             String folio, String platform, Allocation allocation, ValuationMethod valuationMethod, BigDecimal units,
                             BigDecimal investedAmount, BigDecimal currentPrice, BigDecimal manualValue, BigDecimal interestRate,
                             Integer compoundingPerYear, LocalDate startDate, LocalDate maturityDate, Instant priceAsOf,
                             Holding.PriceSource priceSource, BigDecimal value, BigDecimal gain, BigDecimal gainPct, boolean estimated,
                             String basis, String notes, boolean archived, long version, BigDecimal accruedInterest, LocalDate interestAccruedTo,
                             HoldingOwner owner) {
    }

    public record ImportResult(int rows, int created, int updated, int linkedToAmfi, List<String> errors) {
    }

    private final HoldingRepository repository;
    private final PriceService prices;
    private final StockPriceService stockPrices;
    private final AuditService audit;
    private final Clock clock;

    public HoldingService(HoldingRepository repository, PriceService prices, StockPriceService stockPrices, AuditService audit, Clock clock) {
        this.repository = repository;
        this.prices = prices;
        this.stockPrices = stockPrices;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<HoldingDto> list(UUID userId) {
        LocalDate today = TimeConfig.today(clock);
        return repository.findByUserIdOrderByAssetClassAscNameAsc(userId).stream().map(h -> toDto(h, today)).toList();
    }

    @Transactional
    public HoldingDto create(UUID userId, HoldingRequest req) {
        if (req.assetClass() == null) throw ApiException.badRequest("Choose what kind of asset this is");
        Holding h = new Holding(userId, AssetClass.refine(req.assetClass(), req.name()), requireName(req.name()));
        apply(h, req, true);
        if (h.getValuationMethod() == ValuationMethod.MANUAL) {
            if (h.getInterestRate() == null) h.setInterestRate(InterestAccrual.defaultRate(h.getAssetClass()));
            if (h.getInterestRate() != null) h.setInterestAccruedTo(TimeConfig.today(clock)); // interest counts from today
        }
        h = saveChecked(h, true);
        audit.record(userId, "HOLDING_CREATED", "holding", h.getId(), h.getAssetClass() + " " + h.getName());
        return toDto(h, TimeConfig.today(clock));
    }

    @Transactional
    public HoldingDto update(UUID userId, UUID id, HoldingRequest req) {
        Holding h = require(userId, id);
        if (req.version() != null && req.version() != h.getVersion()) throw ApiException.conflict("This holding changed elsewhere. Reload and try again.");
        if (req.assetClass() != null) h.setAssetClass(req.assetClass());
        if (req.name() != null) h.setName(requireName(req.name()));
        apply(h, req, false);
        if (h.getValuationMethod() == ValuationMethod.MANUAL && h.getInterestRate() != null && h.getInterestAccruedTo() == null) {
            h.setInterestAccruedTo(TimeConfig.today(clock)); // a rate was just added: interest counts from today
        }
        saveChecked(h, false);
        audit.record(userId, "HOLDING_UPDATED", "holding", id, h.getName());
        return toDto(h, TimeConfig.today(clock));
    }

    public record LatestPrice(HoldingDto holding, BigDecimal previousPrice, BigDecimal price, LocalDate priceDate, String source, String message) {
    }

    @Transactional(readOnly = true)
    public HoldingDto get(UUID userId, UUID id) {
        return toDto(require(userId, id), TimeConfig.today(clock));
    }

    /**
     * Applies the newest cached official price to one holding (AMFI NAV for a fund, NSE/BSE close for a listed
     * security), replacing a price typed by hand, so automatic updates carry on from here.
     */
    @Transactional
    public LatestPrice applyLatestPrice(UUID userId, UUID id) {
        Holding h = require(userId, id);
        if (h.getValuationMethod() != ValuationMethod.MARKET_PRICE) {
            throw ApiException.unprocessable("This one is valued from figures you enter, so there's no market price to fetch. Use Edit to change its value.");
        }
        BigDecimal before = h.getCurrentPrice();
        if (h.getAssetClass() == AssetClass.MUTUAL_FUND) {
            if (h.getSchemeCode() == null && h.getIsin() != null) prices.byIsin(h.getIsin()).ifPresent(s -> h.setSchemeCode(s.schemeCode()));
            if (h.getSchemeCode() == null) prices.matchByName(h.getName()).ifPresent(s -> PriceService.link(h, s));
            if (h.getSchemeCode() == null) {
                throw new ApiException(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY, "FUND_NOT_LINKED",
                        "Which fund is this? Pick it from AMFI's list so Arth can look up its NAV.");
            }
            var scheme = prices.byCode(h.getSchemeCode()).orElseThrow(() -> ApiException.unprocessable(
                    "AMFI's fund list isn't loaded yet. Try again in a minute."));
            if (scheme.nav() == null || scheme.navDate() == null) {
                throw ApiException.unprocessable("AMFI has no current NAV for this scheme (it may be closed or merged).");
            }
            h.applyPrice(scheme.nav(), scheme.navDate().atTime(23, 0).atZone(TimeConfig.IST).toInstant(), Holding.PriceSource.AMFI);
            return new LatestPrice(toDto(h, TimeConfig.today(clock)), before, scheme.nav(), scheme.navDate(), "AMFI NAV",
                    "NAV ₹" + scheme.nav().stripTrailingZeros().toPlainString() + " for " + scheme.navDate() + " from AMFI");
        }
        if (StockPriceService.LISTED.contains(h.getAssetClass())) {
            if (h.getIsin() == null && h.getSymbol() == null) {
                throw ApiException.unprocessable("Add the NSE/BSE symbol or ISIN (Edit) so its price can be looked up.");
            }
            var sec = stockPrices.find(h.getIsin(), h.getSymbol()).orElseThrow(() -> ApiException.unprocessable(
                    "Couldn't find " + (h.getSymbol() != null ? h.getSymbol() : h.getIsin()) + " in today's NSE/BSE list. Check the symbol or ISIN with Edit."));
            if (h.getIsin() == null) h.setIsin(sec.isin());
            h.applyPrice(sec.close(), StockPriceService.closeOf(sec.priceDate()), Holding.PriceSource.EXCHANGE);
            return new LatestPrice(toDto(h, TimeConfig.today(clock)), before, sec.close(), sec.priceDate(), sec.exchange() + " close",
                    sec.exchange() + " close ₹" + sec.close().stripTrailingZeros().toPlainString() + " for " + sec.priceDate());
        }
        throw ApiException.unprocessable("There's no free official price feed for this kind of asset. Use \"Enter a price yourself\".");
    }

    @Transactional
    public HoldingDto updatePrice(UUID userId, UUID id, BigDecimal price) {
        Holding h = require(userId, id);
        if (price == null || price.signum() < 0) throw ApiException.badRequest("Price must be zero or more");
        if (h.getValuationMethod() == ValuationMethod.MANUAL) h.setManualValue(price.setScale(2, RoundingMode.HALF_UP));
        else h.applyPrice(price, clock.instant(), Holding.PriceSource.MANUAL);
        return toDto(h, TimeConfig.today(clock));
    }

    @Transactional
    public void setArchived(UUID userId, UUID id, boolean archived) {
        require(userId, id).setArchived(archived);
        audit.record(userId, archived ? "HOLDING_ARCHIVED" : "HOLDING_RESTORED", "holding", id, null);
    }

    @Transactional
    public void delete(UUID userId, UUID id) {
        Holding h = require(userId, id);
        repository.delete(h);
        audit.record(userId, "HOLDING_DELETED", "holding", id, h.getName());
    }

    /**
     * Imports a broker/MF platform holdings export. Matches existing holdings by ISIN (+ folio/platform) or by name,
     * and replaces their units, cost and price, so re-importing each month keeps everything current.
     */
    @Transactional
    public ImportResult importCsv(UUID userId, String content, String platform, AssetClass forcedClass) {
        return importCsv(userId, content, platform, forcedClass, HoldingOwner.SELF);
    }

    /** As above, for one owner's holdings: rows match (and update) only that owner's holdings, and new ones belong to them. */
    @Transactional
    public ImportResult importCsv(UUID userId, String content, String platform, AssetClass forcedClass, HoldingOwner owner) {
        HoldingOwner who = owner == null ? HoldingOwner.SELF : owner;
        HoldingsCsvParser.Result parsed;
        try {
            parsed = HoldingsCsvParser.parse(content);
        } catch (IllegalArgumentException e) {
            throw ApiException.unprocessable(e.getMessage());
        }
        String plat = platform == null || platform.isBlank() ? null : platform.trim();
        List<Holding> mine = repository.findByUserIdAndOwnerAndArchivedFalse(userId, who);
        int created = 0, updated = 0, linked = 0;
        List<String> errors = new ArrayList<>(parsed.errors());
        Instant now = clock.instant();
        for (HoldingsCsvParser.HoldingRow row : parsed.rows()) {
            AssetClass cls = forcedClass != null ? forcedClass
                    : row.looksLikeFund() ? AssetClass.refine(AssetClass.MUTUAL_FUND, row.name())
                    : row.name().toUpperCase(Locale.ROOT).matches(".*(ETF|BEES|NIFTYBEES|GOLDBEES).*") ? AssetClass.ETF : AssetClass.STOCK;
            Holding h = find(mine, row, plat).orElse(null);
            boolean isNew = h == null;
            if (isNew) {
                h = new Holding(userId, cls, row.name().length() > 200 ? row.name().substring(0, 200) : row.name());
                h.setPlatform(plat);
                h.setOwner(who);
                h.setIsin(row.isin());
                h.setSymbol(row.symbol());
                h.setFolio(row.folio());
            }
            h.setValuationMethod(ValuationMethod.MARKET_PRICE);
            BigDecimal units = row.quantity() != null ? row.quantity()
                    : (row.price() != null && row.price().signum() > 0 && row.currentValue() != null ? row.currentValue().divide(row.price(), 6, RoundingMode.HALF_UP) : null);
            if (units == null) {
                errors.add("Row " + row.rowNumber() + ": couldn't work out units for " + row.name());
                continue;
            }
            h.setUnits(units.setScale(6, RoundingMode.HALF_UP));
            if (row.invested() != null) h.setInvestedAmount(row.invested().setScale(2, RoundingMode.HALF_UP));
            if (row.price() != null) h.applyPrice(row.price(), now, Holding.PriceSource.BROKER_CSV);
            if (cls == AssetClass.MUTUAL_FUND && h.getSchemeCode() == null && row.isin() != null) {
                Optional<PriceService.Scheme> s = prices.byIsin(row.isin());
                if (s.isPresent()) {
                    h.setSchemeCode(s.get().schemeCode());
                    h.setAllocation(AssetClass.allocationForFundCategory(s.get().category()));
                    linked++;
                }
            }
            if (cls == AssetClass.MUTUAL_FUND && h.getSchemeCode() == null && h.getIsin() == null) {
                // exports without ISINs (e.g. Groww): link by the fund's name when it points to exactly one scheme
                final Holding target = h;
                Optional<PriceService.Scheme> s = prices.matchByName(row.name());
                if (s.isPresent()) {
                    target.setSchemeCode(s.get().schemeCode());
                    target.setAllocation(AssetClass.allocationForFundCategory(s.get().category()));
                    String isin = s.get().isinGrowth() != null ? s.get().isinGrowth() : s.get().isinReinvest();
                    boolean taken = isin != null && mine.stream().anyMatch(o -> o != target && isin.equals(o.getIsin())
                            && java.util.Objects.equals(o.getFolio(), target.getFolio()) && java.util.Objects.equals(o.getPlatform(), target.getPlatform()));
                    if (isin != null && !taken) target.setIsin(isin);
                    linked++;
                }
            }
            if (isNew) {
                h = repository.save(h);
                mine.add(h);
                created++;
            } else {
                updated++;
            }
        }
        audit.record(userId, "HOLDINGS_IMPORTED", "holding", null, created + " new, " + updated + " updated" + (plat == null ? "" : " from " + plat)
                + (who == HoldingOwner.SELF ? "" : " for " + who.label().toLowerCase(Locale.ROOT)));
        return new ImportResult(parsed.rows().size(), created, updated, linked, errors);
    }

    private Optional<Holding> find(List<Holding> mine, HoldingsCsvParser.HoldingRow row, String platform) {
        if (row.isin() != null) {
            Optional<Holding> byIsin = mine.stream().filter(h -> row.isin().equals(h.getIsin())
                    && eq(h.getFolio(), row.folio()) && eq(h.getPlatform(), platform)).findFirst();
            if (byIsin.isPresent()) return byIsin;
        }
        return mine.stream().filter(h -> h.getName().equalsIgnoreCase(row.name()) && eq(h.getPlatform(), platform)
                && (row.symbol() == null || h.getSymbol() == null || row.symbol().equalsIgnoreCase(h.getSymbol()))).findFirst();
    }

    private static boolean eq(String a, String b) {
        return (a == null || a.isBlank()) ? (b == null || b.isBlank()) : a.equalsIgnoreCase(b == null ? "" : b);
    }

    private void apply(Holding h, HoldingRequest r, boolean creating) {
        if (r.symbol() != null) h.setSymbol(blank(r.symbol()));
        if (r.isin() != null) {
            String isin = blank(r.isin()) == null ? null : r.isin().trim().toUpperCase(Locale.ROOT);
            if (isin != null && !isin.matches("[A-Z]{2}[A-Z0-9]{9}[0-9]")) throw ApiException.badRequest("ISIN should look like INF879O01027");
            h.setIsin(isin);
        }
        if (r.folio() != null) h.setFolio(blank(r.folio()));
        if (r.platform() != null) h.setPlatform(blank(r.platform()));
        if (r.allocation() != null) h.setAllocation(r.allocation());
        if (r.valuationMethod() != null) h.setValuationMethod(r.valuationMethod());
        if (r.units() != null) h.setUnits(nonNegative(r.units(), "Units").setScale(6, RoundingMode.HALF_UP));
        if (r.investedAmount() != null) h.setInvestedAmount(nonNegative(r.investedAmount(), "Amount invested").setScale(2, RoundingMode.HALF_UP));
        if (r.manualValue() != null) h.setManualValue(nonNegative(r.manualValue(), "Current value").setScale(2, RoundingMode.HALF_UP));
        if (r.interestRate() != null) {
            if (r.interestRate().compareTo(BigDecimal.ZERO) < 0 || r.interestRate().compareTo(new BigDecimal("50")) > 0) throw ApiException.badRequest("Interest rate looks wrong");
            h.setInterestRate(r.interestRate());
        }
        if (r.compoundingPerYear() != null) {
            if (!List.of(1, 2, 4, 12).contains(r.compoundingPerYear())) throw ApiException.badRequest("Compounding must be yearly, half-yearly, quarterly or monthly");
            h.setCompoundingPerYear(r.compoundingPerYear());
        }
        if (r.startDate() != null) {
            if (r.startDate().isAfter(TimeConfig.today(clock))) throw ApiException.badRequest("The start date can't be in the future");
            h.setStartDate(r.startDate());
        }
        if (r.maturityDate() != null) h.setMaturityDate(r.maturityDate());
        if (r.notes() != null) h.setNotes(blank(r.notes()));
        if (r.owner() != null) h.setOwner(r.owner());
        if (r.schemeCode() != null) {
            String code = blank(r.schemeCode());
            h.setSchemeCode(code);
            if (code != null) {
                prices.byCode(code).ifPresent(s -> {
                    if (h.getIsin() == null) h.setIsin(s.isinGrowth() != null ? s.isinGrowth() : s.isinReinvest());
                    if (r.allocation() == null) h.setAllocation(AssetClass.allocationForFundCategory(s.category()));
                    if (s.nav() != null && s.navDate() != null && r.currentPrice() == null) {
                        h.applyPrice(s.nav(), s.navDate().atTime(23, 0).atZone(TimeConfig.IST).toInstant(), Holding.PriceSource.AMFI);
                    }
                });
            }
        }
        if (r.currentPrice() != null) h.applyPrice(nonNegative(r.currentPrice(), "Price"), clock.instant(), Holding.PriceSource.MANUAL);
        else if (StockPriceService.LISTED.contains(h.getAssetClass()) && h.getValuationMethod() == ValuationMethod.MARKET_PRICE
                && (h.getIsin() != null || h.getSymbol() != null) && (h.getPriceAsOf() == null || h.getPriceSource() == Holding.PriceSource.EXCHANGE)) {
            // a listed stock/ETF/SGB with no price of its own: use the latest exchange close
            stockPrices.find(h.getIsin(), h.getSymbol()).ifPresent(s -> {
                if (h.getIsin() == null) h.setIsin(s.isin());
                h.applyPrice(s.close(), StockPriceService.closeOf(s.priceDate()), Holding.PriceSource.EXCHANGE);
            });
        }
        validate(h);
    }

    private static void validate(Holding h) {
        switch (h.getValuationMethod()) {
            case MARKET_PRICE -> {
                if (h.getUnits() == null) throw ApiException.badRequest("Enter how many units or shares you hold");
            }
            case MANUAL -> {
                if (h.getManualValue() == null) throw ApiException.badRequest("Enter the current value");
            }
            case FIXED_RATE -> {
                if (h.getInvestedAmount() == null || h.getInterestRate() == null || h.getStartDate() == null) {
                    throw ApiException.badRequest("Enter the amount, interest rate and start date");
                }
                if (h.getMaturityDate() != null && h.getMaturityDate().isBefore(h.getStartDate())) throw ApiException.badRequest("Maturity is before the start date");
            }
        }
    }

    HoldingDto toDto(Holding h, LocalDate today) {
        Valuation.Result v = Valuation.value(h.valuationInputs(), today);
        return new HoldingDto(h.getId(), h.getAssetClass(), h.getAssetClass().label(), h.getName(), h.getSymbol(), h.getIsin(), h.getSchemeCode(),
                h.getFolio(), h.getPlatform(), h.getAllocation(), h.getValuationMethod(), h.getUnits(), h.getInvestedAmount(), h.getCurrentPrice(),
                h.getManualValue(), h.getInterestRate(), h.getCompoundingPerYear(), h.getStartDate(), h.getMaturityDate(), h.getPriceAsOf(),
                h.getPriceSource(), v.value(), v.gain(), v.gainPct(), v.estimated(), v.basis(), h.getNotes(), h.isArchived(), h.getVersion(),
                h.getAccruedInterest(), h.getInterestAccruedTo(), h.getOwner());
    }

    /** Saves, turning "this fund is already there" into a message that says which list it's in. */
    private Holding saveChecked(Holding h, boolean creating) {
        try {
            return creating ? repository.saveAndFlush(h) : flushAndReturn(h);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            String whose = h.getOwner() == HoldingOwner.PARENTS ? "your parents' investments" : "your investments";
            throw ApiException.conflict("This fund is already in " + whose + " with the same folio and platform. Edit that one instead, "
                    + "or enter a different folio or platform.");
        }
    }

    private Holding flushAndReturn(Holding h) {
        repository.flush();
        return h;
    }

    private Holding require(UUID userId, UUID id) {
        return repository.findByIdAndUserId(id, userId).orElseThrow(() -> ApiException.notFound("Holding"));
    }

    private static String requireName(String n) {
        if (n == null || n.isBlank()) throw ApiException.badRequest("Name is required");
        String t = n.trim();
        if (t.length() > 200) throw ApiException.badRequest("Name is too long");
        return t;
    }

    private static BigDecimal nonNegative(BigDecimal v, String what) {
        if (v.signum() < 0) throw ApiException.badRequest(what + " can't be negative");
        return v;
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
