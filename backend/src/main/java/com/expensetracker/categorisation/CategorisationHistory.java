package com.expensetracker.categorisation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "categorisation_history")
public class CategorisationHistory {
    @Id
    private UUID id;
    @Column(name = "transaction_id", nullable = false)
    private UUID transactionId;
    @Column(name = "from_category_id")
    private UUID fromCategoryId;
    @Column(name = "to_category_id")
    private UUID toCategoryId;
    @Column(nullable = false)
    private String action;
    @Column(nullable = false)
    private String actor;
    private String detail;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected CategorisationHistory() {
    }

    public CategorisationHistory(UUID transactionId, UUID from, UUID to, String action, String actor, String detail) {
        this.id = UUID.randomUUID();
        this.transactionId = transactionId;
        this.fromCategoryId = from;
        this.toCategoryId = to;
        this.action = action;
        this.actor = actor;
        this.detail = detail == null ? null : (detail.length() > 300 ? detail.substring(0, 300) : detail);
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getTransactionId() { return transactionId; }
    public UUID getFromCategoryId() { return fromCategoryId; }
    public UUID getToCategoryId() { return toCategoryId; }
    public String getAction() { return action; }
    public String getActor() { return actor; }
    public String getDetail() { return detail; }
    public Instant getCreatedAt() { return createdAt; }
}
