package com.expensetracker.integration;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "sync_history")
public class SyncHistory {

    public enum Status { RUNNING, SUCCESS, PARTIAL, FAILED }

    @Id
    private UUID id;
    @Column(name = "user_id", nullable = false)
    private UUID userId;
    @Column(name = "integration_id")
    private UUID integrationId;
    @Column(name = "source_type", nullable = false)
    private String sourceType;
    @Column(name = "started_at", nullable = false)
    private Instant startedAt;
    @Column(name = "finished_at")
    private Instant finishedAt;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;
    private int imported;
    private int merged;
    private int suspected;
    private int skipped;
    private String error;

    protected SyncHistory() {
    }

    public SyncHistory(UUID userId, UUID integrationId, String sourceType) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.integrationId = integrationId;
        this.sourceType = sourceType;
        this.startedAt = Instant.now();
        this.status = Status.RUNNING;
    }

    public void finish(Status status, int imported, int merged, int suspected, int skipped, String error) {
        this.status = status;
        this.imported = imported;
        this.merged = merged;
        this.suspected = suspected;
        this.skipped = skipped;
        this.error = error == null ? null : (error.length() > 1000 ? error.substring(0, 1000) : error);
        this.finishedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getIntegrationId() { return integrationId; }
    public String getSourceType() { return sourceType; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public Status getStatus() { return status; }
    public int getImported() { return imported; }
    public int getMerged() { return merged; }
    public int getSuspected() { return suspected; }
    public int getSkipped() { return skipped; }
    public String getError() { return error; }
}
