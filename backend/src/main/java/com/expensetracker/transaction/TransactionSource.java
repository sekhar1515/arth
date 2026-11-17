package com.expensetracker.transaction;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Where a transaction came from. One transaction can have several sources after deduplication. */
@Entity
@Table(name = "transaction_sources")
public class TransactionSource {
    @Id
    private UUID id;
    @Column(name = "user_id", nullable = false)
    private UUID userId;
    @Column(name = "transaction_id", nullable = false)
    private UUID transactionId;
    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false)
    private SourceType sourceType;
    @Column(name = "external_id", nullable = false)
    private String externalId;
    @Column(name = "integration_id")
    private UUID integrationId;
    @Column(name = "raw_payload")
    private String rawPayload;
    @Column(name = "imported_at", nullable = false)
    private Instant importedAt;
    @Column(name = "sync_id")
    private UUID syncId;

    protected TransactionSource() {
    }

    public TransactionSource(UUID userId, UUID transactionId, SourceType sourceType, String externalId,
                             UUID integrationId, String rawPayload) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.transactionId = transactionId;
        this.sourceType = sourceType;
        this.externalId = externalId;
        this.integrationId = integrationId;
        this.rawPayload = rawPayload;
        this.importedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getTransactionId() { return transactionId; }
    public void setTransactionId(UUID transactionId) { this.transactionId = transactionId; }
    public SourceType getSourceType() { return sourceType; }
    public String getExternalId() { return externalId; }
    public String getRawPayload() { return rawPayload; }
    public Instant getImportedAt() { return importedAt; }
    public UUID getSyncId() { return syncId; }
    public void setSyncId(UUID v) { this.syncId = v; }
    public UUID getUserId() { return userId; }
}
