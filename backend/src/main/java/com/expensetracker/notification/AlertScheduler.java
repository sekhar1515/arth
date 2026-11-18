package com.expensetracker.notification;

import com.expensetracker.analytics.InrFormat;
import com.expensetracker.budget.BudgetService;
import com.expensetracker.creditcard.CreditCardService;
import com.expensetracker.recurring.RecurringService;
import com.expensetracker.user.User;
import com.expensetracker.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.IsoFields;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Background checks that turn your data into reminders and alerts. Each check is idempotent (notifications have
 * dedupe keys), so running it again never duplicates anything.
 */
@Component
public class AlertScheduler {
    private static final Logger log = LoggerFactory.getLogger(AlertScheduler.class);

    private final UserRepository users;
    private final BudgetService budgets;
    private final RecurringService recurring;
    private final CreditCardService cards;
    private final NotificationService notifications;
    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    public AlertScheduler(UserRepository users, BudgetService budgets, RecurringService recurring, CreditCardService cards,
                          NotificationService notifications, NamedParameterJdbcTemplate jdbc, Clock clock) {
        this.users = users;
        this.budgets = budgets;
        this.recurring = recurring;
        this.cards = cards;
        this.notifications = notifications;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Scheduled(cron = "0 5 * * * *", zone = "Asia/Kolkata")
    public void hourly() {
        for (User u : users.findAll()) runFor(u.getId());
    }

    /** Recurring-payment detection once a day, after most bank statements for the day are in. */
    @Scheduled(cron = "0 30 2 * * *", zone = "Asia/Kolkata")
    public void nightlyDetection() {
        for (User u : users.findAll()) safe("detect recurring", u.getId(), () -> recurring.detect(u.getId()));
    }

    public int runFor(UUID userId) {
        int n = 0;
        n += safe("budgets", userId, () -> budgets.checkAlerts(userId));
        n += safe("recurring", userId, () -> recurring.remind(userId));
        n += safe("cards", userId, () -> cards.remind(userId));
        n += safe("large transactions", userId, () -> largeTransactions(userId));
        n += safe("review", userId, () -> reviewReminder(userId));
        return n;
    }

    int largeTransactions(UUID userId) {
        var threshold = notifications.prefs(userId).getLargeTxnThreshold();
        int[] n = {0};
        jdbc.query("""
                SELECT t.id, t.amount, COALESCE(m.name, t.notes, t.counterparty, t.description, 'a payment') AS label,
                       CAST(t.txn_at AT TIME ZONE 'Asia/Kolkata' AS date) AS day
                FROM transactions t LEFT JOIN merchants m ON m.id = t.merchant_id
                LEFT JOIN categories c ON c.id = t.category_id LEFT JOIN categories p ON p.id = c.parent_id
                WHERE t.user_id = :u AND t.created_at >= :since AND t.direction = 'DEBIT' AND t.kind = 'EXPENSE'
                  AND t.duplicate_status = 'NONE' AND t.amount >= :threshold AND NOT COALESCE(p.is_investment, c.is_investment, FALSE)
                """, new MapSqlParameterSource("u", userId).addValue("since", java.sql.Timestamp.from(Instant.now(clock).minus(Duration.ofHours(26))))
                .addValue("threshold", threshold), rs -> {
            if (notifications.notify(userId, NotificationService.Topic.UNUSUAL, "LARGE_TRANSACTION", "INFO",
                    "Large payment: " + InrFormat.format(rs.getBigDecimal("amount")),
                    rs.getString("label") + " on " + rs.getDate("day") + ". You get this alert for payments of " + InrFormat.format(threshold) + " or more.",
                    "/transactions/" + rs.getObject("id"), "large:" + rs.getObject("id"))) n[0]++;
        });
        return n[0];
    }

    int reviewReminder(UUID userId) {
        Integer pending = jdbc.queryForObject("SELECT COUNT(*) FROM transactions WHERE user_id = :u AND categorisation_status = 'PENDING' AND kind IN ('EXPENSE', 'INCOME')",
                new MapSqlParameterSource("u", userId), Integer.class);
        if (pending == null || pending < 5) return 0;
        LocalDate today = LocalDate.now(clock.withZone(com.expensetracker.common.TimeConfig.IST));
        String week = today.getYear() + "-W" + today.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
        return notifications.notify(userId, NotificationService.Topic.REVIEW, "REVIEW_PENDING", "INFO", pending + " transactions need a category",
                "They count in your totals but not in categories or category budgets until you review them.", "/review", "review:" + week) ? 1 : 0;
    }

    private int safe(String what, UUID userId, Supplier<Integer> job) {
        try {
            return job.get();
        } catch (RuntimeException e) {
            log.warn("alert check '{}' failed for user {}: {}", what, userId, e.toString());
            return 0;
        }
    }
}
