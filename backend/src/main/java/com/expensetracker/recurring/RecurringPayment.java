package com.expensetracker.recurring;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "recurring_payments")
public class RecurringPayment {

    public enum Status { SUGGESTED, ACTIVE, DISMISSED, CANCELLED }

    @Id
    private UUID id;
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;
    @Column(nullable = false)
    private String name;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RecurringDetector.Kind kind;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;
    @Column(nullable = false)
    private String direction = "DEBIT";
    @Column(name = "match_key", nullable = false)
    private String matchKey;
    @Column(name = "merchant_id")
    private UUID merchantId;
    @Column(name = "category_id")
    private UUID categoryId;
    @Column(name = "account_id")
    private UUID accountId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RecurringDetector.Frequency frequency;
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;
    @Column(name = "previous_amount", precision = 19, scale = 2)
    private BigDecimal previousAmount;
    @Column(name = "amount_varies", nullable = false)
    private boolean amountVaries;
    @Column(name = "last_paid_on")
    private LocalDate lastPaidOn;
    @Column(name = "next_due")
    private LocalDate nextDue;
    @Column(nullable = false)
    private int occurrences;
    @Column(name = "reminder_days", nullable = false)
    private int reminderDays = 3;
    @Column(name = "reviewed_at")
    private Instant reviewedAt;
    private String notes;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Version
    private long version;

    protected RecurringPayment() {
    }

    public RecurringPayment(UUID userId, String name, RecurringDetector.Kind kind, Status status, String matchKey,
                            RecurringDetector.Frequency frequency, BigDecimal amount) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.name = name;
        this.kind = kind;
        this.status = status;
        this.matchKey = matchKey;
        this.frequency = frequency;
        this.amount = amount;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public String getName() { return name; }
    public void setName(String v) { name = v; }
    public RecurringDetector.Kind getKind() { return kind; }
    public void setKind(RecurringDetector.Kind v) { kind = v; }
    public Status getStatus() { return status; }
    public void setStatus(Status v) { status = v; }
    public String getDirection() { return direction; }
    public void setDirection(String v) { direction = v; }
    public String getMatchKey() { return matchKey; }
    public UUID getMerchantId() { return merchantId; }
    public void setMerchantId(UUID v) { merchantId = v; }
    public UUID getCategoryId() { return categoryId; }
    public void setCategoryId(UUID v) { categoryId = v; }
    public UUID getAccountId() { return accountId; }
    public void setAccountId(UUID v) { accountId = v; }
    public RecurringDetector.Frequency getFrequency() { return frequency; }
    public void setFrequency(RecurringDetector.Frequency v) { frequency = v; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal v) { amount = v; }
    public BigDecimal getPreviousAmount() { return previousAmount; }
    public void setPreviousAmount(BigDecimal v) { previousAmount = v; }
    public boolean isAmountVaries() { return amountVaries; }
    public void setAmountVaries(boolean v) { amountVaries = v; }
    public LocalDate getLastPaidOn() { return lastPaidOn; }
    public void setLastPaidOn(LocalDate v) { lastPaidOn = v; }
    public LocalDate getNextDue() { return nextDue; }
    public void setNextDue(LocalDate v) { nextDue = v; }
    public int getOccurrences() { return occurrences; }
    public void setOccurrences(int v) { occurrences = v; }
    public int getReminderDays() { return reminderDays; }
    public void setReminderDays(int v) { reminderDays = v; }
    public Instant getReviewedAt() { return reviewedAt; }
    public void setReviewedAt(Instant v) { reviewedAt = v; }
    public String getNotes() { return notes; }
    public void setNotes(String v) { notes = v; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getVersion() { return version; }
}
