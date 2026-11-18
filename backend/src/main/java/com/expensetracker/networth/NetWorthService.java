package com.expensetracker.networth;

import com.expensetracker.account.AccountService;
import com.expensetracker.account.AccountType;
import com.expensetracker.common.TimeConfig;
import com.expensetracker.user.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Net worth = everything you own (bank and cash balances, investments, property, other assets)
 * − everything you owe (credit card outstanding, loans). Every figure says where it came from.
 */
@Service
public class NetWorthService {

    public record Item(String kind, UUID id, String name, String detail, BigDecimal value, BigDecimal invested, BigDecimal gain,
                       BigDecimal gainPct, String source, boolean estimated, String asOf) {
    }

    public record Group(String key, String label, BigDecimal value, BigDecimal invested, BigDecimal gain, BigDecimal gainPct, List<Item> items) {
    }

    public record AllocationSlice(Allocation bucket, BigDecimal value, BigDecimal share) {
    }

    public record Change(LocalDate since, BigDecimal amount, BigDecimal pct) {
    }

    public record Summary(BigDecimal netWorth, BigDecimal assets, BigDecimal liabilities, BigDecimal investedInHoldings,
                          BigDecimal holdingsValue, BigDecimal holdingsGain, BigDecimal holdingsGainPct, List<Group> assetGroups,
                          List<Group> liabilityGroups, List<AllocationSlice> allocation, Change change30d, Change changeYtd,
                          int estimatedCount, String pricesNote) {
    }

    public record Point(LocalDate date, BigDecimal assets, BigDecimal liabilities, BigDecimal netWorth) {
    }

    private static final Logger log = LoggerFactory.getLogger(NetWorthService.class);

    private final AccountService accounts;
    private final HoldingRepository holdings;
    private final LiabilityRepository liabilities;
    private final NetWorthSnapshotRepository snapshots;
    private final HoldingService holdingService;
    private final UserRepository users;
    private final PriceService prices;
    private final StockPriceService stockPrices;
    private final ObjectMapper json;
    private final Clock clock;

    public NetWorthService(AccountService accounts, HoldingRepository holdings, LiabilityRepository liabilities,
                           NetWorthSnapshotRepository snapshots, HoldingService holdingService, UserRepository users,
                           PriceService prices, StockPriceService stockPrices, ObjectMapper json, Clock clock) {
        this.accounts = accounts;
        this.holdings = holdings;
        this.liabilities = liabilities;
        this.snapshots = snapshots;
        this.holdingService = holdingService;
        this.users = users;
        this.prices = prices;
        this.stockPrices = stockPrices;
        this.json = json;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Summary summary(UUID userId) {
        LocalDate today = TimeConfig.today(clock);
        List<Item> cashItems = new ArrayList<>();
        List<Item> cardItems = new ArrayList<>();
        for (AccountService.AccountDto a : accounts.list(userId)) {
            if (a.archived()) continue;
            String source = "Opening balance + imported transactions";
            if (a.type() == AccountType.CREDIT_CARD && a.balance().signum() < 0) {
                cardItems.add(new Item("ACCOUNT", a.id(), a.name(), "Credit card outstanding", a.balance().negate(), null, null, null, source, false, null));
            } else {
                cashItems.add(new Item("ACCOUNT", a.id(), a.name(), label(a.type()), a.balance(), null, null, null, source, false, null));
            }
        }

        Map<AssetClass, List<Item>> byClass = new EnumMap<>(AssetClass.class);
        Map<Allocation, BigDecimal> alloc = new EnumMap<>(Allocation.class);
        BigDecimal holdingsValue = BigDecimal.ZERO, invested = BigDecimal.ZERO, investedWithKnownCost = BigDecimal.ZERO, valueWithKnownCost = BigDecimal.ZERO;
        int estimated = 0;
        for (Holding h : holdings.findByUserIdAndArchivedFalse(userId)) {
            HoldingService.HoldingDto d = holdingService.toDto(h, today);
            byClass.computeIfAbsent(h.getAssetClass(), k -> new ArrayList<>()).add(new Item("HOLDING", h.getId(), h.getName(), detail(d), d.value(),
                    d.investedAmount(), d.gain(), d.gainPct(), sourceLabel(d), d.estimated(), d.priceAsOf() == null ? null : d.priceAsOf().toString()));
            holdingsValue = holdingsValue.add(d.value());
            if (d.investedAmount() != null) {
                invested = invested.add(d.investedAmount());
                investedWithKnownCost = investedWithKnownCost.add(d.investedAmount());
                valueWithKnownCost = valueWithKnownCost.add(d.value());
            }
            if (d.estimated()) estimated++;
            alloc.merge(h.getAllocation(), d.value(), BigDecimal::add);
        }
        BigDecimal cash = cashItems.stream().map(Item::value).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (cash.signum() != 0) alloc.merge(Allocation.CASH, cash, BigDecimal::add);

        List<Group> assetGroups = new ArrayList<>();
        if (!cashItems.isEmpty()) assetGroups.add(group("CASH", "Bank & cash", cashItems));
        byClass.forEach((cls, items) -> assetGroups.add(group(cls.name(), cls.label(), items)));

        List<Item> loanItems = new ArrayList<>();
        for (Liability l : liabilities.findByUserIdAndArchivedFalse(userId)) {
            String detail = (l.getLender() == null ? "" : l.getLender() + ", ") + (l.getInterestRate() == null ? "" : l.getInterestRate().stripTrailingZeros().toPlainString() + "%")
                    + (l.getEmi() == null ? "" : ", EMI " + com.expensetracker.analytics.InrFormat.format(l.getEmi()));
            loanItems.add(new Item("LIABILITY", l.getId(), l.getName(), detail.replaceAll("^, |, $", ""), l.getOutstanding(), null, null, null,
                    "Outstanding you entered on " + l.getAsOf(), false, l.getAsOf().toString()));
        }
        List<Group> liabilityGroups = new ArrayList<>();
        if (!cardItems.isEmpty()) liabilityGroups.add(group("CARDS", "Credit cards", cardItems));
        if (!loanItems.isEmpty()) liabilityGroups.add(group("LOANS", "Loans", loanItems));

        BigDecimal assets = cash.add(holdingsValue).setScale(2, RoundingMode.HALF_UP);
        BigDecimal owed = liabilityGroups.stream().map(Group::value).reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
        BigDecimal net = assets.subtract(owed);

        BigDecimal positiveTotal = alloc.values().stream().filter(v -> v.signum() > 0).reduce(BigDecimal.ZERO, BigDecimal::add);
        List<AllocationSlice> allocation = alloc.entrySet().stream().filter(e -> e.getValue().signum() > 0)
                .sorted(Map.Entry.<Allocation, BigDecimal>comparingByValue().reversed())
                .map(e -> new AllocationSlice(e.getKey(), e.getValue().setScale(2, RoundingMode.HALF_UP),
                        positiveTotal.signum() > 0 ? e.getValue().multiply(BigDecimal.valueOf(100)).divide(positiveTotal, 1, RoundingMode.HALF_UP) : BigDecimal.ZERO))
                .toList();

        BigDecimal gain = valueWithKnownCost.subtract(investedWithKnownCost).setScale(2, RoundingMode.HALF_UP);
        BigDecimal gainPct = investedWithKnownCost.signum() > 0 ? gain.multiply(BigDecimal.valueOf(100)).divide(investedWithKnownCost, 2, RoundingMode.HALF_UP) : null;
        String fundNote = prices.lastRefresh().map(r -> r.ok() ? "Fund NAVs from AMFI" + (r.latestNavDate() == null ? "" : " as of " + r.latestNavDate())
                : "Last NAV refresh failed: " + r.message()).orElse("Fund NAVs refresh nightly from AMFI");
        String stockNote = stockPrices.lastRefresh().map(r -> r.ok() ? "stock, ETF and SGB closing prices from NSE/BSE" + (r.priceDate() == null ? "" : " as of " + r.priceDate())
                : "last stock price refresh failed").orElse("stock prices refresh every trading evening from NSE/BSE");
        String pricesNote = fundNote + "; " + stockNote;

        return new Summary(net, assets, owed, invested.setScale(2, RoundingMode.HALF_UP), holdingsValue.setScale(2, RoundingMode.HALF_UP), gain, gainPct,
                assetGroups, liabilityGroups, allocation, change(userId, today.minusDays(30), net), change(userId, LocalDate.of(today.getYear(), 1, 1), net),
                estimated, pricesNote);
    }

    /** Records today's net worth (one row per day, replaced on each change). */
    @Transactional
    public void snapshot(UUID userId) {
        Summary s = summary(userId);
        LocalDate today = TimeConfig.today(clock);
        NetWorthSnapshot snap = snapshots.findByUserIdAndSnapshotDate(userId, today).orElseGet(() -> new NetWorthSnapshot(userId, today));
        Map<String, BigDecimal> breakdown = new LinkedHashMap<>();
        s.assetGroups().forEach(g -> breakdown.put(g.key(), g.value()));
        s.liabilityGroups().forEach(g -> breakdown.put("-" + g.key(), g.value()));
        String breakdownJson;
        try {
            breakdownJson = json.writeValueAsString(breakdown);
        } catch (Exception e) {
            breakdownJson = null;
        }
        snap.set(s.assets(), s.liabilities(), breakdownJson);
        snapshots.save(snap);
    }

    @Transactional(readOnly = true)
    public List<Point> history(UUID userId, int months) {
        LocalDate from = TimeConfig.today(clock).minusMonths(Math.max(1, Math.min(months, 120)));
        return snapshots.findByUserIdAndSnapshotDateGreaterThanEqualOrderBySnapshotDateAsc(userId, from).stream()
                .map(s -> new Point(s.getSnapshotDate(), s.getAssets(), s.getLiabilities(), s.getNetWorth())).toList();
    }

    /** Nightly, after the NAV refresh: one snapshot per user so the trend line has a point every day. */
    @Scheduled(cron = "0 45 23 * * *", zone = "Asia/Kolkata")
    public void nightlySnapshots() {
        for (UUID id : users.findAll().stream().map(com.expensetracker.user.User::getId).toList()) {
            try {
                snapshot(id);
            } catch (Exception e) {
                log.warn("Net worth snapshot failed for user {}: {}", id, e.toString());
            }
        }
    }

    private Change change(UUID userId, LocalDate since, BigDecimal current) {
        Optional<NetWorthSnapshot> base = snapshots.findFirstByUserIdAndSnapshotDateLessThanEqualOrderBySnapshotDateDesc(userId, since);
        if (base.isEmpty()) return null;
        BigDecimal then = base.get().getNetWorth();
        BigDecimal diff = current.subtract(then);
        BigDecimal pct = then.signum() != 0 ? diff.multiply(BigDecimal.valueOf(100)).divide(then.abs(), 2, RoundingMode.HALF_UP) : null;
        return new Change(base.get().getSnapshotDate(), diff, pct);
    }

    private static Group group(String key, String label, List<Item> items) {
        items.sort(Comparator.comparing(Item::value).reversed());
        BigDecimal value = items.stream().map(Item::value).reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
        List<Item> withCost = items.stream().filter(i -> i.invested() != null).toList();
        BigDecimal invested = withCost.isEmpty() ? null : withCost.stream().map(Item::invested).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal gain = invested == null ? null : withCost.stream().map(Item::value).reduce(BigDecimal.ZERO, BigDecimal::add).subtract(invested).setScale(2, RoundingMode.HALF_UP);
        BigDecimal pct = invested != null && invested.signum() > 0 ? gain.multiply(BigDecimal.valueOf(100)).divide(invested, 2, RoundingMode.HALF_UP) : null;
        return new Group(key, label, value, invested, gain, pct, items);
    }

    private static String label(AccountType t) {
        return switch (t) {
            case BANK -> "Bank account";
            case CASH -> "Cash";
            case WALLET -> "Wallet";
            case CREDIT_CARD -> "Credit card (in credit)";
        };
    }

    private static String detail(HoldingService.HoldingDto d) {
        List<String> parts = new ArrayList<>();
        if (d.units() != null && d.valuationMethod() == ValuationMethod.MARKET_PRICE) {
            parts.add(d.units().stripTrailingZeros().toPlainString() + (d.assetClass() == AssetClass.GOLD ? " g" : " units"));
        }
        if (d.interestRate() != null) parts.add(d.interestRate().stripTrailingZeros().toPlainString() + "%");
        if (d.maturityDate() != null) parts.add("matures " + d.maturityDate());
        if (d.platform() != null) parts.add(d.platform());
        return String.join(", ", parts);
    }

    private static String sourceLabel(HoldingService.HoldingDto d) {
        if (d.valuationMethod() == ValuationMethod.MANUAL) return "Value you entered";
        if (d.valuationMethod() == ValuationMethod.FIXED_RATE) return "Estimated from rate";
        if (d.priceSource() == null) return "No price yet";
        String on = d.priceAsOf() == null ? "" : " · " + InrDates.shortDate(d.priceAsOf().atZone(com.expensetracker.common.TimeConfig.IST).toLocalDate());
        return switch (d.priceSource()) {
            case AMFI -> "AMFI NAV" + on;
            case BROKER_CSV -> "Broker export" + on;
            case MANUAL -> "Price you entered" + on;
            case CALCULATED -> "Calculated";
            case EXCHANGE -> "NSE/BSE close" + on;
        };
    }
}
