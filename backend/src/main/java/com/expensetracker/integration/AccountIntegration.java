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
@Table(name = "account_integrations")
public class AccountIntegration {

    public enum Status { CONNECTED, ERROR, DISCONNECTED }

    @Id
    private UUID id;
    @Column(name = "user_id", nullable = false)
    private UUID userId;
    @Column(nullable = false)
    private String provider;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;
    @Column(name = "default_account_id")
    private UUID defaultAccountId;
    @Column(name = "token_hash")
    private String tokenHash;
    @Column(name = "token_hint")
    private String tokenHint;
    @Column(name = "last_sync_at")
    private Instant lastSyncAt;
    @Column(name = "next_sync_at")
    private Instant nextSyncAt;
    @Column(name = "last_error")
    private String lastError;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AccountIntegration() {
    }

    public AccountIntegration(UUID userId, String provider) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.provider = provider;
        this.status = Status.CONNECTED;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public String getProvider() { return provider; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public UUID getDefaultAccountId() { return defaultAccountId; }
    public void setDefaultAccountId(UUID id) { this.defaultAccountId = id; }
    public String getTokenHash() { return tokenHash; }
    public void setTokenHash(String tokenHash) { this.tokenHash = tokenHash; }
    public String getTokenHint() { return tokenHint; }
    public void setTokenHint(String tokenHint) { this.tokenHint = tokenHint; }
    public Instant getLastSyncAt() { return lastSyncAt; }
    public void setLastSyncAt(Instant t) { this.lastSyncAt = t; }
    public Instant getNextSyncAt() { return nextSyncAt; }
    public String getLastError() { return lastError; }
    public void setLastError(String e) { this.lastError = e == null ? null : (e.length() > 500 ? e.substring(0, 500) : e); }
    public Instant getCreatedAt() { return createdAt; }
}
