package com.expensetracker.demo;

import com.expensetracker.account.AccountService;
import com.expensetracker.account.AccountType;
import com.expensetracker.auth.AuthService;
import com.expensetracker.categorisation.CategorisationService;
import com.expensetracker.categorisation.RememberScope;
import com.expensetracker.category.Category;
import com.expensetracker.category.CategoryService;
import com.expensetracker.common.TimeConfig;
import com.expensetracker.ingestion.IngestOutcome;
import com.expensetracker.ingestion.IngestionService;
import com.expensetracker.ingestion.RawTransaction;
import com.expensetracker.networth.AssetClass;
import com.expensetracker.networth.HoldingService;
import com.expensetracker.networth.Liability;
import com.expensetracker.networth.LiabilityService;
import com.expensetracker.networth.NetWorthService;
import com.expensetracker.transaction.Direction;
import com.expensetracker.transaction.PaymentMethod;
import com.expensetracker.transaction.SourceType;
import com.expensetracker.transaction.Transaction;
import com.expensetracker.transaction.TransactionRepository;
import com.expensetracker.user.User;
import com.expensetracker.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Creates a clearly labelled demo user (is_demo = true) with ~3 months of realistic, synthetic Indian
 * transactions. Runs only when app.demo.enabled=true. Demo data flows through the real ingestion pipeline,
 * so deduplication, suggestions and the review inbox behave exactly as with real data.
 */
@Component
@ConditionalOnProperty(name = "app.demo.enabled", havingValue = "true")
public class DemoDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);
    private static final int UNREVIEWED_DAYS = 6;

    private record Event(LocalDate date, LocalTime time, UUID account, Direction direction, BigDecimal amount, String counterparty,
                         String description, PaymentMethod method, String provider, String categoryPath, UUID transferTo,
                         String reference, String notes) {
    }

    private final AuthService auth;
    private final UserRepository users;
    private final AccountService accounts;
    private final CategoryService categories;
    private final IngestionService ingestion;
    private final CategorisationService categorisation;
    private final TransactionRepository transactions;
    private final HoldingService holdings;
    private final LiabilityService liabilities;
    private final NetWorthService netWorth;
    private final com.expensetracker.budget.BudgetService budgets;
    private final com.expensetracker.recurring.RecurringService recurring;
    private final com.expensetracker.creditcard.CreditCardService cards;
    private final com.expensetracker.goal.GoalService goals;
    private final jakarta.persistence.EntityManager em;
    private final Clock clock;
    private final String email;
    private final String password;

    public DemoDataSeeder(AuthService auth, UserRepository users, AccountService accounts, CategoryService categories,
                          IngestionService ingestion, CategorisationService categorisation, TransactionRepository transactions,
                          HoldingService holdings, LiabilityService liabilities, NetWorthService netWorth,
                          com.expensetracker.budget.BudgetService budgets, com.expensetracker.recurring.RecurringService recurring,
                          com.expensetracker.creditcard.CreditCardService cards, com.expensetracker.goal.GoalService goals,
                          jakarta.persistence.EntityManager em, Clock clock, @Value("${app.demo.email:demo@arth.local}") String email,
                          @Value("${app.demo.password}") String password) {
        this.auth = auth;
        this.users = users;
        this.accounts = accounts;
        this.categories = categories;
        this.ingestion = ingestion;
        this.categorisation = categorisation;
        this.transactions = transactions;
        this.holdings = holdings;
        this.liabilities = liabilities;
        this.netWorth = netWorth;
        this.budgets = budgets;
        this.recurring = recurring;
        this.cards = cards;
        this.goals = goals;
        this.em = em;
        this.clock = clock;
        this.email = email;
        this.password = password;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (users.existsByEmailIgnoreCase(email)) {
            log.info("Demo user already exists; skipping demo seed");
            return;
        }
        User user = auth.createUser(email, password, "Demo User", true);
        UUID u = user.getId();
        LocalDate today = TimeConfig.today(clock);
        LocalDate start = today.minusMonths(3).withDayOfMonth(1);

        UUID hdfc = accounts.create(u, new AccountService.AccountRequest("HDFC Savings", AccountType.BANK, "HDFC Bank", "4821",
                new BigDecimal("85000.00"), start)).id();
        UUID sbi = accounts.create(u, new AccountService.AccountRequest("SBI Savings", AccountType.BANK, "State Bank of India", "3307",
                new BigDecimal("20000.00"), start)).id();
        UUID card = accounts.create(u, new AccountService.AccountRequest("ICICI Amazon Pay Card", AccountType.CREDIT_CARD, "ICICI Bank", "9012",
                BigDecimal.ZERO, start)).id();
        UUID cash = accounts.list(u).stream().filter(a -> a.type() == AccountType.CASH).findFirst().orElseThrow().id();

        List<Event> events = generate(start, today, hdfc, sbi, card, cash);
        int n = 0;
        Set<UUID> rememberedMerchants = new HashSet<>();
        for (Event e : events) {
            RawTransaction raw = RawTransaction.builder()
                    .account(e.account()).direction(e.direction()).amount(e.amount())
                    .txnAt(e.date().atTime(e.time()).atZone(TimeConfig.IST).toInstant()).timeKnown(true)
                    .counterparty(e.counterparty()).description(e.description()).method(e.method()).provider(e.provider())
                    .reference(e.reference()).notes(e.notes())
                    .source(SourceType.DEMO, "demo-" + (n++)).raw("Synthetic demo transaction")
                    .build();
            IngestOutcome outcome = ingestion.ingest(u, raw);
            if (outcome.result() != IngestOutcome.Result.CREATED) continue;
            boolean recent = !e.date().isBefore(today.minusDays(UNREVIEWED_DAYS));
            if (recent) continue; // left for the Needs Review inbox
            Transaction t = transactions.findByIdAndUserId(outcome.transactionId(), u).orElseThrow();
            if (e.transferTo() != null) {
                categorisation.markTransfer(u, t.getId(), new CategorisationService.MarkTransferRequest(null, e.transferTo()));
                continue;
            }
            if (e.categoryPath() == null && e.notes() != null && e.notes().startsWith("lent")) {
                categorisation.exclude(u, t.getId(), "Money lent to a friend");
                continue;
            }
            UUID categoryId = categories.resolvePath(u, e.categoryPath()).map(Category::getId).orElse(t.getSuggestedCategoryId());
            if (categoryId == null) continue;
            RememberScope scope = RememberScope.ONLY_THIS;
            if (e.notes() != null) scope = RememberScope.NOTE;
            else if (t.getMerchantId() != null && rememberedMerchants.add(t.getMerchantId())) scope = RememberScope.MERCHANT;
            categorisation.categorise(u, t.getId(), new CategorisationService.CategoriseRequest(categoryId, scope, null, null, null));
        }
        // the same Swiggy order seen twice (alert + statement without a reference) → flagged as a possible duplicate
        LocalDate dupDay = today.minusDays(2);
        for (int i = 0; i < 2; i++) {
            ingestion.ingest(u, RawTransaction.builder().account(hdfc).direction(Direction.DEBIT).amount(new BigDecimal("386.00"))
                    .txnAt(dupDay.atTime(i == 0 ? LocalTime.of(20, 41) : LocalTime.NOON).atZone(TimeConfig.IST).toInstant())
                    .timeKnown(i == 0).counterparty("SWIGGY").description(i == 0 ? "Rs.386.00 debited from A/c XX4821 to VPA swiggy@icici"
                            : "UPI-SWIGGY-SWIGGY@ICICI-ICIC0DC0099-ORDER").method(PaymentMethod.UPI)
                    .source(SourceType.DEMO, "demo-dup-" + i).raw("Synthetic demo transaction").build());
        }
        UUID liquidFund = seedNetWorth(u, today);
        seedPlanning(u, today, card, liquidFund);
        log.info("Seeded demo user {} with {} synthetic transactions", email, events.size() + 2);
    }

    private void seedPlanning(UUID u, LocalDate today, UUID card, UUID liquidFund) {
        em.flush(); // the planning services read transactions with plain SQL, which doesn't trigger Hibernate's auto-flush
        cards.configure(u, card, new com.expensetracker.creditcard.CreditCardService.CardRequest(new BigDecimal("200000.00"), 5, 18,
                new BigDecimal("5"), new BigDecimal("200"), new BigDecimal("4120"), new BigDecimal("2"), new BigDecimal("0.25"), BigDecimal.ZERO, "Synthetic demo card"));
        var budgetPeriod = com.expensetracker.budget.BudgetPeriods.Period.MONTHLY;
        budgets.create(u, new com.expensetracker.budget.BudgetService.BudgetRequest("Overall spending", null, budgetPeriod, new BigDecimal("60000"), true, null, null));
        categories.resolvePath(u, "Food & Dining").ifPresent(c -> budgets.create(u, new com.expensetracker.budget.BudgetService.BudgetRequest(null, c.getId(), budgetPeriod, new BigDecimal("7000"), false, null, null)));
        categories.resolvePath(u, "Groceries").ifPresent(c -> budgets.create(u, new com.expensetracker.budget.BudgetService.BudgetRequest(null, c.getId(), budgetPeriod, new BigDecimal("5000"), false, null, null)));
        categories.resolvePath(u, "Transportation").ifPresent(c -> budgets.create(u, new com.expensetracker.budget.BudgetService.BudgetRequest(null, c.getId(), budgetPeriod, new BigDecimal("3000"), false, null, null)));

        em.flush();
        recurring.detect(u);
        for (var item : recurring.summary(u).items()) {
            if (item.kind() == com.expensetracker.recurring.RecurringDetector.Kind.SUBSCRIPTION || item.kind() == com.expensetracker.recurring.RecurringDetector.Kind.RENT
                    || item.kind() == com.expensetracker.recurring.RecurringDetector.Kind.INCOME) {
                recurring.setStatus(u, item.id(), com.expensetracker.recurring.RecurringPayment.Status.ACTIVE, null);
            }
        }
        var ef = goals.create(u, new com.expensetracker.goal.GoalService.GoalRequest("Emergency fund", com.expensetracker.goal.Goal.Kind.EMERGENCY_FUND,
                new BigDecimal("300000"), today.plusMonths(18), new BigDecimal("40000"), new BigDecimal("6.5"), 1, null, "Synthetic demo goal", null));
        em.flush();
        if (liquidFund != null) goals.link(u, ef.id(), new com.expensetracker.goal.GoalService.LinkRequest(liquidFund, new BigDecimal("100")));
        var trip = goals.create(u, new com.expensetracker.goal.GoalService.GoalRequest("Goa trip", com.expensetracker.goal.Goal.Kind.TRAVEL,
                new BigDecimal("60000"), today.plusMonths(5), BigDecimal.ZERO, BigDecimal.ZERO, 2, null, "Synthetic demo goal", null));
        for (int i = 3; i >= 1; i--) {
            goals.contribute(u, trip.id(), new com.expensetracker.goal.GoalService.ContributionRequest(new BigDecimal("8000"), today.minusMonths(i).withDayOfMonth(6), "Monthly top-up", null));
        }
        // reminders and budget alerts are created by the hourly job (AlertScheduler), outside this seeding transaction
    }

    private UUID seedNetWorth(UUID u, LocalDate today) {
        record H(AssetClass cls, String name, String units, String invested, String price, String manual, String rate, LocalDate start, String platform, String isin) {
        }
        List<H> list = List.of(
                new H(AssetClass.MUTUAL_FUND, "Parag Parikh Flexi Cap Fund - Direct Growth", "412.348", "32000.00", "92.4521", null, null, null, "Groww", "INF879O01027"),
                new H(AssetClass.MUTUAL_FUND, "HDFC Liquid Fund - Direct Growth", "4.902", "24000.00", "5123.4567", null, null, null, "Groww", "INF179KB1HK0"),
                new H(AssetClass.ETF, "NIFTYBEES", "120", "29400.00", "268.45", null, null, null, "Zerodha", null),
                new H(AssetClass.STOCK, "INFY", "15", "21757.50", "1612.30", null, null, null, "Zerodha", null),
                new H(AssetClass.FIXED_DEPOSIT, "SBI FD (2 yr)", null, "100000.00", null, null, "7.00", today.minusMonths(7), "SBI", null),
                new H(AssetClass.PPF, "PPF account", null, null, null, "186400.00", null, null, "SBI", null),
                new H(AssetClass.EPF, "EPF (UAN)", null, null, null, "412300.00", null, null, null, null),
                new H(AssetClass.GOLD, "Gold coins", "10", "58000.00", "7650.00", null, null, null, null, null));
        UUID liquid = null;
        for (H h : list) {
            var created = holdings.create(u, new HoldingService.HoldingRequest(h.cls(), h.name(), h.cls() == AssetClass.STOCK || h.cls() == AssetClass.ETF ? h.name() : null,
                    h.isin(), null, null, h.platform(), null, null,
                    h.units() == null ? null : new BigDecimal(h.units()), h.invested() == null ? null : new BigDecimal(h.invested()),
                    h.price() == null ? null : new BigDecimal(h.price()), h.manual() == null ? null : new BigDecimal(h.manual()),
                    h.rate() == null ? null : new BigDecimal(h.rate()), h.rate() == null ? null : 4, h.start(),
                    h.start() == null ? null : h.start().plusYears(2), "Synthetic demo holding", null));
            if (h.name().startsWith("HDFC Liquid")) liquid = created.id();
        }
        liabilities.create(u, new LiabilityService.LiabilityRequest(Liability.Type.CAR_LOAN, "Car loan", "HDFC Bank", new BigDecimal("650000.00"),
                new BigDecimal("412000.00"), new BigDecimal("9.10"), new BigDecimal("13450.00"), today.minusYears(2), today.plusYears(3), today,
                "Synthetic demo loan", null));
        netWorth.snapshot(u);
        return liquid;
    }

    private List<Event> generate(LocalDate start, LocalDate today, UUID hdfc, UUID sbi, UUID card, UUID cash) {
        Random r = new Random(42);
        List<Event> out = new ArrayList<>();
        BigDecimal cardSpendThisCycle = BigDecimal.ZERO;
        for (LocalDate d = start; !d.isAfter(today); d = d.plusDays(1)) {
            int dom = d.getDayOfMonth();
            if (dom == 1) {
                out.add(ev(d, 9, 5, hdfc, Direction.CREDIT, "92000.00", "ACME TECHNOLOGIES PVT LTD",
                        "NEFT CR-CITI0000002-ACME TECHNOLOGIES PVT LTD-SALARY " + d.getMonth(), PaymentMethod.NEFT, null, "Salary", null, ref(r)));
            }
            if (dom == 2) out.add(withNote(ev(d, 9, 30, hdfc, Direction.DEBIT, "3500.00", "SHANTA BAI", "UPI/DR/" + ref(r) + "/SHANTA BAI/SBIN/shantabai@oksbi/maid salary",
                    PaymentMethod.UPI, "PhonePe", "Household Help", null, null), "maid salary"));
            if (r.nextInt(5) == 0) out.add(withNote(ev(d, 8, 10 + r.nextInt(40), hdfc, Direction.DEBIT, money(120 + r.nextInt(380)), "RAJU VEGETABLES",
                    "UPI/DR/" + ref(r) + "/RAJU/SBIN/raju.veg@ybl/sabzi", PaymentMethod.UPI, "PhonePe", "Groceries", null, null), "sabzi"));
            if (dom == 15 && d.getMonthValue() % 2 == 0) out.add(withNote(ev(d, 20, 5, hdfc, Direction.DEBIT, "2000.00", "RAHUL S",
                    "UPI/DR/" + ref(r) + "/RAHUL S/HDFC/rahuls@okhdfcbank/lent to rahul", PaymentMethod.UPI, "Google Pay", null, null, null), "lent to rahul"));
            if (dom == 3) out.add(ev(d, 10, 12, hdfc, Direction.DEBIT, "24000.00", "RAMESH IYER", "UPI/DR/" + ref(r) + "/RAMESH IYER/SBIN/rameshiyer@oksbi/Rent",
                    PaymentMethod.UPI, "PhonePe", "Rent", null, null));
            if (dom == 5) out.add(ev(d, 11, 0, hdfc, Direction.DEBIT, "10000.00", null, "IMPS/P2A/" + ref(r) + "/SELF TRANSFER/SBIN",
                    PaymentMethod.IMPS, null, null, sbi, null));
            if (dom == 7) out.add(ev(d, 6, 30, hdfc, Direction.DEBIT, "5000.00", "BSE STAR MF", "ACH D- BSE STAR MF-SIP " + ref(r),
                    PaymentMethod.NACH, null, "Investments > SIP & Mutual Funds", null, null));
            if (dom == 9) out.add(ev(d, 19, 2, hdfc, Direction.DEBIT, money(1650 + r.nextInt(500)), "BESCOM", "UPI/DR/" + ref(r) + "/BESCOM/UTIB/bescom@axisbank/Electricity",
                    PaymentMethod.UPI, "PhonePe", "Bills & Utilities > Electricity", null, null));
            if (dom == 12) out.add(ev(d, 8, 45, hdfc, Direction.DEBIT, "599.00", "AIRTEL", "UPI/DR/" + ref(r) + "/AIRTEL/AIRP/airtel@airtel/Recharge",
                    PaymentMethod.UPI, "Google Pay", "Bills & Utilities > Mobile Recharge", null, null));
            if (dom == 14) {
                out.add(ev(d, 0, 5, card, Direction.DEBIT, "649.00", "NETFLIX", "NETFLIX.COM MUMBAI", PaymentMethod.CARD, null, "Subscriptions", null, null));
                cardSpendThisCycle = cardSpendThisCycle.add(new BigDecimal("649.00"));
            }
            if (dom == 16) {
                out.add(ev(d, 0, 10, card, Direction.DEBIT, "119.00", "SPOTIFY", "SPOTIFY INDIA", PaymentMethod.CARD, null, "Subscriptions", null, null));
                cardSpendThisCycle = cardSpendThisCycle.add(new BigDecimal("119.00"));
            }
            if (dom == 20) {
                out.add(ev(d, 13, 15, cash, Direction.DEBIT, money(300 + r.nextInt(400)), "Local vegetable vendor", "Cash purchase",
                        PaymentMethod.CASH, null, "Groceries", null, null));
                out.add(ev(d, 18, 20, hdfc, Direction.DEBIT, "3000.00", null, "ATM WDL/CASH/" + ref(r), PaymentMethod.ATM, null, null, cash, null));
            }
            if (dom == 22 && cardSpendThisCycle.signum() > 0) {
                out.add(ev(d, 10, 0, hdfc, Direction.DEBIT, cardSpendThisCycle.toPlainString(), "ICICI CREDIT CARD",
                        "BIL/ONL/" + ref(r) + "/ICICI CREDIT CARD/CC PAYMENT", PaymentMethod.OTHER, null, null, card, null));
                cardSpendThisCycle = BigDecimal.ZERO;
            }
            // food delivery ~4 times a week
            if (r.nextInt(7) < 4) {
                boolean swiggy = r.nextBoolean();
                String name = swiggy ? "SWIGGY" : "ZOMATO";
                out.add(ev(d, 13 + r.nextInt(9), r.nextInt(60), hdfc, Direction.DEBIT, money(180 + r.nextInt(470)), name,
                        "UPI/DR/" + ref(r) + "/" + name + "/YESB/" + name.toLowerCase() + "@ybl/Order", PaymentMethod.UPI, "PhonePe",
                        "Food & Dining > Food Delivery", null, null));
            }
            if (r.nextInt(7) < 1) {
                String g = r.nextBoolean() ? "BLINKIT" : "ZEPTO";
                out.add(ev(d, 9 + r.nextInt(12), r.nextInt(60), hdfc, Direction.DEBIT, money(400 + r.nextInt(1400)), g,
                        "UPI/DR/" + ref(r) + "/" + g + "/HDFC/" + g.toLowerCase() + ".payu@hdfcbank/Groceries", PaymentMethod.UPI, "PhonePe",
                        "Groceries", null, null));
            }
            if (r.nextInt(7) < 3) {
                String c = r.nextInt(3) == 0 ? "RAPIDO" : "UBER";
                out.add(ev(d, 8 + r.nextInt(13), r.nextInt(60), hdfc, Direction.DEBIT, money(80 + r.nextInt(370)), c,
                        "UPI/DR/" + ref(r) + "/" + c + "/UTIB/" + c.toLowerCase() + "@axisbank/Ride", PaymentMethod.UPI, "Google Pay",
                        "Transportation > Cab & Auto", null, null));
            }
            if (dom % 13 == 4) out.add(ev(d, 17, 40, hdfc, Direction.DEBIT, money(1500 + r.nextInt(1000)), "INDIAN OIL",
                    "POS 416021XXXXXX4821 INDIAN OIL PETROL PUMP", PaymentMethod.CARD, null, "Fuel", null, null));
            if (r.nextInt(12) == 0) {
                String shop = r.nextBoolean() ? "AMAZON PAY INDIA" : "FLIPKART";
                BigDecimal amt = new BigDecimal(money(499 + r.nextInt(4000)));
                out.add(new Event(d, LocalTime.of(21, r.nextInt(60)), card, Direction.DEBIT, amt, shop, shop, PaymentMethod.CARD, null,
                        "Shopping > Online Shopping", null, null, null));
                cardSpendThisCycle = cardSpendThisCycle.add(amt);
            }
            if (r.nextInt(15) == 0) out.add(ev(d, 16, 10, hdfc, Direction.DEBIT, money(250 + r.nextInt(300)), "STARBUCKS",
                    "UPI/DR/" + ref(r) + "/STARBUCKS/ICIC/starbucks@icici/Coffee", PaymentMethod.UPI, "PhonePe", "Food & Dining > Cafes", null, null));
            if (dom == 18) out.add(ev(d, 12, 0, hdfc, Direction.DEBIT, money(350 + r.nextInt(900)), "APOLLO PHARMACY",
                    "UPI/DR/" + ref(r) + "/APOLLO PHARMACY/ICIC/apollo@icici/Medicines", PaymentMethod.UPI, "PhonePe", "Medical > Pharmacy", null, null));
            if (dom == 25 && r.nextBoolean()) out.add(ev(d, 11, 30, card, Direction.CREDIT, money(499 + r.nextInt(800)), "AMAZON PAY INDIA",
                    "REFUND AMAZON PAY INDIA", PaymentMethod.CARD, null, "Shopping > Online Shopping", null, null));
        }
        return out;
    }

    private static Event ev(LocalDate d, int h, int m, UUID account, Direction dir, String amount, String counterparty, String desc,
                            PaymentMethod method, String provider, String categoryPath, UUID transferTo, String reference) {
        return new Event(d, LocalTime.of(Math.min(h, 23), Math.min(m, 59)), account, dir, new BigDecimal(amount), counterparty, desc, method,
                provider, categoryPath, transferTo, reference, null);
    }

    private static Event withNote(Event e, String note) {
        return new Event(e.date(), e.time(), e.account(), e.direction(), e.amount(), e.counterparty(), e.description(), e.method(),
                e.provider(), e.categoryPath(), e.transferTo(), e.reference(), note);
    }

    private static String money(int rupees) {
        return rupees + ".00";
    }

    private static String ref(Random r) {
        return "4" + String.format("%011d", Math.abs(r.nextLong()) % 100_000_000_000L);
    }
}
