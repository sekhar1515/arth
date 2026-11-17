package com.expensetracker.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "user_preferences")
public class UserPreferences {
    @Id
    @Column(name = "user_id")
    private UUID userId;
    @Enumerated(EnumType.STRING)
    @Column(name = "categorisation_mode", nullable = false)
    private CategorisationMode categorisationMode = CategorisationMode.ALWAYS_ASK;
    @Column(name = "auto_confidence_threshold", nullable = false, precision = 4, scale = 3)
    private BigDecimal autoConfidenceThreshold = new BigDecimal("0.900");
    @Column(nullable = false, length = 3)
    private String currency = "INR";
    @Column(nullable = false)
    private String theme = "system";
    @Column(name = "external_ai_consent", nullable = false)
    private boolean externalAiConsent;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected UserPreferences() {
    }

    public UserPreferences(UUID userId) {
        this.userId = userId;
    }

    public UUID getUserId() { return userId; }
    public CategorisationMode getCategorisationMode() { return categorisationMode; }
    public void setCategorisationMode(CategorisationMode m) { this.categorisationMode = m; touch(); }
    public BigDecimal getAutoConfidenceThreshold() { return autoConfidenceThreshold; }
    public void setAutoConfidenceThreshold(BigDecimal t) { this.autoConfidenceThreshold = t; touch(); }
    public String getCurrency() { return currency; }
    public String getTheme() { return theme; }
    public void setTheme(String theme) { this.theme = theme; touch(); }
    public boolean isExternalAiConsent() { return externalAiConsent; }
    public void setExternalAiConsent(boolean v) { this.externalAiConsent = v; touch(); }
    public Instant getUpdatedAt() { return updatedAt; }

    private void touch() {
        this.updatedAt = Instant.now();
    }
}
