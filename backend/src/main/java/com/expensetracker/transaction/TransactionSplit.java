package com.expensetracker.transaction;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "transaction_splits")
public class TransactionSplit {
    @Id
    private UUID id;
    @Column(name = "transaction_id", nullable = false)
    private UUID transactionId;
    @Column(name = "category_id", nullable = false)
    private UUID categoryId;
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;
    private String note;

    protected TransactionSplit() {
    }

    public TransactionSplit(UUID transactionId, UUID categoryId, BigDecimal amount, String note) {
        this.id = UUID.randomUUID();
        this.transactionId = transactionId;
        this.categoryId = categoryId;
        this.amount = amount;
        this.note = note;
    }

    public UUID getId() { return id; }
    public UUID getTransactionId() { return transactionId; }
    public UUID getCategoryId() { return categoryId; }
    public BigDecimal getAmount() { return amount; }
    public String getNote() { return note; }
}
