package com.expensetracker.goal;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Money put toward (positive) or taken out of (negative) a goal. */
@Entity
@Table(name = "goal_contributions")
public class GoalContribution {
    @Id
    private UUID id;
    @Column(name = "goal_id", nullable = false)
    private UUID goalId;
    @Column(name = "user_id", nullable = false)
    private UUID userId;
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;
    @Column(name = "contributed_on", nullable = false)
    private LocalDate contributedOn;
    @Column(name = "transaction_id")
    private UUID transactionId;
    private String note;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected GoalContribution() {
    }

    public GoalContribution(UUID goalId, UUID userId, BigDecimal amount, LocalDate on) {
        this.id = UUID.randomUUID();
        this.goalId = goalId;
        this.userId = userId;
        this.amount = amount;
        this.contributedOn = on;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getGoalId() { return goalId; }
    public UUID getUserId() { return userId; }
    public BigDecimal getAmount() { return amount; }
    public LocalDate getContributedOn() { return contributedOn; }
    public UUID getTransactionId() { return transactionId; }
    public void setTransactionId(UUID v) { transactionId = v; }
    public String getNote() { return note; }
    public void setNote(String v) { note = v; }
}
