package com.expensetracker.recurring;

import com.expensetracker.analytics.InrFormat;
import com.expensetracker.audit.AuditService;
import com.expensetracker.category.CategoryService;
import com.expensetracker.common.ApiException;
import com.expensetracker.common.TimeConfig;
import com.expensetracker.notification.NotificationService;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Recurring payments: detected from your history (always as suggestions you confirm), or added by hand.
 * Monthly equivalents: weekly × 52 ÷ 12, quarterly ÷ 3, yearly ÷ 12.
 */
@Service
public class RecurringService {

    public record RecurringRequest(String name, RecurringDetector.Kind kind, RecurringDetector.Frequency frequency, BigDecimal amount,
                                   LocalDate nextDue, Integer reminderDays, UUID categoryId, UUID accountId, String notes, Boolean income, Long version) {
    }

    public record RecurringDto(UUID id, String name, RecurringDetector.Kind kind, RecurringPayment.Status status, boolean income,
                               RecurringDetector.Frequency frequency, BigDecimal amount, BigDecimal previousAmount, boolean priceChanged,
                               boolean amountVaries, LocalDate lastPaidOn, LocalDate nextDue, Long daysUntilDue, boolean overdue, int occurrences,
                               BigDecimal monthlyCost, BigDecimal annualCost, int reminderDays, UUID categoryId, String categoryName, UUID accountId,
                               boolean detected, boolean forgotten, String notes, long version) {
    }

    public record Summary(BigDecimal monthlyCommitted, BigDecimal monthlyIncome, BigDecimal annualSubscriptions, int activeSubscriptions,
                          int suggestions, int dueIn7Days, int forgotten, List<RecurringDto> items, String note) {
    }

    private static final String HISTORY = """
            SELECT t.txn_at, t.amount, t.direction, t.merchant_id, m.normalized_name, m.name AS merchant_name, t.note_key, t.notes,
                   t.counterparty, t.category_id, c.name AS category_name, COALESCE(p.is_investment, c.is_investment, FALSE) AS investment, t.account_id
            FROM transactions t
            LEFT JOIN merchants m ON m.id = t.merchant_id
            LEFT JOIN categories c ON c.id = t.category_id
            LEFT JOIN categories p ON p.id = c.parent_id
            WHERE t.user_id = :userId AND t.txn_at >= :since AND t.status = 'POSTED' AND t.duplicate_status = 'NONE'
              AND t.kind IN ('EXPENSE', 'INCOME')
            ORDER BY t.txn_at
            """;

    private final RecurringPaymentRepository repository;
    private final NamedParameterJdbcTemplate jdbc;
    private final CategoryService categories;
    private final NotificationService notifications;
    private final AuditService audit;
    private final Clock clock;

    public RecurringService(RecurringPaymentRepository repository, NamedParameterJdbcTemplate jdbc, CategoryService categories,
                            NotificationService notifications, AuditService audit, Clock clock) {
        this.repository = repository;
        this.jdbc = jdbc;
        this.categories = categories;
        this.notifications = notifications;
        this.audit = audit;
        this.clock = clock;
    }

    private record Meta(UUID merchantId, UUID categoryId, UUID accountId) {
    }

    /** Re-runs detection over the last 400 days. Returns how many new suggestions were created. */
    @Transactional
    public int detect(UUID userId) {
        Map<String, Meta> meta = new HashMap<>();
        List<RecurringDetector.Payment> payments = new ArrayList<>();
        jdbc.query(HISTORY, new MapSqlParameterSource("userId", userId)
                .addValue("since", java.sql.Timestamp.from(Instant.now(clock).minus(Duration.ofDays(400)))), rs -> {
            String norm = rs.getString("normalized_name");
            String noteKey = rs.getString("note_key");
            String key = norm != null ? "m:" + norm : noteKey != null && !noteKey.isBlank() ? "n:" + noteKey : null;
            if (key == null) return;
            String name = rs.getString("merchant_name") != null ? rs.getString("merchant_name")
                    : rs.getString("notes") != null ? rs.getString("notes") : rs.getString("counterparty");
            boolean credit = "CREDIT".equals(rs.getString("direction"));
            LocalDate date = rs.getTimestamp("txn_at").toInstant().atZone(TimeConfig.IST).toLocalDate();
            payments.add(new RecurringDetector.Payment(key, name, date, rs.getBigDecimal("amount"), credit, rs.getString("category_name"), rs.getBoolean("investment")));
            meta.put(key + (credit ? "|C" : "|D"), new Meta((UUID) rs.getObject("merchant_id"), (UUID) rs.getObject("category_id"), (UUID) rs.getObject("account_id")));
        });
        int created = 0;
        for (RecurringDetector.Pattern p : RecurringDetector.detect(payments)) {
            String dir = p.credit() ? "CREDIT" : "DEBIT";
            Meta m = meta.get(p.key() + (p.credit() ? "|C" : "|D"));
            RecurringPayment r = repository.findByUserIdAndMatchKeyAndDirection(userId, p.key(), dir).orElse(null);
            if (r == null) {
                r = new RecurringPayment(userId, title(p.name()), p.kind(), RecurringPayment.Status.SUGGESTED, p.key(), p.frequency(), p.amount());
                r.setDirection(dir);
                apply(r, p, m);
                repository.save(r);
                created++;
                continue;
            }
            if (r.getStatus() == RecurringPayment.Status.DISMISSED) continue;
            boolean newPayment = r.getLastPaidOn() == null || p.lastDate().isAfter(r.getLastPaidOn());
            if (r.getStatus() == RecurringPayment.Status.CANCELLED) {
                LocalDate cancelledOn = r.getUpdatedAt().atZone(TimeConfig.IST).toLocalDate();
                if (newPayment && p.lastDate().isAfter(cancelledOn)) {
                    notifications.notify(userId, NotificationService.Topic.SUBSCRIPTION, "CHARGED_AFTER_CANCEL", "WARNING",
                            r.getName() + " charged after you cancelled it",
                            InrFormat.format(p.amount()) + " was paid on " + p.lastDate() + ". Check with the provider, or mark it active again.",
                            "/subscriptions", "cancelled-charge:" + r.getId() + ":" + p.lastDate());
                }
                continue;
            }
            if (!newPayment) continue;
            BigDecimal old = r.getAmount();
            apply(r, p, m);
            if (!p.amountVaries() && old != null && old.compareTo(p.amount()) != 0) {
                r.setPreviousAmount(old);
                if (r.getStatus() == RecurringPayment.Status.ACTIVE) {
                    boolean up = p.amount().compareTo(old) > 0;
                    notifications.notify(userId, NotificationService.Topic.SUBSCRIPTION, "PRICE_CHANGE", up ? "WARNING" : "INFO",
                            r.getName() + " price " + (up ? "went up" : "went down"),
                            "Now " + InrFormat.format(p.amount()) + " instead of " + InrFormat.format(old) + " (" + p.frequency().name().toLowerCase()
                                    + "). That's " + InrFormat.format(p.amount().subtract(old).abs().multiply(BigDecimal.valueOf(p.frequency().perYear))) + " a year "
                                    + (up ? "more." : "less."),
                            "/subscriptions", "price:" + r.getId() + ":" + p.lastDate());
                }
            }
        }
        if (created > 0) audit.record(userId, "RECURRING_DETECTED", "recurring_payment", null, created + " new suggestions");
        return created;
    }

    private static void apply(RecurringPayment r, RecurringDetector.Pattern p, Meta m) {
        r.setFrequency(p.frequency());
        r.setAmount(p.amount());
        r.setAmountVaries(p.amountVaries());
        r.setLastPaidOn(p.lastDate());
        r.setNextDue(p.nextDue());
        r.setOccurrences(p.occurrences());
        if (m != null) {
            if (m.merchantId() != null) r.setMerchantId(m.merchantId());
            if (m.categoryId() != null) r.setCategoryId(m.categoryId());
            if (m.accountId() != null) r.setAccountId(m.accountId());
        }
    }

    private static String title(String s) {
        if (s == null || s.isBlank()) return "Recurring payment";
        String t = s.trim();
        if (t.length() > 120) t = t.substring(0, 120);
        return Character.toUpperCase(t.charAt(0)) + t.substring(1);
    }

    @Transactional(readOnly = true)
    public Summary summary(UUID userId) {
        LocalDate today = TimeConfig.today(clock);
        var info = categories.infoMap(userId);
        List<RecurringDto> items = repository.findByUserIdOrderByNextDueAsc(userId).stream().map(r -> toDto(r, today, info)).toList();
        BigDecimal committed = BigDecimal.ZERO, income = BigDecimal.ZERO, subs = BigDecimal.ZERO;
        int activeSubs = 0, suggestions = 0, due7 = 0, forgotten = 0;
        for (RecurringDto d : items) {
            if (d.status() == RecurringPayment.Status.SUGGESTED) suggestions++;
            if (d.status() != RecurringPayment.Status.ACTIVE) continue;
            if (d.income()) income = income.add(d.monthlyCost());
            else committed = committed.add(d.monthlyCost());
            if (d.kind() == RecurringDetector.Kind.SUBSCRIPTION) {
                activeSubs++;
                subs = subs.add(d.annualCost());
            }
            if (d.daysUntilDue() != null && d.daysUntilDue() >= 0 && d.daysUntilDue() <= 7 && !d.income()) due7++;
            if (d.forgotten()) forgotten++;
        }
        return new Summary(committed, income, subs, activeSubs, suggestions, due7, forgotten, items,
                "Detected items are only suggestions until you confirm them. Monthly cost converts weekly, quarterly and yearly amounts to a month.");
    }

    @Transactional
    public RecurringDto create(UUID userId, RecurringRequest r) {
        if (r.name() == null || r.name().isBlank()) throw ApiException.badRequest("Name is required");
        if (r.frequency() == null) throw ApiException.badRequest("Choose how often it repeats");
        BigDecimal amount = money(r.amount());
        boolean income = Boolean.TRUE.equals(r.income());
        RecurringPayment p = new RecurringPayment(userId, r.name().trim(), r.kind() != null ? r.kind() : income ? RecurringDetector.Kind.INCOME : RecurringDetector.Kind.OTHER,
                RecurringPayment.Status.ACTIVE, "manual:" + UUID.randomUUID(), r.frequency(), amount);
        p.setDirection(income ? "CREDIT" : "DEBIT");
        p.setNextDue(r.nextDue() != null ? r.nextDue() : TimeConfig.today(clock));
        p.setReviewedAt(Instant.now());
        applyEdits(userId, p, r);
        p = repository.save(p);
        audit.record(userId, "RECURRING_CREATED", "recurring_payment", p.getId(), p.getName());
        return toDto(p, TimeConfig.today(clock), categories.infoMap(userId));
    }

    @Transactional
    public RecurringDto update(UUID userId, UUID id, RecurringRequest r) {
        RecurringPayment p = require(userId, id);
        if (r.version() != null && r.version() != p.getVersion()) throw ApiException.conflict("This item changed elsewhere. Reload and try again.");
        if (r.name() != null && !r.name().isBlank()) p.setName(r.name().trim());
        if (r.frequency() != null) p.setFrequency(r.frequency());
        if (r.amount() != null) p.setAmount(money(r.amount()));
        if (r.kind() != null) p.setKind(r.kind());
        if (r.nextDue() != null) p.setNextDue(r.nextDue());
        applyEdits(userId, p, r);
        repository.flush();
        return toDto(p, TimeConfig.today(clock), categories.infoMap(userId));
    }

    private void applyEdits(UUID userId, RecurringPayment p, RecurringRequest r) {
        if (r.reminderDays() != null) {
            if (r.reminderDays() < 0 || r.reminderDays() > 30) throw ApiException.badRequest("Reminder must be 0 to 30 days before");
            p.setReminderDays(r.reminderDays());
        }
        if (r.categoryId() != null) p.setCategoryId(categories.require(userId, r.categoryId()).getId());
        if (r.accountId() != null) p.setAccountId(r.accountId());
        if (r.notes() != null) p.setNotes(r.notes().isBlank() ? null : r.notes().trim());
    }

    /** Confirms a suggestion (optionally correcting its kind) or reactivates a cancelled item. */
    @Transactional
    public RecurringDto setStatus(UUID userId, UUID id, RecurringPayment.Status status, RecurringDetector.Kind kind) {
        RecurringPayment p = require(userId, id);
        p.setStatus(status);
        if (kind != null) p.setKind(kind);
        if (status == RecurringPayment.Status.ACTIVE) p.setReviewedAt(Instant.now());
        audit.record(userId, "RECURRING_" + status.name(), "recurring_payment", id, p.getName());
        repository.flush();
        return toDto(p, TimeConfig.today(clock), categories.infoMap(userId));
    }

    @Transactional
    public RecurringDto markReviewed(UUID userId, UUID id) {
        RecurringPayment p = require(userId, id);
        p.setReviewedAt(Instant.now());
        repository.flush();
        return toDto(p, TimeConfig.today(clock), categories.infoMap(userId));
    }

    /** For items added by hand: records this cycle as paid and moves the due date on. */
    @Transactional
    public RecurringDto markPaid(UUID userId, UUID id) {
        RecurringPayment p = require(userId, id);
        LocalDate today = TimeConfig.today(clock);
        LocalDate base = p.getNextDue() != null ? p.getNextDue() : today;
        p.setLastPaidOn(today);
        LocalDate next = p.getFrequency().next(base);
        while (!next.isAfter(today)) next = p.getFrequency().next(next);
        p.setNextDue(next);
        p.setOccurrences(p.getOccurrences() + 1);
        repository.flush();
        return toDto(p, today, categories.infoMap(userId));
    }

    @Transactional
    public void delete(UUID userId, UUID id) {
        RecurringPayment p = require(userId, id);
        repository.delete(p);
        audit.record(userId, "RECURRING_DELETED", "recurring_payment", id, p.getName());
    }

    /** Reminders before due dates, overdue warnings and forgotten-subscription nudges. */
    @Transactional
    public int remind(UUID userId) {
        LocalDate today = TimeConfig.today(clock);
        int n = 0;
        var info = categories.infoMap(userId);
        for (RecurringPayment p : repository.findByUserIdAndStatus(userId, RecurringPayment.Status.ACTIVE)) {
            RecurringDto d = toDto(p, today, info);
            if (d.income() || d.nextDue() == null) continue;
            long days = d.daysUntilDue();
            if (days >= 0 && days <= p.getReminderDays()) {
                String when = days == 0 ? "today" : days == 1 ? "tomorrow" : "in " + days + " days";
                if (notifications.notify(userId, NotificationService.Topic.BILL, "BILL_DUE", "INFO", p.getName() + " due " + when,
                        (p.isAmountVaries() ? "About " : "") + InrFormat.format(p.getAmount()) + " on " + p.getNextDue() + ".", "/subscriptions",
                        "due:" + p.getId() + ":" + p.getNextDue())) n++;
            } else if (d.overdue()) {
                if (notifications.notify(userId, NotificationService.Topic.BILL, "BILL_MISSED", "WARNING", p.getName() + " looks overdue",
                        "It was expected on " + p.getNextDue() + " and no payment has shown up. If you paid elsewhere, mark it paid; if you stopped it, mark it cancelled.",
                        "/subscriptions", "overdue:" + p.getId() + ":" + p.getNextDue())) n++;
            }
            if (d.forgotten()) {
                if (notifications.notify(userId, NotificationService.Topic.SUBSCRIPTION, "SUBSCRIPTION_REVIEW", "INFO", "Still using " + p.getName() + "?",
                        "It costs " + InrFormat.format(d.annualCost()) + " a year and you haven't reviewed it in a while.", "/subscriptions",
                        "review-sub:" + p.getId() + ":" + today.withDayOfMonth(1))) n++;
            }
        }
        return n;
    }

    private RecurringDto toDto(RecurringPayment p, LocalDate today, Map<UUID, CategoryService.CategoryInfo> info) {
        Long days = p.getNextDue() == null ? null : ChronoUnit.DAYS.between(today, p.getNextDue());
        boolean manual = p.getMatchKey().startsWith("manual:");
        // detected items get a 3-day grace for bank posting delays; manual ones are overdue the day after
        boolean overdue = p.getStatus() == RecurringPayment.Status.ACTIVE && days != null && days < (manual ? 0 : -3);
        BigDecimal monthly = switch (p.getFrequency()) {
            case WEEKLY -> p.getAmount().multiply(BigDecimal.valueOf(52)).divide(BigDecimal.valueOf(12), 2, RoundingMode.HALF_UP);
            case MONTHLY -> p.getAmount();
            case QUARTERLY -> p.getAmount().divide(BigDecimal.valueOf(3), 2, RoundingMode.HALF_UP);
            case YEARLY -> p.getAmount().divide(BigDecimal.valueOf(12), 2, RoundingMode.HALF_UP);
        };
        BigDecimal annual = p.getAmount().multiply(BigDecimal.valueOf(p.getFrequency().perYear)).setScale(2, RoundingMode.HALF_UP);
        Instant reviewed = p.getReviewedAt() != null ? p.getReviewedAt() : p.getCreatedAt();
        boolean forgotten = p.getStatus() == RecurringPayment.Status.ACTIVE && p.getKind() == RecurringDetector.Kind.SUBSCRIPTION
                && reviewed.isBefore(Instant.now(clock).minus(Duration.ofDays(180)));
        boolean priceChanged = !p.isAmountVaries() && p.getPreviousAmount() != null && p.getPreviousAmount().compareTo(p.getAmount()) != 0;
        CategoryService.CategoryInfo ci = p.getCategoryId() == null ? null : info.get(p.getCategoryId());
        return new RecurringDto(p.getId(), p.getName(), p.getKind(), p.getStatus(), "CREDIT".equals(p.getDirection()), p.getFrequency(),
                p.getAmount(), p.getPreviousAmount(), priceChanged, p.isAmountVaries(), p.getLastPaidOn(), p.getNextDue(), days, overdue,
                p.getOccurrences(), monthly, annual, p.getReminderDays(), p.getCategoryId(), ci == null ? null : ci.path(), p.getAccountId(),
                !p.getMatchKey().startsWith("manual:"), forgotten, p.getNotes(), p.getVersion());
    }

    private static BigDecimal money(BigDecimal v) {
        if (v == null || v.signum() < 0) throw ApiException.badRequest("Enter the amount");
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    private RecurringPayment require(UUID userId, UUID id) {
        return repository.findByIdAndUserId(id, userId).orElseThrow(() -> ApiException.notFound("Recurring payment"));
    }
}
