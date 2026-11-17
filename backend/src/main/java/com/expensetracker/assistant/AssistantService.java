package com.expensetracker.assistant;

import com.expensetracker.account.AccountService;
import com.expensetracker.account.AccountType;
import com.expensetracker.analytics.DashboardService;
import com.expensetracker.analytics.InrFormat;
import com.expensetracker.budget.BudgetService;
import com.expensetracker.budget.SpendQueries;
import com.expensetracker.category.CategoryKind;
import com.expensetracker.category.CategoryService;
import com.expensetracker.common.TimeConfig;
import com.expensetracker.creditcard.CreditCardService;
import com.expensetracker.goal.GoalService;
import com.expensetracker.merchant.Merchant;
import com.expensetracker.merchant.MerchantService;
import com.expensetracker.networth.NetWorthService;
import com.expensetracker.recurring.RecurringPayment;
import com.expensetracker.recurring.RecurringService;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Answers questions from your own data with fixed rules. It runs entirely on this server (no external AI), shows
 * how each number was worked out, and separates confirmed figures from estimates. Questions it doesn't recognise
 * get a list of what it can answer instead of a guess.
 */
@Service
public class AssistantService {

    public enum FactKind { CONFIRMED, ESTIMATE, PENDING }

    public record Fact(String label, BigDecimal amount, String text, FactKind kind) {
        static Fact money(String label, BigDecimal amount, FactKind kind) {
            return new Fact(label, amount, null, kind);
        }

        static Fact text(String label, String text, FactKind kind) {
            return new Fact(label, null, text, kind);
        }
    }

    public record Link(String label, String href) {
    }

    public record Answer(String question, IntentParser.Type intent, String period, String answer, List<Fact> facts, List<String> calculation,
                         List<Link> links, List<String> followUps, String dataNote) {
    }

    private static final List<String> EXAMPLES = List.of(
            "How much did I spend this month?", "How much did I spend on food last month?", "What did I spend at Swiggy in October?",
            "Where did my money go this year?", "What's my savings rate?", "Compare this month with last month", "What's my net worth?",
            "Am I over budget?", "What subscriptions do I have?", "What bills are due soon?", "When is my credit card bill due?",
            "How are my goals doing?", "What were my biggest expenses last month?", "How much did I invest this year?", "Can I afford a ₹40,000 phone?");

    private final DashboardService dashboard;
    private final SpendQueries spend;
    private final CategoryService categories;
    private final MerchantService merchants;
    private final AccountService accounts;
    private final NetWorthService netWorth;
    private final BudgetService budgets;
    private final RecurringService recurring;
    private final CreditCardService cards;
    private final GoalService goals;
    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    public AssistantService(DashboardService dashboard, SpendQueries spend, CategoryService categories, MerchantService merchants,
                            AccountService accounts, NetWorthService netWorth, BudgetService budgets, RecurringService recurring,
                            CreditCardService cards, GoalService goals, NamedParameterJdbcTemplate jdbc, Clock clock) {
        this.dashboard = dashboard;
        this.spend = spend;
        this.categories = categories;
        this.merchants = merchants;
        this.accounts = accounts;
        this.netWorth = netWorth;
        this.budgets = budgets;
        this.recurring = recurring;
        this.cards = cards;
        this.goals = goals;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public List<String> examples() {
        return EXAMPLES;
    }

    @Transactional(readOnly = true)
    public Answer ask(UUID userId, String question) {
        if (question == null || question.isBlank()) return help(question);
        if (question.length() > 300) question = question.substring(0, 300);
        LocalDate today = TimeConfig.today(clock);
        IntentParser.Intent intent = IntentParser.parse(question, today);
        IntentParser.Range r = intent.range();
        LocalDate to = r.toExclusive().minusDays(1);
        return switch (intent.type()) {
            case SPEND_TOTAL -> intent.subject() == null ? spendTotal(userId, question, r, to) : spendOn(userId, question, intent, to);
            case INCOME_TOTAL -> income(userId, question, r, to);
            case TOP_CATEGORIES -> topCategories(userId, question, r, to);
            case TOP_MERCHANTS -> topMerchants(userId, question, r, to);
            case COMPARE_MONTHS -> compare(userId, question, today);
            case SAVINGS_RATE -> savings(userId, question, r, to);
            case INVESTED -> invested(userId, question, r, to);
            case NET_WORTH -> netWorth(userId, question);
            case BUDGETS -> budgetStatus(userId, question);
            case SUBSCRIPTIONS -> subscriptions(userId, question);
            case UPCOMING_BILLS -> upcoming(userId, question);
            case CARD_DUES -> cardDues(userId, question);
            case GOALS -> goalStatus(userId, question);
            case LARGEST -> largest(userId, question, r);
            case NEEDS_REVIEW -> review(userId, question);
            case CAN_AFFORD -> afford(userId, question, intent.amount(), today);
            case HELP -> help(question);
        };
    }

    private Answer spendTotal(UUID u, String q, IntentParser.Range r, LocalDate to) {
        var s = dashboard.summary(u, r.from(), to);
        List<String> calc = List.of(
                "Money out on expenses: " + InrFormat.format(s.grossExpenses()),
                "− refunds: " + InrFormat.format(s.refunds()),
                "− moved into investments (counted as saving): " + InrFormat.format(s.invested()),
                "= spending: " + InrFormat.format(s.expenses()),
                "Transfers between your own accounts and excluded transactions are not counted.");
        List<Fact> facts = new ArrayList<>(List.of(Fact.money("Spending", s.expenses(), FactKind.CONFIRMED)));
        if (s.pendingCategorisationAmount().signum() > 0) facts.add(Fact.money("Of which not yet categorised", s.pendingCategorisationAmount(), FactKind.PENDING));
        String text = "You spent " + InrFormat.format(s.expenses()) + " " + r.label() + "."
                + (s.pendingCategorisationAmount().signum() > 0 ? " " + InrFormat.format(s.pendingCategorisationAmount()) + " of that isn't categorised yet." : "");
        return new Answer(q, IntentParser.Type.SPEND_TOTAL, r.label(), text, facts, calc,
                List.of(new Link("See transactions", "/transactions?from=" + r.from() + "&to=" + to + "&direction=DEBIT")),
                List.of("Where did my money go " + r.label() + "?", "Compare this month with last month"), note());
    }

    private Answer spendOn(UUID u, String q, IntentParser.Intent intent, LocalDate to) {
        IntentParser.Range r = intent.range();
        String subject = intent.subject().toLowerCase(Locale.ROOT);
        var cat = categories.infoMap(u).values().stream()
                .filter(c -> c.kind() == CategoryKind.EXPENSE)
                .filter(c -> c.name().toLowerCase(Locale.ROOT).equals(subject) || c.name().toLowerCase(Locale.ROOT).startsWith(subject)
                        || c.name().toLowerCase(Locale.ROOT).contains(subject))
                .min((a, b) -> Integer.compare(a.name().length(), b.name().length())).orElse(null);
        if (cat != null) {
            BigDecimal amount = spend.spent(u, cat.id(), r.from(), r.toExclusive());
            boolean top = cat.topId() == null || cat.topId().equals(cat.id());
            return new Answer(q, IntentParser.Type.SPEND_TOTAL, r.label(),
                    "You spent " + InrFormat.format(amount) + " on " + cat.path() + " " + r.label() + ".",
                    List.of(Fact.money(cat.path(), amount, FactKind.CONFIRMED)),
                    List.of("Sum of confirmed transactions (and split lines) in " + cat.path() + (top ? " and its subcategories" : ""),
                            "minus refunds in the same category, " + r.label(),
                            "Transactions still in Needs Review are not included until you categorise them."),
                    List.of(new Link("See these transactions", "/transactions?categoryId=" + cat.id() + "&from=" + r.from() + "&to=" + to)),
                    List.of("Am I over budget?", "How much did I spend on " + cat.name() + " last month?"), note());
        }
        List<Merchant> found = merchants.search(u, intent.subject());
        if (!found.isEmpty()) {
            Merchant m = found.get(0);
            var row = jdbc.queryForMap("""
                    SELECT COALESCE(SUM(t.amount) FILTER (WHERE t.direction = 'DEBIT' AND t.kind = 'EXPENSE'), 0)
                           - COALESCE(SUM(t.amount) FILTER (WHERE t.direction = 'CREDIT' AND t.kind = 'REFUND'), 0) AS total,
                           COUNT(*) FILTER (WHERE t.direction = 'DEBIT' AND t.kind = 'EXPENSE') AS n
                    FROM transactions t WHERE t.user_id = :u AND t.merchant_id = :m AND t.txn_at >= :from AND t.txn_at < :to
                      AND t.status = 'POSTED' AND t.duplicate_status = 'NONE'
                    """, new MapSqlParameterSource("u", u).addValue("m", m.getId()).addValue("from", ts(r.from())).addValue("to", ts(r.toExclusive())));
            BigDecimal total = (BigDecimal) row.get("total");
            long n = ((Number) row.get("n")).longValue();
            return new Answer(q, IntentParser.Type.SPEND_TOTAL, r.label(),
                    "You spent " + InrFormat.format(total) + " at " + m.getName() + " " + r.label() + " across " + n + " payment" + (n == 1 ? "" : "s") + ".",
                    List.of(Fact.money(m.getName(), total, FactKind.CONFIRMED)),
                    List.of(n + " payments to " + m.getName() + " minus refunds from them, " + r.label()),
                    List.of(new Link("See these transactions", "/transactions?merchantId=" + m.getId() + "&from=" + r.from() + "&to=" + to)),
                    List.of("What are my top merchants " + r.label() + "?"), note());
        }
        return new Answer(q, IntentParser.Type.SPEND_TOTAL, r.label(),
                "I couldn't find a category or merchant matching \"" + intent.subject() + "\". Try a category name from your Categories page, or the merchant name as it appears in your transactions.",
                List.of(), List.of(), List.of(new Link("Categories", "/categories")), List.of("Where did my money go " + r.label() + "?"), note());
    }

    private Answer income(UUID u, String q, IntentParser.Range r, LocalDate to) {
        var s = dashboard.summary(u, r.from(), to);
        List<Fact> facts = new ArrayList<>();
        facts.add(Fact.money("Income", s.income(), FactKind.CONFIRMED));
        s.incomeByCategory().stream().limit(4).forEach(c -> facts.add(Fact.money(c.name(), c.amount(), FactKind.CONFIRMED)));
        return new Answer(q, IntentParser.Type.INCOME_TOTAL, r.label(), "Your income " + r.label() + " was " + InrFormat.format(s.income()) + ".", facts,
                List.of("Sum of money in that is marked as income (not refunds, not transfers from your own accounts)"),
                List.of(new Link("See income", "/transactions?direction=CREDIT&from=" + r.from() + "&to=" + to)),
                List.of("What's my savings rate?"), note());
    }

    private Answer topCategories(UUID u, String q, IntentParser.Range r, LocalDate to) {
        var s = dashboard.summary(u, r.from(), to);
        List<Fact> facts = s.expenseByCategory().stream().limit(6)
                .map(c -> new Fact(c.name(), c.amount(), c.share() == null ? null : c.share().stripTrailingZeros().toPlainString() + "% of spending", FactKind.CONFIRMED)).toList();
        String text = facts.isEmpty() ? "No categorised spending " + r.label() + " yet."
                : "Your biggest category " + r.label() + " was " + facts.get(0).label() + " at " + InrFormat.format(facts.get(0).amount()) + ".";
        return new Answer(q, IntentParser.Type.TOP_CATEGORIES, r.label(), text, facts,
                List.of("Share = category spending ÷ total spending " + InrFormat.format(s.expenses()),
                        "Pending categorisation: " + InrFormat.format(s.pendingCategorisationAmount()) + " is counted in the total but not in any category"),
                List.of(new Link("Open dashboard", "/dashboard")), List.of("What are my top merchants " + r.label() + "?"), note());
    }

    private Answer topMerchants(UUID u, String q, IntentParser.Range r, LocalDate to) {
        var s = dashboard.summary(u, r.from(), to);
        List<Fact> facts = s.topMerchants().stream().map(m -> new Fact(m.name(), m.amount(), m.count() + " payments", FactKind.CONFIRMED)).toList();
        return new Answer(q, IntentParser.Type.TOP_MERCHANTS, r.label(),
                facts.isEmpty() ? "No merchant spending " + r.label() + "." : "You spent the most at " + facts.get(0).label() + " " + r.label() + ".",
                facts, List.of("Total of expense payments per merchant " + r.label()), List.of(), List.of(), note());
    }

    private Answer compare(UUID u, String q, LocalDate today) {
        var s = dashboard.summary(u, today.withDayOfMonth(1), today);
        BigDecimal diff = s.expenses().subtract(s.previousExpenses());
        String dir = diff.signum() > 0 ? "more" : "less";
        return new Answer(q, IntentParser.Type.COMPARE_MONTHS, "this month so far",
                "So far this month you've spent " + InrFormat.format(s.expenses()) + ", " + InrFormat.format(diff.abs()) + " " + dir
                        + " than the same days last month (" + InrFormat.format(s.previousExpenses()) + ").",
                List.of(Fact.money("This month so far", s.expenses(), FactKind.CONFIRMED), Fact.money("Same days last month", s.previousExpenses(), FactKind.CONFIRMED),
                        Fact.money("Income this month", s.income(), FactKind.CONFIRMED), Fact.money("Income same days last month", s.previousIncome(), FactKind.CONFIRMED)),
                List.of("This period: " + s.period().from() + " to " + s.period().to(), "Compared with: " + s.previousPeriod().from() + " to " + s.previousPeriod().to(),
                        "Difference: " + InrFormat.format(s.expenses()) + " − " + InrFormat.format(s.previousExpenses()) + " = " + InrFormat.format(diff)),
                List.of(new Link("Monthly report", "/analytics")), List.of("Where did my money go this month?"), note());
    }

    private Answer savings(UUID u, String q, IntentParser.Range r, LocalDate to) {
        var s = dashboard.summary(u, r.from(), to);
        String rate = s.savingsRate() == null ? "not available (no income recorded)" : s.savingsRate().stripTrailingZeros().toPlainString() + "%";
        return new Answer(q, IntentParser.Type.SAVINGS_RATE, r.label(), "Your savings rate " + r.label() + " is " + rate + ".",
                List.of(Fact.money("Income", s.income(), FactKind.CONFIRMED), Fact.money("Spending", s.expenses(), FactKind.CONFIRMED),
                        Fact.money("Saved (income − spending)", s.savings(), FactKind.CONFIRMED), Fact.money("Of which invested", s.invested(), FactKind.CONFIRMED)),
                List.of("Savings = income " + InrFormat.format(s.income()) + " − spending " + InrFormat.format(s.expenses()) + " = " + InrFormat.format(s.savings()),
                        "Savings rate = savings ÷ income"),
                List.of(new Link("Monthly report", "/analytics")), List.of("How much did I invest this year?"), note());
    }

    private Answer invested(UUID u, String q, IntentParser.Range r, LocalDate to) {
        var s = dashboard.summary(u, r.from(), to);
        List<Fact> facts = new ArrayList<>(List.of(Fact.money("Moved into investments", s.invested(), FactKind.CONFIRMED)));
        s.investedByCategory().forEach(c -> facts.add(Fact.money(c.name(), c.amount(), FactKind.CONFIRMED)));
        return new Answer(q, IntentParser.Type.INVESTED, r.label(), "You put " + InrFormat.format(s.invested()) + " into investments " + r.label() + ".", facts,
                List.of("Sum of confirmed transactions in categories marked as investments (SIPs, stocks, FDs, PPF and so on)"),
                List.of(new Link("Net worth", "/networth")), List.of("What's my net worth?"), note());
    }

    private Answer netWorth(UUID u, String q) {
        var n = netWorth.summary(u);
        List<Fact> facts = new ArrayList<>(List.of(Fact.money("Assets", n.assets(), n.estimatedCount() > 0 ? FactKind.ESTIMATE : FactKind.CONFIRMED),
                Fact.money("Liabilities", n.liabilities(), FactKind.CONFIRMED), Fact.money("Net worth", n.netWorth(), n.estimatedCount() > 0 ? FactKind.ESTIMATE : FactKind.CONFIRMED)));
        if (n.change30d() != null) facts.add(Fact.money("Change in 30 days", n.change30d().amount(), FactKind.CONFIRMED));
        return new Answer(q, IntentParser.Type.NET_WORTH, "today", "Your net worth is " + InrFormat.format(n.netWorth()) + ".", facts,
                List.of("Net worth = assets " + InrFormat.format(n.assets()) + " − liabilities " + InrFormat.format(n.liabilities()),
                        n.estimatedCount() > 0 ? n.estimatedCount() + " holding(s) use a manual or calculated value, so the total is partly an estimate." : "All holdings have recent prices or values.",
                        n.pricesNote() == null ? "" : n.pricesNote()),
                List.of(new Link("Net worth", "/networth")), List.of(), note());
    }

    private Answer budgetStatus(UUID u, String q) {
        var o = budgets.overview(u);
        var active = o.budgets().stream().filter(b -> !b.archived()).toList();
        if (active.isEmpty()) return new Answer(q, IntentParser.Type.BUDGETS, "now", "You haven't set any budgets yet.", List.of(), List.of(),
                List.of(new Link("Create a budget", "/budgets")), List.of(), note());
        List<Fact> facts = active.stream().map(b -> new Fact(b.name() + " (" + b.periodLabel() + ")", b.status().spent(),
                InrFormat.format(b.status().spent()) + " of " + InrFormat.format(b.status().available()) + ", " + b.status().pctUsed().stripTrailingZeros().toPlainString() + "% used",
                FactKind.CONFIRMED)).toList();
        long over = active.stream().filter(b -> "OVER".equals(b.status().state())).count();
        String text = over > 0 ? "You're over budget on " + over + " of " + active.size() + " budgets." : "You're within all " + active.size() + " budgets so far.";
        List<String> calc = new ArrayList<>();
        active.stream().filter(b -> b.status().projectedOverspend().signum() > 0).forEach(b -> calc.add(b.name() + ": at this pace you'll spend about "
                + InrFormat.format(b.status().forecast()) + " (estimate: spent so far ÷ days elapsed × days in period)"));
        return new Answer(q, IntentParser.Type.BUDGETS, "current periods", text, facts, calc, List.of(new Link("Budgets", "/budgets")), List.of(), note());
    }

    private Answer subscriptions(UUID u, String q) {
        var s = recurring.summary(u);
        var subs = s.items().stream().filter(i -> i.status() == RecurringPayment.Status.ACTIVE && i.kind() == com.expensetracker.recurring.RecurringDetector.Kind.SUBSCRIPTION).toList();
        List<Fact> facts = subs.stream().map(i -> new Fact(i.name(), i.amount(), i.frequency().name().toLowerCase() + ", next " + i.nextDue()
                + (i.priceChanged() ? ", price changed from " + InrFormat.format(i.previousAmount()) : ""), FactKind.CONFIRMED)).toList();
        String text = subs.isEmpty() ? "You have no confirmed subscriptions." + (s.suggestions() > 0 ? " There are " + s.suggestions() + " detected repeats waiting for you to confirm." : "")
                : "You have " + subs.size() + " subscriptions costing " + InrFormat.format(s.annualSubscriptions()) + " a year.";
        return new Answer(q, IntentParser.Type.SUBSCRIPTIONS, "now", text, facts, List.of("Yearly cost = amount × payments per year, added up"),
                List.of(new Link("Subscriptions", "/subscriptions")), List.of("What bills are due soon?"), note());
    }

    private Answer upcoming(UUID u, String q) {
        var s = recurring.summary(u);
        var due = s.items().stream().filter(i -> i.status() == RecurringPayment.Status.ACTIVE && !i.income() && i.daysUntilDue() != null
                && i.daysUntilDue() >= -3 && i.daysUntilDue() <= 30).toList();
        BigDecimal total = due.stream().map(RecurringService.RecurringDto::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        List<Fact> facts = due.stream().map(i -> new Fact(i.name(), i.amount(), "due " + i.nextDue() + (i.amountVaries() ? " (amount varies, average shown)" : ""),
                i.amountVaries() ? FactKind.ESTIMATE : FactKind.CONFIRMED)).toList();
        return new Answer(q, IntentParser.Type.UPCOMING_BILLS, "next 30 days",
                due.isEmpty() ? "Nothing recurring is due in the next 30 days." : due.size() + " payments due in the next 30 days, about " + InrFormat.format(total) + " in total.",
                facts, List.of("Due dates are predicted from past payment dates, so treat them as estimates."), List.of(new Link("Subscriptions & bills", "/subscriptions")),
                List.of("When is my credit card bill due?"), note());
    }

    private Answer cardDues(UUID u, String q) {
        var o = cards.overview(u);
        if (o.cards().isEmpty()) return new Answer(q, IntentParser.Type.CARD_DUES, "now", "You don't have a credit card account in Arth.", List.of(), List.of(),
                List.of(new Link("Add an account", "/accounts")), List.of(), note());
        List<Fact> facts = new ArrayList<>();
        for (var c : o.cards()) {
            if (!c.configured()) {
                facts.add(Fact.text(c.name(), "Outstanding " + InrFormat.format(c.outstanding()) + ". Add the statement day and limit to see the due date.", FactKind.CONFIRMED));
            } else {
                facts.add(new Fact(c.name(), c.due().remaining(), c.due().state() == com.expensetracker.creditcard.CardCycle.DueState.PAID ? "paid"
                        : "due " + c.lastStatement().dueDate() + ", minimum " + InrFormat.format(c.due().minimumRemaining()) + ", " + c.utilisationPct() + "% of limit used",
                        FactKind.ESTIMATE));
            }
        }
        return new Answer(q, IntentParser.Type.CARD_DUES, "latest statements", "Across your cards you owe " + InrFormat.format(o.totalDue()) + " on the latest statements.",
                facts, List.of("Statement amounts are worked out from the card transactions in Arth, so check them against your bank's statement."),
                List.of(new Link("Credit cards", "/credit-cards")), List.of(), note());
    }

    private Answer goalStatus(UUID u, String q) {
        var list = goals.list(u).stream().filter(g -> g.status() == com.expensetracker.goal.Goal.Status.ACTIVE).toList();
        if (list.isEmpty()) return new Answer(q, IntentParser.Type.GOALS, "now", "You don't have any active goals.", List.of(), List.of(),
                List.of(new Link("Set a goal", "/goals")), List.of(), note());
        List<Fact> facts = list.stream().map(g -> new Fact(g.name(), g.projection().saved(), g.projection().progressPct().stripTrailingZeros().toPlainString()
                + "% of " + InrFormat.format(g.targetAmount()) + (g.projection().requiredMonthly() != null ? ", needs " + InrFormat.format(g.projection().requiredMonthly()) + "/month" : "")
                + ", " + g.projection().state().toLowerCase().replace('_', ' '), FactKind.CONFIRMED)).toList();
        long behind = list.stream().filter(g -> "BEHIND".equals(g.projection().state())).count();
        return new Answer(q, IntentParser.Type.GOALS, "now", list.size() + " active goals" + (behind > 0 ? ", " + behind + " behind schedule." : ", none behind schedule."),
                facts, List.of("Required monthly amount assumes contributions at the end of each month and the expected return you set (an estimate)."),
                List.of(new Link("Goals", "/goals")), List.of(), note());
    }

    private Answer largest(UUID u, String q, IntentParser.Range r) {
        List<Fact> facts = jdbc.query("""
                SELECT COALESCE(m.name, t.notes, t.counterparty, t.description, 'Payment') AS label, t.amount, CAST(t.txn_at AT TIME ZONE 'Asia/Kolkata' AS date) AS day
                FROM transactions t LEFT JOIN merchants m ON m.id = t.merchant_id
                LEFT JOIN categories c ON c.id = t.category_id LEFT JOIN categories p ON p.id = c.parent_id
                WHERE t.user_id = :u AND t.txn_at >= :from AND t.txn_at < :to AND t.status = 'POSTED' AND t.duplicate_status = 'NONE'
                  AND t.direction = 'DEBIT' AND t.kind = 'EXPENSE' AND NOT COALESCE(p.is_investment, c.is_investment, FALSE)
                ORDER BY t.amount DESC LIMIT 5
                """, new MapSqlParameterSource("u", u).addValue("from", ts(r.from())).addValue("to", ts(r.toExclusive())),
                (rs, i) -> new Fact(rs.getString("label"), rs.getBigDecimal("amount"), rs.getDate("day").toString(), FactKind.CONFIRMED));
        return new Answer(q, IntentParser.Type.LARGEST, r.label(),
                facts.isEmpty() ? "No expenses " + r.label() + "." : "Your biggest expense " + r.label() + " was " + facts.get(0).label() + " for " + InrFormat.format(facts.get(0).amount()) + ".",
                facts, List.of(), List.of(new Link("Transactions", "/transactions?from=" + r.from())), List.of(), note());
    }

    private Answer review(UUID u, String q) {
        LocalDate today = TimeConfig.today(clock);
        var s = dashboard.summary(u, today.withDayOfMonth(1), today);
        return new Answer(q, IntentParser.Type.NEEDS_REVIEW, "now", s.needsReviewCount() + " transactions are waiting for a category.",
                List.of(Fact.text("Needs review", String.valueOf(s.needsReviewCount()), FactKind.PENDING)),
                List.of("Uncategorised transactions count in your totals but not in category breakdowns or category budgets."),
                List.of(new Link("Review now", "/review")), List.of(), note());
    }

    private Answer afford(UUID u, String q, BigDecimal amount, LocalDate today) {
        if (amount == null) return new Answer(q, IntentParser.Type.CAN_AFFORD, "now", "Tell me the amount, for example \"Can I afford ₹40,000?\"",
                List.of(), List.of(), List.of(), List.of(), note());
        BigDecimal liquid = accounts.list(u).stream().filter(a -> !a.archived() && a.type() != AccountType.CREDIT_CARD)
                .map(AccountService.AccountDto::balance).reduce(BigDecimal.ZERO, BigDecimal::add);
        var rec = recurring.summary(u);
        BigDecimal bills = rec.items().stream().filter(i -> i.status() == RecurringPayment.Status.ACTIVE && !i.income() && i.daysUntilDue() != null
                && i.daysUntilDue() >= 0 && i.daysUntilDue() <= 30).map(RecurringService.RecurringDto::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal cardDue = cards.overview(u).totalDue();
        BigDecimal avgSpend = BigDecimal.ZERO;
        for (int i = 1; i <= 3; i++) {
            LocalDate s = today.withDayOfMonth(1).minusMonths(i);
            avgSpend = avgSpend.add(dashboard.summary(u, s, s.plusMonths(1).minusDays(1)).expenses());
        }
        avgSpend = avgSpend.divide(BigDecimal.valueOf(3), 2, RoundingMode.HALF_UP);
        BigDecimal left = liquid.subtract(bills).subtract(cardDue).subtract(amount);
        BigDecimal months = avgSpend.signum() > 0 ? left.divide(avgSpend, 1, RoundingMode.HALF_DOWN) : null;
        String verdict;
        if (left.signum() < 0) verdict = "Not from what's in your accounts right now: you'd be " + InrFormat.format(left.negate()) + " short after upcoming bills and card dues.";
        else if (months != null && months.compareTo(BigDecimal.valueOf(3)) < 0)
            verdict = "You could pay for it, but you'd have " + InrFormat.format(left) + " left, about " + months.toPlainString() + " months of your usual spending. That's a thin cushion.";
        else verdict = "Yes, it looks affordable: you'd still have " + InrFormat.format(left) + " left" + (months != null ? ", about " + months.toPlainString() + " months of your usual spending." : ".");
        return new Answer(q, IntentParser.Type.CAN_AFFORD, "now", verdict,
                List.of(Fact.money("Bank, cash and wallet balances", liquid, FactKind.CONFIRMED), Fact.money("Recurring payments due in 30 days", bills, FactKind.ESTIMATE),
                        Fact.money("Credit card dues", cardDue, FactKind.ESTIMATE), Fact.money("Average monthly spending (last 3 months)", avgSpend, FactKind.CONFIRMED),
                        Fact.money("Left after this purchase", left, FactKind.ESTIMATE)),
                List.of(InrFormat.format(liquid) + " − bills " + InrFormat.format(bills) + " − card dues " + InrFormat.format(cardDue) + " − " + InrFormat.format(amount)
                        + " = " + InrFormat.format(left),
                        "Balances are only as current as your last import; investments are not counted as spendable cash.",
                        "This is a rough check, not financial advice."),
                List.of(new Link("Accounts", "/accounts")), List.of("What bills are due soon?"), note());
    }

    private Answer help(String q) {
        return new Answer(q, IntentParser.Type.HELP, null,
                "I answer questions about your own money using the data in Arth. I didn't understand that one; here are things you can ask.",
                List.of(), List.of(), List.of(), EXAMPLES.subList(0, 8),
                "Runs on this server with fixed rules. Nothing is sent to an outside AI service.");
    }

    private static String note() {
        return "Worked out from your transactions in Arth on this server. Nothing is sent to an outside AI service.";
    }

    private static java.sql.Timestamp ts(LocalDate d) {
        return java.sql.Timestamp.from(TimeConfig.startOfDay(d));
    }
}
