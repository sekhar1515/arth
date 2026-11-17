package com.expensetracker.category;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** A category; a "subcategory" is simply a category with a parent (ADR-1). */
@Entity
@Table(name = "categories")
public class Category {
    @Id
    private UUID id;
    @Column(name = "user_id", nullable = false)
    private UUID userId;
    @Column(name = "parent_id")
    private UUID parentId;
    @Column(nullable = false)
    private String name;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CategoryKind kind;
    @Column(name = "group_name")
    private String groupName;
    private String icon;
    private String color;
    @Column(nullable = false)
    private boolean archived;
    @Column(name = "system_default", nullable = false)
    private boolean systemDefault;
    @Column(name = "sort_order", nullable = false)
    private int sortOrder;
    /** Money moved into investments: counted as saved on the dashboard, not as spending. */
    @Column(name = "is_investment", nullable = false)
    private boolean investment;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Category() {
    }

    public Category(UUID userId, UUID parentId, String name, CategoryKind kind) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.parentId = parentId;
        this.name = name;
        this.kind = kind;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public UUID getParentId() { return parentId; }
    public void setParentId(UUID parentId) { this.parentId = parentId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public CategoryKind getKind() { return kind; }
    public String getGroupName() { return groupName; }
    public void setGroupName(String groupName) { this.groupName = groupName; }
    public String getIcon() { return icon; }
    public void setIcon(String icon) { this.icon = icon; }
    public String getColor() { return color; }
    public void setColor(String color) { this.color = color; }
    public boolean isArchived() { return archived; }
    public void setArchived(boolean archived) { this.archived = archived; }
    public boolean isSystemDefault() { return systemDefault; }
    public void setSystemDefault(boolean systemDefault) { this.systemDefault = systemDefault; }
    public int getSortOrder() { return sortOrder; }
    public boolean isInvestment() { return investment; }
    public void setInvestment(boolean investment) { this.investment = investment; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
}
