package com.expensetracker.categorisation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "merchant_rules")
public class MerchantRule {
    @Id
    private UUID id;
    @Column(name = "user_id", nullable = false)
    private UUID userId;
    @Enumerated(EnumType.STRING)
    @Column(name = "match_type", nullable = false)
    private RuleMatchType matchType;
    @Column(name = "merchant_id")
    private UUID merchantId;
    private String pattern;
    @Column(name = "category_id", nullable = false)
    private UUID categoryId;
    @Column(name = "hit_count", nullable = false)
    private int hitCount;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected MerchantRule() {
    }

    public static MerchantRule forMerchant(UUID userId, UUID merchantId, UUID categoryId) {
        MerchantRule r = new MerchantRule(userId, RuleMatchType.MERCHANT, categoryId);
        r.merchantId = merchantId;
        return r;
    }

    public static MerchantRule forPattern(UUID userId, String pattern, UUID categoryId) {
        MerchantRule r = new MerchantRule(userId, RuleMatchType.DESCRIPTION_CONTAINS, categoryId);
        r.pattern = pattern;
        return r;
    }

    public static MerchantRule forNote(UUID userId, String noteKeyword, UUID categoryId) {
        MerchantRule r = new MerchantRule(userId, RuleMatchType.NOTE_KEYWORD, categoryId);
        r.pattern = noteKeyword;
        return r;
    }

    private MerchantRule(UUID userId, RuleMatchType type, UUID categoryId) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.matchType = type;
        this.categoryId = categoryId;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public RuleMatchType getMatchType() { return matchType; }
    public UUID getMerchantId() { return merchantId; }
    public String getPattern() { return pattern; }
    public UUID getCategoryId() { return categoryId; }
    public void setCategoryId(UUID categoryId) { this.categoryId = categoryId; }
    public int getHitCount() { return hitCount; }
    public void recordHit() { this.hitCount++; }
    public Instant getCreatedAt() { return createdAt; }
}
