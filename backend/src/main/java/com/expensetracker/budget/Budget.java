package com.expensetracker.budget;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "budgets")
public class Budget {
    @Id
    private UUID id;
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;
    @Column(nullable = false)
    private String name;
    @Column(name = "category_id")
    private UUID categoryId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BudgetPeriods.Period period;
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;
    @Column(nullable = false)
    private boolean rollover;
    @Column(name = "alert_thresholds", nullable = false)
    private String alertThresholds = "50,75,90,100";
    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;
    @Column(nullable = false)
    private boolean archived;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Version
    private long version;

    protected Budget() {
    }

    public Budget(UUID userId, String name, UUID categoryId, BudgetPeriods.Period period, BigDecimal amount, LocalDate startDate) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.name = name;
        this.categoryId = categoryId;
        this.period = period;
        this.amount = amount;
        this.startDate = startDate;
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
    public UUID getCategoryId() { return categoryId; }
    public BudgetPeriods.Period getPeriod() { return period; }
    public void setPeriod(BudgetPeriods.Period v) { period = v; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal v) { amount = v; }
    public boolean isRollover() { return rollover; }
    public void setRollover(boolean v) { rollover = v; }
    public String getAlertThresholds() { return alertThresholds; }
    public void setAlertThresholds(String v) { alertThresholds = v; }
    public LocalDate getStartDate() { return startDate; }
    public boolean isArchived() { return archived; }
    public void setArchived(boolean v) { archived = v; }
    public long getVersion() { return version; }
}
