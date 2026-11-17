package com.expensetracker.integration;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "integration_consents")
public class IntegrationConsent {
    @Id
    private UUID id;
    @Column(name = "integration_id", nullable = false)
    private UUID integrationId;
    @Column(nullable = false)
    private String scope;
    @Column(name = "granted_at", nullable = false)
    private Instant grantedAt;
    @Column(name = "revoked_at")
    private Instant revokedAt;

    protected IntegrationConsent() {
    }

    public IntegrationConsent(UUID integrationId, String scope) {
        this.id = UUID.randomUUID();
        this.integrationId = integrationId;
        this.scope = scope;
        this.grantedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getIntegrationId() { return integrationId; }
    public String getScope() { return scope; }
    public Instant getGrantedAt() { return grantedAt; }
    public Instant getRevokedAt() { return revokedAt; }
    public void revoke() { this.revokedAt = Instant.now(); }
}
