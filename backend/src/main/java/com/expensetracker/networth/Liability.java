package com.expensetracker.networth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Money you owe outside credit cards (card outstanding comes from card accounts). */
@Entity
@Table(name = "liabilities")
public class Liability {

    public enum Type { HOME_LOAN, CAR_LOAN, PERSONAL_LOAN, EDUCATION_LOAN, GOLD_LOAN, LOAN_AGAINST_SECURITIES, BORROWED_FROM_PERSON, OTHER }

    @Id
    private UUID id;
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Type type;
    @Column(nullable = false)
    private String name;
    private String lender;
    @Column(precision = 19, scale = 2)
    private BigDecimal principal;
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal outstanding;
    @Column(name = "interest_rate", precision = 6, scale = 3)
    private BigDecimal interestRate;
    @Column(precision = 19, scale = 2)
    private BigDecimal emi;
    @Column(name = "start_date")
    private LocalDate startDate;
    @Column(name = "end_date")
    private LocalDate endDate;
    @Column(name = "as_of", nullable = false)
    private LocalDate asOf;
    private String notes;
    @Column(nullable = false)
    private boolean archived;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Version
    private long version;

    protected Liability() {
    }

    public Liability(UUID userId, Type type, String name, BigDecimal outstanding, LocalDate asOf) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.type = type;
        this.name = name;
        this.outstanding = outstanding;
        this.asOf = asOf;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    @PreUpdate
    void touch() {
        this.updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public Type getType() { return type; }
    public void setType(Type v) { this.type = v; }
    public String getName() { return name; }
    public void setName(String v) { this.name = v; }
    public String getLender() { return lender; }
    public void setLender(String v) { this.lender = v; }
    public BigDecimal getPrincipal() { return principal; }
    public void setPrincipal(BigDecimal v) { this.principal = v; }
    public BigDecimal getOutstanding() { return outstanding; }
    public void setOutstanding(BigDecimal v) { this.outstanding = v; }
    public BigDecimal getInterestRate() { return interestRate; }
    public void setInterestRate(BigDecimal v) { this.interestRate = v; }
    public BigDecimal getEmi() { return emi; }
    public void setEmi(BigDecimal v) { this.emi = v; }
    public LocalDate getStartDate() { return startDate; }
    public void setStartDate(LocalDate v) { this.startDate = v; }
    public LocalDate getEndDate() { return endDate; }
    public void setEndDate(LocalDate v) { this.endDate = v; }
    public LocalDate getAsOf() { return asOf; }
    public void setAsOf(LocalDate v) { this.asOf = v; }
    public String getNotes() { return notes; }
    public void setNotes(String v) { this.notes = v; }
    public boolean isArchived() { return archived; }
    public void setArchived(boolean v) { this.archived = v; }
    public long getVersion() { return version; }
}
