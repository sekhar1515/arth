package com.expensetracker.notification;

import com.expensetracker.common.ApiException;
import com.expensetracker.user.UserRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One place that records notifications. Each has a dedupe key, so a reminder or alert is stored once even if the
 * hourly job sees the same condition again. E-mail is sent only when the user enabled it and SMTP is configured.
 */
@Service
public class NotificationService {

    public enum Topic { BUDGET, BILL, SUBSCRIPTION, CARD, UNUSUAL, GOAL, REVIEW, SYSTEM }

    public record NotificationDto(UUID id, String type, String severity, String title, String body, String link, Instant createdAt, boolean read) {
        static NotificationDto of(Notification n) {
            return new NotificationDto(n.getId(), n.getType(), n.getSeverity(), n.getTitle(), n.getBody(), n.getLink(), n.getCreatedAt(), n.getReadAt() != null);
        }
    }

    public record PreferencesDto(boolean emailEnabled, boolean emailAvailable, boolean browserEnabled, boolean budgetAlerts, boolean billReminders,
                                 boolean subscriptionAlerts, boolean cardReminders, boolean unusualSpending,
                                 boolean reviewReminders, BigDecimal largeTxnThreshold) {
    }

    private final NotificationRepository repository;
    private final NotificationPreferencesRepository preferences;
    private final UserRepository users;
    private final NamedParameterJdbcTemplate jdbc;
    private final Mailer mailer;

    public NotificationService(NotificationRepository repository, NotificationPreferencesRepository preferences, UserRepository users,
                               NamedParameterJdbcTemplate jdbc, Mailer mailer) {
        this.repository = repository;
        this.preferences = preferences;
        this.users = users;
        this.jdbc = jdbc;
        this.mailer = mailer;
    }

    /** Records a notification unless one with the same key exists or the topic is switched off. Returns true if new. */
    @Transactional
    public boolean notify(UUID userId, Topic topic, String type, String severity, String title, String body, String link, String dedupeKey) {
        NotificationPreferences p = prefs(userId);
        if (!enabled(p, topic)) return false;
        UUID id = UUID.randomUUID();
        int inserted = jdbc.update("""
                INSERT INTO notifications (id, user_id, type, severity, title, body, link, dedupe_key)
                VALUES (:id, :u, :type, :sev, :title, :body, :link, :key) ON CONFLICT (user_id, dedupe_key) DO NOTHING
                """, new MapSqlParameterSource("id", id).addValue("u", userId).addValue("type", type).addValue("sev", severity)
                .addValue("title", cut(title, 160)).addValue("body", cut(body, 1000)).addValue("link", link).addValue("key", cut(dedupeKey, 200)));
        if (inserted == 0) return false;
        if (p.isEmailEnabled() && mailer.available()) {
            users.findById(userId).ifPresent(u -> {
                if (!u.isDemo() && mailer.send(u.getEmail(), title, body, link)) {
                    jdbc.update("UPDATE notifications SET emailed = TRUE WHERE id = :id", new MapSqlParameterSource("id", id));
                }
            });
        }
        return true;
    }

    private static boolean enabled(NotificationPreferences p, Topic t) {
        return switch (t) {
            case BUDGET -> p.isBudgetAlerts();
            case BILL -> p.isBillReminders();
            case SUBSCRIPTION -> p.isSubscriptionAlerts();
            case CARD -> p.isCardReminders();
            case UNUSUAL -> p.isUnusualSpending();
            case GOAL -> false; // goals were removed; kept so old notifications still load
            case REVIEW -> p.isReviewReminders();
            case SYSTEM -> true;
        };
    }

    @Transactional(readOnly = true)
    public List<NotificationDto> list(UUID userId, boolean unreadOnly, int limit) {
        PageRequest page = PageRequest.of(0, Math.max(1, Math.min(limit, 200)));
        return (unreadOnly ? repository.findByUserIdAndReadAtIsNullOrderByCreatedAtDesc(userId, page)
                : repository.findByUserIdOrderByCreatedAtDesc(userId, page)).stream().map(NotificationDto::of).toList();
    }

    @Transactional(readOnly = true)
    public long unreadCount(UUID userId) {
        return repository.countByUserIdAndReadAtIsNull(userId);
    }

    @Transactional
    public void markRead(UUID userId, UUID id) {
        Notification n = repository.findByIdAndUserId(id, userId).orElseThrow(() -> ApiException.notFound("Notification"));
        if (n.getReadAt() == null) n.setReadAt(Instant.now());
    }

    @Transactional
    public int markAllRead(UUID userId) {
        return repository.markAllRead(userId, Instant.now());
    }

    @Transactional
    public void delete(UUID userId, UUID id) {
        repository.delete(repository.findByIdAndUserId(id, userId).orElseThrow(() -> ApiException.notFound("Notification")));
    }

    @Transactional
    public int clearRead(UUID userId) {
        return repository.deleteRead(userId);
    }

    @Transactional
    public NotificationPreferences prefs(UUID userId) {
        return preferences.findById(userId).orElseGet(() -> preferences.save(new NotificationPreferences(userId)));
    }

    @Transactional
    public PreferencesDto preferences(UUID userId) {
        return toDto(prefs(userId));
    }

    @Transactional
    public PreferencesDto updatePreferences(UUID userId, PreferencesDto r) {
        NotificationPreferences p = prefs(userId);
        if (r.emailEnabled() && !mailer.available()) {
            throw ApiException.badRequest("E-mail isn't set up on this server. Ask whoever runs Arth to configure SMTP (see README).");
        }
        p.setEmailEnabled(r.emailEnabled());
        p.setBrowserEnabled(r.browserEnabled());
        p.setBudgetAlerts(r.budgetAlerts());
        p.setBillReminders(r.billReminders());
        p.setSubscriptionAlerts(r.subscriptionAlerts());
        p.setCardReminders(r.cardReminders());
        p.setUnusualSpending(r.unusualSpending());
        p.setReviewReminders(r.reviewReminders());
        if (r.largeTxnThreshold() != null) {
            if (r.largeTxnThreshold().signum() <= 0) throw ApiException.badRequest("Large transaction amount must be more than zero");
            p.setLargeTxnThreshold(r.largeTxnThreshold());
        }
        preferences.flush();
        return toDto(p);
    }

    private PreferencesDto toDto(NotificationPreferences p) {
        return new PreferencesDto(p.isEmailEnabled(), mailer.available(), p.isBrowserEnabled(), p.isBudgetAlerts(), p.isBillReminders(),
                p.isSubscriptionAlerts(), p.isCardReminders(), p.isUnusualSpending(), p.isReviewReminders(), p.getLargeTxnThreshold());
    }

    private static String cut(String s, int n) {
        return s == null || s.length() <= n ? s : s.substring(0, n - 1) + "…";
    }
}
