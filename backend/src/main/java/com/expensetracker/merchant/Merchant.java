package com.expensetracker.merchant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "merchants")
public class Merchant {
    @Id
    private UUID id;
    @Column(name = "user_id", nullable = false)
    private UUID userId;
    @Column(nullable = false)
    private String name;
    @Column(name = "normalized_name", nullable = false)
    private String normalizedName;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Merchant() {
    }

    public Merchant(UUID userId, String name, String normalizedName) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.name = name;
        this.normalizedName = normalizedName;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public String getName() { return name; }
    public String getNormalizedName() { return normalizedName; }
}
