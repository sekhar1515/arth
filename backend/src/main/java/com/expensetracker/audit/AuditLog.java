package com.expensetracker.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "audit_log")
public class AuditLog {
    @Id
    private UUID id;
    @Column(name = "user_id")
    private UUID userId;
    @Column(nullable = false)
    private String action;
    @Column(name = "entity_type")
    private String entityType;
    @Column(name = "entity_id")
    private UUID entityId;
    private String detail;
    @Column(name = "ip_address")
    private String ipAddress;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AuditLog() {
    }

    public AuditLog(UUID userId, String action, String entityType, UUID entityId, String detail, String ipAddress) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.action = action;
        this.entityType = entityType;
        this.entityId = entityId;
        this.detail = detail == null ? null : (detail.length() > 1000 ? detail.substring(0, 1000) : detail);
        this.ipAddress = ipAddress;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public String getAction() { return action; }
    public String getEntityType() { return entityType; }
    public UUID getEntityId() { return entityId; }
    public String getDetail() { return detail; }
    public Instant getCreatedAt() { return createdAt; }
}
