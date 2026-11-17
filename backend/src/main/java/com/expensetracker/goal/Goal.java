package com.expensetracker.goal;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "financial_goals")
public class Goal {

    public enum Kind { EMERGENCY_FUND, HOUSE, CAR, TRAVEL, EDUCATION, RETIREMENT, WEDDING, GADGET, DEBT_PAYOFF, OTHER }

    public enum Status { ACTIVE, PAUSED, ACHIEVED, ARCHIVED }

    @Id
    private UUID id;
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;
    @Column(nullable = false)
    private String name;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Kind kind;
    @Column(name = "target_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal targetAmount;
    @Column(name = "target_date")
    private LocalDate targetDate;
    @Column(name = "starting_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal startingAmount = BigDecimal.ZERO;
    @Column(name = "expected_return_pct", nullable = false, precision = 6, scale = 3)
    private BigDecimal expectedReturnPct = BigDecimal.ZERO;
    @Column(nullable = false)
    private int priority = 2;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.ACTIVE;
    private String notes;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Version
    private long version;

    protected Goal() {
    }

    public Goal(UUID userId, String name, Kind kind, BigDecimal targetAmount) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.name = name;
        this.kind = kind;
        this.targetAmount = targetAmount;
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
    public Kind getKind() { return kind; }
    public void setKind(Kind v) { kind = v; }
    public BigDecimal getTargetAmount() { return targetAmount; }
    public void setTargetAmount(BigDecimal v) { targetAmount = v; }
    public LocalDate getTargetDate() { return targetDate; }
    public void setTargetDate(LocalDate v) { targetDate = v; }
    public BigDecimal getStartingAmount() { return startingAmount; }
    public void setStartingAmount(BigDecimal v) { startingAmount = v; }
    public BigDecimal getExpectedReturnPct() { return expectedReturnPct; }
    public void setExpectedReturnPct(BigDecimal v) { expectedReturnPct = v; }
    public int getPriority() { return priority; }
    public void setPriority(int v) { priority = v; }
    public Status getStatus() { return status; }
    public void setStatus(Status v) { status = v; }
    public String getNotes() { return notes; }
    public void setNotes(String v) { notes = v; }
    public Instant getCreatedAt() { return createdAt; }
    public long getVersion() { return version; }
}
