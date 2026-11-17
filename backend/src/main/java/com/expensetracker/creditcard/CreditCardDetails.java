package com.expensetracker.creditcard;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Terms you enter once per card. Everything else (outstanding, cycles, dues) is computed from transactions. */
@Entity
@Table(name = "credit_card_details")
public class CreditCardDetails {
    @Id
    @Column(name = "account_id")
    private UUID accountId;
    @Column(name = "user_id", nullable = false)
    private UUID userId;
    @Column(name = "credit_limit", nullable = false, precision = 19, scale = 2)
    private BigDecimal creditLimit;
    @Column(name = "statement_day", nullable = false)
    private int statementDay;
    @Column(name = "due_days_after", nullable = false)
    private int dueDaysAfter = 20;
    @Column(name = "min_due_pct", nullable = false, precision = 5, scale = 2)
    private BigDecimal minDuePct = BigDecimal.valueOf(5);
    @Column(name = "min_due_floor", nullable = false, precision = 19, scale = 2)
    private BigDecimal minDueFloor = BigDecimal.valueOf(200);
    @Column(name = "reward_points", precision = 19, scale = 2)
    private BigDecimal rewardPoints;
    @Column(name = "reward_rate", precision = 8, scale = 3)
    private BigDecimal rewardRate;
    @Column(name = "reward_point_value", precision = 8, scale = 4)
    private BigDecimal rewardPointValue;
    @Column(name = "annual_fee", precision = 19, scale = 2)
    private BigDecimal annualFee;
    private String notes;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected CreditCardDetails() {
    }

    public CreditCardDetails(UUID accountId, UUID userId, BigDecimal creditLimit, int statementDay) {
        this.accountId = accountId;
        this.userId = userId;
        this.creditLimit = creditLimit;
        this.statementDay = statementDay;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    public UUID getAccountId() { return accountId; }
    public UUID getUserId() { return userId; }
    public BigDecimal getCreditLimit() { return creditLimit; }
    public void setCreditLimit(BigDecimal v) { creditLimit = v; }
    public int getStatementDay() { return statementDay; }
    public void setStatementDay(int v) { statementDay = v; }
    public int getDueDaysAfter() { return dueDaysAfter; }
    public void setDueDaysAfter(int v) { dueDaysAfter = v; }
    public BigDecimal getMinDuePct() { return minDuePct; }
    public void setMinDuePct(BigDecimal v) { minDuePct = v; }
    public BigDecimal getMinDueFloor() { return minDueFloor; }
    public void setMinDueFloor(BigDecimal v) { minDueFloor = v; }
    public BigDecimal getRewardPoints() { return rewardPoints; }
    public void setRewardPoints(BigDecimal v) { rewardPoints = v; }
    public BigDecimal getRewardRate() { return rewardRate; }
    public void setRewardRate(BigDecimal v) { rewardRate = v; }
    public BigDecimal getRewardPointValue() { return rewardPointValue; }
    public void setRewardPointValue(BigDecimal v) { rewardPointValue = v; }
    public BigDecimal getAnnualFee() { return annualFee; }
    public void setAnnualFee(BigDecimal v) { annualFee = v; }
    public String getNotes() { return notes; }
    public void setNotes(String v) { notes = v; }
}
