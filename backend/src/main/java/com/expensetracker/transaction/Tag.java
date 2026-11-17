package com.expensetracker.transaction;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "tags")
public class Tag {
    @Id
    private UUID id;
    @Column(name = "user_id", nullable = false)
    private UUID userId;
    @Column(nullable = false)
    private String name;

    protected Tag() {
    }

    public Tag(UUID userId, String name) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.name = name;
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public String getName() { return name; }
}
