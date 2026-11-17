package com.expensetracker.budget;

import com.expensetracker.analytics.InrFormat;
import com.expensetracker.audit.AuditService;
import com.expensetracker.category.Category;
import com.expensetracker.category.CategoryService;
import com.expensetracker.common.ApiException;
import com.expensetracker.common.TimeConfig;
import com.expensetracker.notification.NotificationService;
import org.springframework.dao.DataIntegrityViolationException;
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
import java.util.UUID;

@Service
public class BudgetService {

    public record BudgetRequest(String name, UUID categoryId, BudgetPeriods.Period period, BigDecimal amount, Boolean rollover,
                                String alertThresholds, Long version) {
    }

    public record HistoryPoint(String label, LocalDate start, BigDecimal limit, BigDecimal spent) {
    }

    public record BudgetDto(UUID id, String name, UUID categoryId, String categoryName, String categoryColor, BudgetPeriods.Period period,
                            BigDecimal amount, boolean rollover, List<Integer> alertThresholds, boolean archived, long version,
                            String periodLabel, LocalDate periodStart, LocalDate periodEnd, long daysElapsed, long daysTotal,
                            BudgetMath.Status status, BigDecimal recommended, String recommendationBasis, List<HistoryPoint> history) {
    }

    public record Overview(BigDecimal totalBudgeted, BigDecimal totalSpent, int over, int atRisk, List<BudgetDto> budgets, String note) {
    }

    private final BudgetRepository repository;
    private final SpendQueries spend;
    private final CategoryService categories;
    private final NotificationService notifications;
    private final NamedParameterJdbcTemplate jdbc;
    private final AuditService audit;
    private final Clock clock;

    public BudgetService(BudgetRepository repository, SpendQueries spend, CategoryService categories, NotificationService notifications,
                         NamedParameterJdbcTemplate jdbc, AuditService audit, Clock clock) {
        this.repository = repository;
        this.spend = spend;
        this.categories = categories;
        this.notifications = notifications;
        this.jdbc = jdbc;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Overview overview(UUID userId) {
        LocalDate today = TimeConfig.today(clock);
        var info = categories.infoMap(userId);
        List<BudgetDto> list = repository.findByUserIdOrderByArchivedAscCreatedAtAsc(userId).stream().map(b -> toDto(userId, b, today, info)).toList();
        BigDecimal budgeted = BigDecimal.ZERO, spent = BigDecimal.ZERO;
        int over = 0, risk = 0;
        for (BudgetDto b : list) {
            if (b.archived() || b.period() != BudgetPeriods.Period.MONTHLY || b.categoryId() == null) continue;
            budgeted = budgeted.add(b.status().available());
            spent = spent.add(b.status().spent());
        }
        for (BudgetDto b : list) {
            if (b.archived()) continue;
            if ("OVER".equals(b.status().state())) over++;
            else if (!"ON_TRACK".equals(b.status().state())) risk++;
        }
        return new Overview(budgeted, spent, over, risk, list,
                "Spending = money out minus refunds. Transfers, excluded rows and money moved into investments don't count. "
                        + "Category budgets include subcategories and count only categorised transactions; an overall budget also counts uncategorised spending.");
    }

    @Transactional(readOnly = true)
    public BudgetDto get(UUID userId, UUID id) {
        return toDto(userId, require(userId, id), TimeConfig.today(clock), categories.infoMap(userId));
    }

    /** Suggestion before creating a budget: average of the last three complete periods. */
    @Transactional(readOnly = true)
    public java.util.Map<String, Object> suggest(UUID userId, UUID categoryId, BudgetPeriods.Period period) {
        LocalDate today = TimeConfig.today(clock);
        BudgetPeriods.Range cur = BudgetPeriods.containing(period, today);
        List<BigDecimal> past = new ArrayList<>();
        BudgetPeriods.Range r = cur;
        for (int i = 0; i < 3; i++) {
            r = BudgetPeriods.previous(period, r);
            past.add(spend.spent(userId, categoryId, r.start(), r.endExclusive()));
        }
        BigDecimal rec = BudgetMath.recommend(past, period);
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("recommended", rec);
        m.put("pastPeriods", past);
        m.put("basis", rec == null ? "No spending in the last three periods to base a suggestion on." : "Average of the last three complete periods, rounded up.");
        return m;
    }

    @Transactional
    public BudgetDto create(UUID userId, BudgetRequest r) {
        if (r.period() == null) throw ApiException.badRequest("Choose weekly, monthly or yearly");
        BigDecimal amount = positive(r.amount());
        String name = r.name();
        if (r.categoryId() != null) {
            Category c = categories.require(userId, r.categoryId());
            if (c.getKind() != com.expensetracker.category.CategoryKind.EXPENSE) throw ApiException.badRequest("Budgets are for expense categories");
            if (name == null || name.isBlank()) name = c.getName();
        } else if (name == null || name.isBlank()) {
            name = "Overall spending";
        }
        Budget b = new Budget(userId, name.trim(), r.categoryId(), r.period(), amount, BudgetPeriods.containing(r.period(), TimeConfig.today(clock)).start());
        if (r.rollover() != null) b.setRollover(r.rollover());
        if (r.alertThresholds() != null) b.setAlertThresholds(thresholds(r.alertThresholds()));
        try {
            b = repository.saveAndFlush(b);
        } catch (DataIntegrityViolationException e) {
            throw ApiException.conflict("You already have a " + r.period().name().toLowerCase() + " budget for this. Edit that one instead.");
        }
        audit.record(userId, "BUDGET_CREATED", "budget", b.getId(), b.getName() + " " + amount.toPlainString());
        return get(userId, b.getId());
    }

    @Transactional
    public BudgetDto update(UUID userId, UUID id, BudgetRequest r) {
        Budget b = require(userId, id);
        if (r.version() != null && r.version() != b.getVersion()) throw ApiException.conflict("This budget changed elsewhere. Reload and try again.");
        if (r.name() != null && !r.name().isBlank()) b.setName(r.name().trim());
        if (r.amount() != null) b.setAmount(positive(r.amount()));
        if (r.period() != null) b.setPeriod(r.period());
        if (r.rollover() != null) b.setRollover(r.rollover());
        if (r.alertThresholds() != null) b.setAlertThresholds(thresholds(r.alertThresholds()));
        try {
            repository.flush();
        } catch (DataIntegrityViolationException e) {
            throw ApiException.conflict("Another budget already covers this category and period");
        }
        return get(userId, id);
    }

    @Transactional
    public void setArchived(UUID userId, UUID id, boolean archived) {
        require(userId, id).setArchived(archived);
    }

    @Transactional
    public void delete(UUID userId, UUID id) {
        Budget b = require(userId, id);
        repository.delete(b);
        audit.record(userId, "BUDGET_DELETED", "budget", id, b.getName());
    }

    /** Sends one alert per threshold per period. Returns how many new alerts were created. */
    @Transactional
    public int checkAlerts(UUID userId) {
        LocalDate today = TimeConfig.today(clock);
        var info = categories.infoMap(userId);
        int created = 0;
        for (Budget b : repository.findByUserIdAndArchivedFalse(userId)) {
            BudgetDto d = toDto(userId, b, today, info);
            for (int t : BudgetMath.crossed(d.alertThresholds(), d.status().pctUsed())) {
                int ins = jdbc.update("INSERT INTO budget_alerts (budget_id, period_start, threshold) VALUES (:b, :p, :t) ON CONFLICT DO NOTHING",
                        new MapSqlParameterSource("b", b.getId()).addValue("p", d.periodStart()).addValue("t", t));
                if (ins == 0) continue;
                // only the highest newly crossed threshold produces a message (others are recorded silently)
                if (t != d.alertThresholds().stream().filter(x -> d.status().pctUsed().compareTo(BigDecimal.valueOf(x)) >= 0).max(Integer::compare).orElse(t)) continue;
                boolean over = t >= 100;
                String title = over ? b.getName() + " budget used up" : b.getName() + " budget " + t + "% used";
                String body = "You've spent " + InrFormat.format(d.status().spent()) + " of " + InrFormat.format(d.status().available())
                        + " for " + d.periodLabel() + ". " + (d.status().remaining().signum() >= 0
                        ? InrFormat.format(d.status().remaining()) + " left, about " + InrFormat.format(d.status().dailyAllowance()) + " a day."
                        : "That's " + InrFormat.format(d.status().remaining().negate()) + " over.");
                if (notifications.notify(userId, NotificationService.Topic.BUDGET, "BUDGET_THRESHOLD", over ? "CRITICAL" : t >= 90 ? "WARNING" : "INFO",
                        title, body, "/budgets", "budget:" + b.getId() + ":" + d.periodStart() + ":" + t)) created++;
            }
        }
        return created;
    }

    private BudgetDto toDto(UUID userId, Budget b, LocalDate today, java.util.Map<UUID, CategoryService.CategoryInfo> info) {
        BudgetPeriods.Range cur = BudgetPeriods.containing(b.getPeriod(), today);
        BigDecimal spent = spend.spent(userId, b.getCategoryId(), cur.start(), cur.endExclusive());
        BudgetPeriods.Range prev = BudgetPeriods.previous(b.getPeriod(), cur);
        BigDecimal carried = BigDecimal.ZERO;
        List<HistoryPoint> history = new ArrayList<>();
        BudgetPeriods.Range r = cur;
        List<BigDecimal> past = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            r = BudgetPeriods.previous(b.getPeriod(), r);
            BigDecimal s = spend.spent(userId, b.getCategoryId(), r.start(), r.endExclusive());
            history.add(0, new HistoryPoint(BudgetPeriods.label(b.getPeriod(), r), r.start(), b.getAmount(), s));
            if (i < 3) past.add(s);
            if (i == 0 && b.isRollover() && !prev.start().isBefore(b.getStartDate())) carried = BudgetMath.carryOver(true, b.getAmount(), s);
        }
        BudgetMath.Status st = BudgetMath.status(b.getAmount(), carried, spent, cur.daysElapsed(today), cur.days());
        BigDecimal rec = BudgetMath.recommend(past, b.getPeriod());
        String basis = rec == null ? null : "Average spend of the last three periods (" + past.stream().map(InrFormat::format).reduce((x, y) -> x + ", " + y).orElse("") + "), rounded up";
        CategoryService.CategoryInfo ci = b.getCategoryId() == null ? null : info.get(b.getCategoryId());
        return new BudgetDto(b.getId(), b.getName(), b.getCategoryId(), ci == null ? null : ci.name(), ci == null ? null : ci.color(), b.getPeriod(),
                b.getAmount(), b.isRollover(), BudgetMath.parseThresholds(b.getAlertThresholds()), b.isArchived(), b.getVersion(),
                BudgetPeriods.label(b.getPeriod(), cur), cur.start(), cur.endExclusive().minusDays(1), cur.daysElapsed(today), cur.days(),
                st, rec, basis, history);
    }

    private static String thresholds(String csv) {
        try {
            List<Integer> t = BudgetMath.parseThresholds(csv);
            if (t.isEmpty()) throw ApiException.badRequest("Give at least one alert threshold, e.g. 50,75,90,100");
            return String.join(",", t.stream().map(String::valueOf).toList());
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("Alert thresholds must be whole percentages, e.g. 50,75,90,100");
        }
    }

    private static BigDecimal positive(BigDecimal v) {
        if (v == null || v.signum() <= 0) throw ApiException.badRequest("Budget amount must be more than zero");
        if (v.scale() > 2) throw ApiException.badRequest("Amount has more than 2 decimal places");
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    private Budget require(UUID userId, UUID id) {
        return repository.findByIdAndUserId(id, userId).orElseThrow(() -> ApiException.notFound("Budget"));
    }
}
