package com.expensetracker.notification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "notifications")
public class Notification {
    @Id
    private UUID id;
    @Column(name = "user_id", nullable = false)
    private UUID userId;
    @Column(nullable = false)
    private String type;
    @Column(nullable = false)
    private String severity;
    @Column(nullable = false)
    private String title;
    @Column(nullable = false)
    private String body;
    private String link;
    @Column(name = "dedupe_key", nullable = false)
    private String dedupeKey;
    @Column(nullable = false)
    private boolean emailed;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "read_at")
    private Instant readAt;

    protected Notification() {
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public String getType() { return type; }
    public String getSeverity() { return severity; }
    public String getTitle() { return title; }
    public String getBody() { return body; }
    public String getLink() { return link; }
    public String getDedupeKey() { return dedupeKey; }
    public boolean isEmailed() { return emailed; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getReadAt() { return readAt; }
    public void setReadAt(Instant v) { this.readAt = v; }
}
