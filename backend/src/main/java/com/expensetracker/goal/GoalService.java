package com.expensetracker.goal;

import com.expensetracker.analytics.DashboardService;
import com.expensetracker.analytics.InrFormat;
import com.expensetracker.audit.AuditService;
import com.expensetracker.common.ApiException;
import com.expensetracker.common.TimeConfig;
import com.expensetracker.networth.HoldingService;
import com.expensetracker.notification.NotificationService;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class GoalService {

    public record GoalRequest(String name, Goal.Kind kind, BigDecimal targetAmount, LocalDate targetDate, BigDecimal startingAmount,
                              BigDecimal expectedReturnPct, Integer priority, Goal.Status status, String notes, Long version) {
    }

    public record ContributionRequest(BigDecimal amount, LocalDate date, String note, UUID transactionId) {
    }

    public record LinkRequest(UUID holdingId, BigDecimal sharePct) {
    }

    public record ContributionDto(UUID id, BigDecimal amount, LocalDate date, String note, UUID transactionId) {
    }

    public record LinkedHolding(UUID holdingId, String name, BigDecimal value, BigDecimal sharePct, BigDecimal counted, boolean estimated) {
    }

    public record GoalDto(UUID id, String name, Goal.Kind kind, BigDecimal targetAmount, LocalDate targetDate, BigDecimal startingAmount,
                          BigDecimal contributed, BigDecimal fromHoldings, BigDecimal expectedReturnPct, int priority, Goal.Status status,
                          String notes, long version, GoalMath.Projection projection, List<ContributionDto> contributions,
                          List<LinkedHolding> holdings, String explanation) {
    }

    private final GoalRepository goals;
    private final GoalContributionRepository contributions;
    private final HoldingService holdings;
    private final DashboardService dashboard;
    private final NotificationService notifications;
    private final NamedParameterJdbcTemplate jdbc;
    private final AuditService audit;
    private final Clock clock;

    public GoalService(GoalRepository goals, GoalContributionRepository contributions, HoldingService holdings, DashboardService dashboard,
                       NotificationService notifications, NamedParameterJdbcTemplate jdbc, AuditService audit, Clock clock) {
        this.goals = goals;
        this.contributions = contributions;
        this.holdings = holdings;
        this.dashboard = dashboard;
        this.notifications = notifications;
        this.jdbc = jdbc;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<GoalDto> list(UUID userId) {
        Map<UUID, HoldingService.HoldingDto> h = holdingMap(userId);
        return goals.findByUserIdOrderByPriorityAscCreatedAtAsc(userId).stream().map(g -> toDto(g, h)).toList();
    }

    @Transactional(readOnly = true)
    public GoalDto get(UUID userId, UUID id) {
        return toDto(require(userId, id), holdingMap(userId));
    }

    /** Emergency fund suggestion: six months of your average spending over the last three complete months. */
    @Transactional(readOnly = true)
    public Map<String, Object> emergencyFundSuggestion(UUID userId) {
        LocalDate first = TimeConfig.today(clock).withDayOfMonth(1);
        List<BigDecimal> months = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            LocalDate s = first.minusMonths(i);
            months.add(dashboard.summary(userId, s, s.plusMonths(1).minusDays(1)).expenses());
        }
        BigDecimal avg = months.stream().reduce(BigDecimal.ZERO, BigDecimal::add).divide(BigDecimal.valueOf(3), 2, RoundingMode.HALF_UP);
        Map<String, Object> m = new HashMap<>();
        m.put("averageMonthlySpending", avg);
        m.put("months", months);
        m.put("suggestedTarget", avg.multiply(BigDecimal.valueOf(6)).divide(BigDecimal.valueOf(1000), 0, RoundingMode.CEILING).multiply(BigDecimal.valueOf(1000)));
        m.put("basis", "6 × average monthly spending of the last 3 complete months (" + InrFormat.format(avg) + "), rounded up to the next ₹1,000");
        return m;
    }

    @Transactional
    public GoalDto create(UUID userId, GoalRequest r) {
        if (r.name() == null || r.name().isBlank()) throw ApiException.badRequest("Name your goal");
        if (r.targetAmount() == null || r.targetAmount().signum() <= 0) throw ApiException.badRequest("Target amount must be more than zero");
        Goal g = new Goal(userId, r.name().trim(), r.kind() != null ? r.kind() : Goal.Kind.OTHER, r.targetAmount().setScale(2, RoundingMode.HALF_UP));
        apply(g, r);
        g = goals.save(g);
        audit.record(userId, "GOAL_CREATED", "goal", g.getId(), g.getName());
        return get(userId, g.getId());
    }

    @Transactional
    public GoalDto update(UUID userId, UUID id, GoalRequest r) {
        Goal g = require(userId, id);
        if (r.version() != null && r.version() != g.getVersion()) throw ApiException.conflict("This goal changed elsewhere. Reload and try again.");
        if (r.name() != null && !r.name().isBlank()) g.setName(r.name().trim());
        if (r.kind() != null) g.setKind(r.kind());
        if (r.targetAmount() != null) {
            if (r.targetAmount().signum() <= 0) throw ApiException.badRequest("Target amount must be more than zero");
            g.setTargetAmount(r.targetAmount().setScale(2, RoundingMode.HALF_UP));
        }
        apply(g, r);
        goals.flush();
        return get(userId, id);
    }

    private void apply(Goal g, GoalRequest r) {
        if (r.targetDate() != null) {
            if (r.targetDate().isBefore(TimeConfig.today(clock))) throw ApiException.badRequest("Target date is in the past");
            g.setTargetDate(r.targetDate());
        }
        if (r.startingAmount() != null) {
            if (r.startingAmount().signum() < 0) throw ApiException.badRequest("Amount already saved can't be negative");
            g.setStartingAmount(r.startingAmount().setScale(2, RoundingMode.HALF_UP));
        }
        if (r.expectedReturnPct() != null) {
            if (r.expectedReturnPct().compareTo(BigDecimal.valueOf(-50)) < 0 || r.expectedReturnPct().compareTo(BigDecimal.valueOf(50)) > 0)
                throw ApiException.badRequest("Expected return should be between -50% and 50% a year");
            g.setExpectedReturnPct(r.expectedReturnPct());
        }
        if (r.priority() != null) g.setPriority(Math.max(1, Math.min(3, r.priority())));
        if (r.status() != null) g.setStatus(r.status());
        if (r.notes() != null) g.setNotes(r.notes().isBlank() ? null : r.notes().trim());
    }

    @Transactional
    public void delete(UUID userId, UUID id) {
        Goal g = require(userId, id);
        goals.delete(g);
        audit.record(userId, "GOAL_DELETED", "goal", id, g.getName());
    }

    @Transactional
    public GoalDto contribute(UUID userId, UUID goalId, ContributionRequest r) {
        Goal g = require(userId, goalId);
        if (r.amount() == null || r.amount().signum() == 0) throw ApiException.badRequest("Enter an amount (negative to record a withdrawal)");
        GoalContribution c = new GoalContribution(g.getId(), userId, r.amount().setScale(2, RoundingMode.HALF_UP), r.date() != null ? r.date() : TimeConfig.today(clock));
        if (r.note() != null && !r.note().isBlank()) c.setNote(r.note().trim());
        if (r.transactionId() != null) c.setTransactionId(r.transactionId());
        contributions.save(c);
        contributions.flush();
        return get(userId, goalId);
    }

    @Transactional
    public GoalDto deleteContribution(UUID userId, UUID goalId, UUID contributionId) {
        GoalContribution c = contributions.findByIdAndUserId(contributionId, userId).filter(x -> x.getGoalId().equals(goalId))
                .orElseThrow(() -> ApiException.notFound("Contribution"));
        contributions.delete(c);
        contributions.flush();
        return get(userId, goalId);
    }

    @Transactional
    public GoalDto link(UUID userId, UUID goalId, LinkRequest r) {
        require(userId, goalId);
        if (r.holdingId() == null || !holdingMap(userId).containsKey(r.holdingId())) throw ApiException.notFound("Holding");
        BigDecimal share = r.sharePct() == null ? BigDecimal.valueOf(100) : r.sharePct();
        if (share.signum() <= 0 || share.compareTo(BigDecimal.valueOf(100)) > 0) throw ApiException.badRequest("Share must be between 1% and 100%");
        jdbc.update("""
                INSERT INTO goal_holdings (goal_id, holding_id, share_pct) VALUES (:g, :h, :s)
                ON CONFLICT (goal_id, holding_id) DO UPDATE SET share_pct = EXCLUDED.share_pct
                """, new MapSqlParameterSource("g", goalId).addValue("h", r.holdingId()).addValue("s", share));
        return get(userId, goalId);
    }

    @Transactional
    public GoalDto unlink(UUID userId, UUID goalId, UUID holdingId) {
        require(userId, goalId);
        jdbc.update("DELETE FROM goal_holdings WHERE goal_id = :g AND holding_id = :h", new MapSqlParameterSource("g", goalId).addValue("h", holdingId));
        return get(userId, goalId);
    }

    /** Milestone notifications at 25/50/75/100%; marks a goal achieved when it reaches 100%. */
    @Transactional
    public int milestones(UUID userId) {
        int n = 0;
        for (GoalDto g : list(userId)) {
            if (g.status() != Goal.Status.ACTIVE) continue;
            BigDecimal pct = g.projection().progressPct();
            int reached = pct.compareTo(BigDecimal.valueOf(100)) >= 0 ? 100 : pct.compareTo(BigDecimal.valueOf(75)) >= 0 ? 75
                    : pct.compareTo(BigDecimal.valueOf(50)) >= 0 ? 50 : pct.compareTo(BigDecimal.valueOf(25)) >= 0 ? 25 : 0;
            if (reached == 0) continue;
            String title = reached == 100 ? "Goal reached: " + g.name() : g.name() + " is " + reached + "% there";
            String body = InrFormat.format(g.projection().saved()) + " of " + InrFormat.format(g.targetAmount()) + " saved."
                    + (reached < 100 && g.projection().requiredMonthly() != null ? " Keep about " + InrFormat.format(g.projection().requiredMonthly()) + " a month to finish on time." : "");
            if (notifications.notify(userId, NotificationService.Topic.GOAL, "GOAL_MILESTONE", reached == 100 ? "INFO" : "INFO", title, body, "/goals",
                    "goal:" + g.id() + ":" + reached)) n++;
        }
        return n;
    }

    private Map<UUID, HoldingService.HoldingDto> holdingMap(UUID userId) {
        Map<UUID, HoldingService.HoldingDto> m = new HashMap<>();
        for (HoldingService.HoldingDto h : holdings.list(userId)) m.put(h.id(), h);
        return m;
    }

    private GoalDto toDto(Goal g, Map<UUID, HoldingService.HoldingDto> holdingMap) {
        LocalDate today = TimeConfig.today(clock);
        List<GoalContribution> cs = contributions.findByGoalIdOrderByContributedOnDesc(g.getId());
        BigDecimal contributed = cs.stream().map(GoalContribution::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        List<LinkedHolding> linked = new ArrayList<>();
        BigDecimal fromHoldings = BigDecimal.ZERO;
        for (Map<String, Object> row : jdbc.queryForList("SELECT holding_id, share_pct FROM goal_holdings WHERE goal_id = :g", new MapSqlParameterSource("g", g.getId()))) {
            HoldingService.HoldingDto h = holdingMap.get((UUID) row.get("holding_id"));
            if (h == null) continue;
            BigDecimal share = (BigDecimal) row.get("share_pct");
            BigDecimal value = h.value() == null ? BigDecimal.ZERO : h.value();
            BigDecimal counted = value.multiply(share).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
            fromHoldings = fromHoldings.add(counted);
            linked.add(new LinkedHolding(h.id(), h.name(), value, share, counted, h.estimated()));
        }
        BigDecimal saved = g.getStartingAmount().add(contributed).add(fromHoldings);
        LocalDate sixMonthsAgo = today.minusMonths(6);
        BigDecimal recent = cs.stream().filter(c -> c.getContributedOn().isAfter(sixMonthsAgo)).map(GoalContribution::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add).divide(BigDecimal.valueOf(6), 2, RoundingMode.HALF_UP);
        GoalMath.Projection p = GoalMath.project(g.getTargetAmount(), saved, g.getTargetDate(), g.getExpectedReturnPct(), recent, today);
        String explanation = "Saved = already saved " + InrFormat.format(g.getStartingAmount()) + " + contributions " + InrFormat.format(contributed)
                + (linked.isEmpty() ? "" : " + linked investments " + InrFormat.format(fromHoldings) + " (current value × share)")
                + ". Projection uses your average contribution over the last 6 months (" + InrFormat.format(recent) + "/month)"
                + (g.getExpectedReturnPct().signum() != 0 ? " and " + g.getExpectedReturnPct().stripTrailingZeros().toPlainString() + "% a year growth, compounded monthly (an estimate, not a promise)" : "")
                + ".";
        return new GoalDto(g.getId(), g.getName(), g.getKind(), g.getTargetAmount(), g.getTargetDate(), g.getStartingAmount(), contributed, fromHoldings,
                g.getExpectedReturnPct(), g.getPriority(), g.getStatus(), g.getNotes(), g.getVersion(), p,
                cs.stream().limit(50).map(c -> new ContributionDto(c.getId(), c.getAmount(), c.getContributedOn(), c.getNote(), c.getTransactionId())).toList(),
                linked, explanation);
    }

    private Goal require(UUID userId, UUID id) {
        return goals.findByIdAndUserId(id, userId).orElseThrow(() -> ApiException.notFound("Goal"));
    }
}
