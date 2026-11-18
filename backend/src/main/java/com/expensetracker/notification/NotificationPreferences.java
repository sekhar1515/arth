package com.expensetracker.notification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "notification_preferences")
public class NotificationPreferences {
    @Id
    @Column(name = "user_id")
    private UUID userId;
    @Column(name = "email_enabled", nullable = false)
    private boolean emailEnabled;
    @Column(name = "browser_enabled", nullable = false)
    private boolean browserEnabled = true;
    @Column(name = "budget_alerts", nullable = false)
    private boolean budgetAlerts = true;
    @Column(name = "bill_reminders", nullable = false)
    private boolean billReminders = true;
    @Column(name = "subscription_alerts", nullable = false)
    private boolean subscriptionAlerts = true;
    @Column(name = "card_reminders", nullable = false)
    private boolean cardReminders = true;
    @Column(name = "unusual_spending", nullable = false)
    private boolean unusualSpending = true;
    @Column(name = "review_reminders", nullable = false)
    private boolean reviewReminders = true;
    @Column(name = "large_txn_threshold", nullable = false, precision = 19, scale = 2)
    private BigDecimal largeTxnThreshold = BigDecimal.valueOf(10000);
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected NotificationPreferences() {
    }

    public NotificationPreferences(UUID userId) {
        this.userId = userId;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    public UUID getUserId() { return userId; }
    public boolean isEmailEnabled() { return emailEnabled; }
    public void setEmailEnabled(boolean v) { emailEnabled = v; }
    public boolean isBrowserEnabled() { return browserEnabled; }
    public void setBrowserEnabled(boolean v) { browserEnabled = v; }
    public boolean isBudgetAlerts() { return budgetAlerts; }
    public void setBudgetAlerts(boolean v) { budgetAlerts = v; }
    public boolean isBillReminders() { return billReminders; }
    public void setBillReminders(boolean v) { billReminders = v; }
    public boolean isSubscriptionAlerts() { return subscriptionAlerts; }
    public void setSubscriptionAlerts(boolean v) { subscriptionAlerts = v; }
    public boolean isCardReminders() { return cardReminders; }
    public void setCardReminders(boolean v) { cardReminders = v; }
    public boolean isUnusualSpending() { return unusualSpending; }
    public void setUnusualSpending(boolean v) { unusualSpending = v; }
    public boolean isReviewReminders() { return reviewReminders; }
    public void setReviewReminders(boolean v) { reviewReminders = v; }
    public BigDecimal getLargeTxnThreshold() { return largeTxnThreshold; }
    public void setLargeTxnThreshold(BigDecimal v) { largeTxnThreshold = v; }
}
