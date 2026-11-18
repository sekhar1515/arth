package com.expensetracker.networth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** A SIP into a fund, or a regular contribution (PF/EPF, PPF, NPS, RD…) to an investment, on a day each month. */
@Entity
@Table(name = "investment_plans")
public class InvestmentPlan {

    public enum Kind { SIP, CONTRIBUTION }

    @Id
    private UUID id;
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;
    @Column(name = "holding_id", nullable = false)
    private UUID holdingId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Kind kind;
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;
    @Column(name = "employer_amount", precision = 19, scale = 2)
    private BigDecimal employerAmount;
    @Column(name = "day_of_month", nullable = false)
    private int dayOfMonth;
    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;
    @Column(name = "end_date")
    private LocalDate endDate;
    @Column(name = "account_id")
    private UUID accountId;
    @Column(nullable = false)
    private boolean active = true;
    @Column(name = "last_run_date")
    private LocalDate lastRunDate;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Version
    private long version;

    protected InvestmentPlan() {
    }

    public InvestmentPlan(UUID userId, UUID holdingId, Kind kind) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.holdingId = holdingId;
        this.kind = kind;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    /** Total that goes into the investment each time (your part plus any employer part). */
    public BigDecimal total() {
        return employerAmount == null ? amount : amount.add(employerAmount);
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public UUID getHoldingId() { return holdingId; }
    public Kind getKind() { return kind; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal v) { amount = v; }
    public BigDecimal getEmployerAmount() { return employerAmount; }
    public void setEmployerAmount(BigDecimal v) { employerAmount = v; }
    public int getDayOfMonth() { return dayOfMonth; }
    public void setDayOfMonth(int v) { dayOfMonth = v; }
    public LocalDate getStartDate() { return startDate; }
    public void setStartDate(LocalDate v) { startDate = v; }
    public LocalDate getEndDate() { return endDate; }
    public void setEndDate(LocalDate v) { endDate = v; }
    public UUID getAccountId() { return accountId; }
    public void setAccountId(UUID v) { accountId = v; }
    public boolean isActive() { return active; }
    public void setActive(boolean v) { active = v; }
    public LocalDate getLastRunDate() { return lastRunDate; }
    public void setLastRunDate(LocalDate v) { lastRunDate = v; }
    public long getVersion() { return version; }
}
