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

    /** {@code dayChange}: how much the value moved on the latest NAV/close day ({@code dayChangeOn}), from the official prices. */
    public record Item(String kind, UUID id, String name, String detail, BigDecimal value, BigDecimal invested, BigDecimal gain,
                       BigDecimal gainPct, String source, boolean estimated, String asOf, BigDecimal dayChange, BigDecimal dayChangePct,
                       LocalDate dayChangeOn, BigDecimal xirr, boolean xirrEstimated, LocalDate investingSince) {
    }

    public record Group(String key, String label, BigDecimal value, BigDecimal invested, BigDecimal gain, BigDecimal gainPct, List<Item> items,
                        BigDecimal dayChange, BigDecimal dayChangePct, BigDecimal xirr, int xirrCount) {
    }

    private record DayMove(BigDecimal change, BigDecimal base, LocalDate on) {
    }

    public record AllocationSlice(Allocation bucket, BigDecimal value, BigDecimal share) {
    }

    public record Change(LocalDate since, BigDecimal amount, BigDecimal pct) {
    }

    public record Summary(BigDecimal netWorth, BigDecimal assets, BigDecimal liabilities, BigDecimal investedInHoldings,
                          BigDecimal holdingsValue, BigDecimal holdingsGain, BigDecimal holdingsGainPct, List<Group> assetGroups,
                          List<Group> liabilityGroups, List<AllocationSlice> allocation, Change change30d, Change changeYtd,
                          int estimatedCount, String pricesNote, BigDecimal holdingsDayChange, BigDecimal holdingsDayChangePct,
                          Change sincePrevious, Change sinceStart, BigDecimal holdingsXirr, int holdingsXirrCount, int holdingsCount) {
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
    private final XirrService xirr;
    private final ObjectMapper json;
    private final Clock clock;

    public NetWorthService(AccountService accounts, HoldingRepository holdings, LiabilityRepository liabilities,
                           NetWorthSnapshotRepository snapshots, HoldingService holdingService, UserRepository users,
                           PriceService prices, StockPriceService stockPrices, XirrService xirr, ObjectMapper json, Clock clock) {
        this.accounts = accounts;
        this.holdings = holdings;
        this.liabilities = liabilities;
        this.snapshots = snapshots;
        this.holdingService = holdingService;
        this.users = users;
        this.prices = prices;
        this.stockPrices = stockPrices;
        this.xirr = xirr;
        this.json = json;
        this.clock = clock;
    }

    /** Your net worth: bank and cash, your own investments (not parents'), minus cards and loans. */
    @Transactional(readOnly = true)
    public Summary summary(UUID userId) {
        return build(userId, HoldingOwner.SELF, true);
    }

    /**
     * One owner's investments on their own (e.g. parents' holdings you manage): values, gains, XIRR and the day's
     * move, with no bank accounts, loans or history. Parents' holdings never count in your net worth.
     */
    @Transactional(readOnly = true)
    public Summary portfolio(UUID userId, HoldingOwner owner) {
        return build(userId, owner == null ? HoldingOwner.SELF : owner, false);
    }

    private Summary build(UUID userId, HoldingOwner owner, boolean withAccounts) {
        LocalDate today = TimeConfig.today(clock);
        List<Item> cashItems = new ArrayList<>();
        List<Item> cardItems = new ArrayList<>();
        for (AccountService.AccountDto a : withAccounts ? accounts.list(userId) : List.<AccountService.AccountDto>of()) {
            if (a.archived()) continue;
            String source = "Opening balance + imported transactions";
            if (a.type() == AccountType.CREDIT_CARD && a.balance().signum() < 0) {
                cardItems.add(new Item("ACCOUNT", a.id(), a.name(), "Credit card outstanding", a.balance().negate(), null, null, null, source, false, null, null, null, null, null, false, null));
            } else {
                cashItems.add(new Item("ACCOUNT", a.id(), a.name(), label(a.type()), a.balance(), null, null, null, source, false, null, null, null, null, null, false, null));
            }
        }

        Map<AssetClass, List<Item>> byClass = new EnumMap<>(AssetClass.class);
        Map<Allocation, BigDecimal> alloc = new EnumMap<>(Allocation.class);
        BigDecimal holdingsValue = BigDecimal.ZERO, invested = BigDecimal.ZERO, investedWithKnownCost = BigDecimal.ZERO, valueWithKnownCost = BigDecimal.ZERO;
        int estimated = 0;
        List<Holding> mine = holdings.findByUserIdAndOwnerAndArchivedFalse(userId, owner);
        Map<String, PriceService.Scheme> schemes = prices.byCodes(mine.stream().map(Holding::getSchemeCode).filter(java.util.Objects::nonNull).distinct().toList());
        Map<String, StockPriceService.Security> closes = stockPrices.byIsins(mine.stream().map(Holding::getIsin).filter(java.util.Objects::nonNull).distinct().toList());
        BigDecimal dayChange = null, dayBase = BigDecimal.ZERO;
        Map<UUID, BigDecimal> values = new java.util.HashMap<>();
        Map<UUID, HoldingService.HoldingDto> dtos = new java.util.HashMap<>();
        for (Holding h : mine) {
            HoldingService.HoldingDto d0 = holdingService.toDto(h, today);
            dtos.put(h.getId(), d0);
            values.put(h.getId(), d0.value());
        }
        Map<UUID, XirrService.HoldingFlows> flows = xirr.flows(mine, values, today);
        List<Xirr.Flow> allFlows = new ArrayList<>();
        for (Holding h : mine) {
            HoldingService.HoldingDto d = dtos.get(h.getId());
            XirrService.HoldingFlows hf = flows.get(h.getId());
            if (hf != null) allFlows.addAll(hf.flows());
            DayMove move = dayMove(h, schemes, closes);
            BigDecimal movePct = move == null || move.base().signum() == 0 ? null : move.change().multiply(BigDecimal.valueOf(100)).divide(move.base(), 2, RoundingMode.HALF_UP);
            if (move != null) {
                dayChange = (dayChange == null ? BigDecimal.ZERO : dayChange).add(move.change());
                dayBase = dayBase.add(move.base());
            }
            byClass.computeIfAbsent(h.getAssetClass(), k -> new ArrayList<>()).add(new Item("HOLDING", h.getId(), h.getName(), detail(d), d.value(),
                    d.investedAmount(), d.gain(), d.gainPct(), sourceLabel(d), d.estimated(), d.priceAsOf() == null ? null : d.priceAsOf().toString(),
                    move == null ? null : move.change(), movePct, move == null ? null : move.on(),
                    hf == null ? null : Xirr.percent(hf.flows()), hf != null && hf.estimated(), h.getStartDate()));
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
        byClass.forEach((cls, items) -> assetGroups.add(withXirr(group(cls.name(), cls.label(), items), flows)));

        List<Item> loanItems = new ArrayList<>();
        for (Liability l : withAccounts ? liabilities.findByUserIdAndArchivedFalse(userId) : List.<Liability>of()) {
            String detail = (l.getLender() == null ? "" : l.getLender() + ", ") + (l.getInterestRate() == null ? "" : l.getInterestRate().stripTrailingZeros().toPlainString() + "%")
                    + (l.getEmi() == null ? "" : ", EMI " + com.expensetracker.analytics.InrFormat.format(l.getEmi()));
            loanItems.add(new Item("LIABILITY", l.getId(), l.getName(), detail.replaceAll("^, |, $", ""), l.getOutstanding(), null, null, null,
                    "Outstanding you entered on " + l.getAsOf(), false, l.getAsOf().toString(), null, null, null, null, false, null));
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
                assetGroups, liabilityGroups, allocation,
                withAccounts ? change(userId, today.minusDays(30), net) : null,
                withAccounts ? change(userId, LocalDate.of(today.getYear(), 1, 1), net) : null,
                estimated, pricesNote, dayChange == null ? null : dayChange.setScale(2, RoundingMode.HALF_UP),
                dayChange == null || dayBase.signum() == 0 ? null : dayChange.multiply(BigDecimal.valueOf(100)).divide(dayBase, 2, RoundingMode.HALF_UP),
                withAccounts ? change(userId, today.minusDays(1), net) : null, withAccounts ? sinceStart(userId, today, net) : null,
                Xirr.percent(mergeTodayValues(allFlows, today)), flows.size(), mine.size());
    }

    /** A group's XIRR from the flows of its holdings that have one. */
    private static Group withXirr(Group g, Map<UUID, XirrService.HoldingFlows> flows) {
        List<Xirr.Flow> merged = new ArrayList<>();
        int count = 0;
        for (Item i : g.items()) {
            XirrService.HoldingFlows hf = flows.get(i.id());
            if (hf == null) continue;
            merged.addAll(hf.flows());
            count++;
        }
        return new Group(g.key(), g.label(), g.value(), g.invested(), g.gain(), g.gainPct(), g.items(), g.dayChange(), g.dayChangePct(),
                count == 0 ? null : Xirr.percent(mergeTodayValues(merged, LocalDate.MAX)), count);
    }

    /** Fewer, larger flows: today's values of all holdings as one positive flow (same result, quicker to solve). */
    private static List<Xirr.Flow> mergeTodayValues(List<Xirr.Flow> flows, LocalDate ignored) {
        Map<LocalDate, BigDecimal> byDate = new java.util.TreeMap<>();
        for (Xirr.Flow f : flows) byDate.merge(f.date(), f.amount(), BigDecimal::add);
        List<Xirr.Flow> out = new ArrayList<>();
        byDate.forEach((d, a) -> {
            if (a.signum() != 0) out.add(new Xirr.Flow(d, a));
        });
        return out;
    }

    /** Change since the first saved day, for people whose history is too short for "last 30 days". */
    private Change sinceStart(UUID userId, LocalDate today, BigDecimal current) {
        return snapshots.findFirstByUserIdOrderBySnapshotDateAsc(userId)
                .filter(first -> first.getSnapshotDate().isBefore(today))
                .map(first -> change(userId, first.getSnapshotDate(), current))
                .orElse(null);
    }

    /** Today's net worth, saved as today's point at the same time so the history and comparisons stay current. */
    @Transactional
    public Summary current(UUID userId) {
        Summary s = summary(userId);
        save(userId, s);
        return s;
    }

    /** New NAVs or closes were loaded: refresh everyone's point for today (in the background). */
    @org.springframework.transaction.event.TransactionalEventListener(fallbackExecution = true)
    public void onPricesUpdated(PricesUpdatedEvent e) {
        java.util.concurrent.CompletableFuture.runAsync(this::nightlySnapshots);
    }

    /**
     * The move on the latest official day: units × (latest NAV − previous NAV) for a fund priced from AMFI, or
     * units × (close − previous close) for a stock/ETF priced from NSE/BSE. Null when the holding isn't on that price
     * (e.g. a broker-file or typed price) or the previous one isn't known yet.
     */
    private static DayMove dayMove(Holding h, Map<String, PriceService.Scheme> schemes, Map<String, StockPriceService.Security> closes) {
        if (h.getValuationMethod() != ValuationMethod.MARKET_PRICE || h.getUnits() == null || h.getCurrentPrice() == null) return null;
        BigDecimal now = null, before = null;
        LocalDate on = null;
        if (h.getPriceSource() == Holding.PriceSource.AMFI && h.getSchemeCode() != null) {
            PriceService.Scheme s = schemes.get(h.getSchemeCode());
            if (s != null && s.nav() != null && s.prevNav() != null && s.nav().compareTo(h.getCurrentPrice()) == 0) {
                now = s.nav();
                before = s.prevNav();
                on = s.navDate();
            }
        } else if (h.getPriceSource() == Holding.PriceSource.EXCHANGE && h.getIsin() != null) {
            StockPriceService.Security s = closes.get(h.getIsin().toUpperCase(java.util.Locale.ROOT));
            if (s != null && s.close() != null && s.prevClose() != null && s.close().compareTo(h.getCurrentPrice()) == 0) {
                now = s.close();
                before = s.prevClose();
                on = s.priceDate();
            }
        }
        if (now == null || before.signum() <= 0) return null;
        return new DayMove(h.getUnits().multiply(now.subtract(before)).setScale(2, RoundingMode.HALF_UP),
                h.getUnits().multiply(before), on);
    }

    /** Records today's net worth (one row per day, replaced on each change). */
    @Transactional
    public void snapshot(UUID userId) {
        save(userId, summary(userId));
    }

    private void save(UUID userId, Summary s) {
        LocalDate today = TimeConfig.today(clock);
        Map<String, BigDecimal> breakdown = new LinkedHashMap<>();
        s.assetGroups().forEach(g -> breakdown.put(g.key(), g.value()));
        s.liabilityGroups().forEach(g -> breakdown.put("-" + g.key(), g.value()));
        String breakdownJson;
        try {
            breakdownJson = json.writeValueAsString(breakdown);
        } catch (Exception e) {
            breakdownJson = null;
        }
        snapshots.upsert(UUID.randomUUID(), userId, today, s.assets(), s.liabilities(), s.assets().subtract(s.liabilities()), breakdownJson);
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
        List<Item> moved = items.stream().filter(i -> i.dayChange() != null).toList();
        BigDecimal day = moved.isEmpty() ? null : moved.stream().map(Item::dayChange).reduce(BigDecimal.ZERO, BigDecimal::add);
        // previous value of the holdings that moved: value now minus the move
        BigDecimal before = moved.stream().map(i -> i.value().subtract(i.dayChange())).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal dayPct = day == null || before.signum() <= 0 ? null : day.multiply(BigDecimal.valueOf(100)).divide(before, 2, RoundingMode.HALF_UP);
        return new Group(key, label, value, invested, gain, pct, items, day, dayPct, null, 0);
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
        if (d.valuationMethod() == ValuationMethod.MANUAL) {
            if (d.accruedInterest() != null && d.accruedInterest().signum() > 0) {
                return "Incl. " + com.expensetracker.analytics.InrFormat.format(d.accruedInterest()) + " interest at "
                        + d.interestRate().stripTrailingZeros().toPlainString() + "% (est.)";
            }
            return d.interestRate() != null ? "Balance + " + d.interestRate().stripTrailingZeros().toPlainString() + "% interest" : "Value you entered";
        }
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
