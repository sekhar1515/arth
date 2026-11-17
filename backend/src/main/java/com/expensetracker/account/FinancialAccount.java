package com.expensetracker.account;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "financial_accounts")
public class FinancialAccount {
    @Id
    private UUID id;
    @Column(name = "user_id", nullable = false)
    private UUID userId;
    @Column(nullable = false)
    private String name;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AccountType type;
    private String institution;
    private String last4;
    @Column(name = "opening_balance", nullable = false, precision = 19, scale = 2)
    private BigDecimal openingBalance = BigDecimal.ZERO;
    @Column(name = "opening_date")
    private LocalDate openingDate;
    @Column(nullable = false, length = 3)
    private String currency = "INR";
    @Column(nullable = false)
    private boolean archived;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected FinancialAccount() {
    }

    public FinancialAccount(UUID userId, String name, AccountType type) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.name = name;
        this.type = type;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public AccountType getType() { return type; }
    public void setType(AccountType type) { this.type = type; }
    public String getInstitution() { return institution; }
    public void setInstitution(String institution) { this.institution = institution; }
    public String getLast4() { return last4; }
    public void setLast4(String last4) { this.last4 = last4; }
    public BigDecimal getOpeningBalance() { return openingBalance; }
    public void setOpeningBalance(BigDecimal openingBalance) { this.openingBalance = openingBalance; }
    public LocalDate getOpeningDate() { return openingDate; }
    public void setOpeningDate(LocalDate openingDate) { this.openingDate = openingDate; }
    public String getCurrency() { return currency; }
    public boolean isArchived() { return archived; }
    public void setArchived(boolean archived) { this.archived = archived; }
    public Instant getCreatedAt() { return createdAt; }
}
