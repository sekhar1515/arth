package com.expensetracker.transaction;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "transactions")
public class Transaction implements Persistable<UUID> {
    @Id
    private UUID id;
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;
    @Column(name = "account_id", nullable = false)
    private UUID accountId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Direction direction;
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;
    @Column(nullable = false, length = 3)
    private String currency = "INR";
    @Column(name = "txn_at", nullable = false)
    private Instant txnAt;
    @Column(name = "time_known", nullable = false)
    private boolean timeKnown = true;
    @Column(name = "merchant_id")
    private UUID merchantId;
    private String counterparty;
    private String description;
    @Column(name = "reference_number")
    private String referenceNumber;
    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", nullable = false)
    private PaymentMethod paymentMethod = PaymentMethod.OTHER;
    @Column(name = "payment_provider")
    private String paymentProvider;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TxnStatus status = TxnStatus.POSTED;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TxnKind kind;
    @Column(name = "category_id")
    private UUID categoryId;
    @Enumerated(EnumType.STRING)
    @Column(name = "categorisation_status", nullable = false)
    private CategorisationStatus categorisationStatus = CategorisationStatus.PENDING;
    @Column(name = "suggested_category_id")
    private UUID suggestedCategoryId;
    @Column(name = "suggestion_confidence", precision = 4, scale = 3)
    private BigDecimal suggestionConfidence;
    @Column(name = "suggestion_reason")
    private String suggestionReason;
    @Enumerated(EnumType.STRING)
    @Column(name = "duplicate_status", nullable = false)
    private DuplicateStatus duplicateStatus = DuplicateStatus.NONE;
    @Column(name = "duplicate_of_id")
    private UUID duplicateOfId;
    @Column(name = "transfer_group_id")
    private UUID transferGroupId;
    @Column(name = "linked_transaction_id")
    private UUID linkedTransactionId;
    private String notes;
    /** Normalised note, used to learn from your choices ("sabzi" → Groceries). */
    @Column(name = "note_key")
    private String noteKey;
    @Column(nullable = false)
    private boolean business;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Version
    private long version;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "transaction_tags", joinColumns = @JoinColumn(name = "transaction_id"),
            inverseJoinColumns = @JoinColumn(name = "tag_id"))
    private Set<Tag> tags = new HashSet<>();

    /** Assigned UUIDs: tell Spring Data the entity is new so save() persists (keeps this instance managed) instead of merging. */
    @Transient
    private boolean isNew = true;

    protected Transaction() {
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostPersist
    @PostLoad
    void markNotNew() {
        this.isNew = false;
    }

    public Transaction(UUID userId, UUID accountId, Direction direction, BigDecimal amount, Instant txnAt) {
        if (amount == null || amount.signum() <= 0) throw new IllegalArgumentException("amount must be positive");
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.accountId = accountId;
        this.direction = direction;
        this.amount = amount.setScale(2, java.math.RoundingMode.UNNECESSARY);
        this.txnAt = txnAt;
        this.kind = TxnKind.defaultFor(direction);
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    @PreUpdate
    void touch() {
        this.updatedAt = Instant.now();
    }

    /** Counts towards income/expense/cash-flow totals. */
    public boolean countsInTotals() {
        return status == TxnStatus.POSTED && duplicateStatus == DuplicateStatus.NONE;
    }

    public boolean needsReview() {
        return duplicateStatus == DuplicateStatus.SUSPECTED
                || (categorisationStatus == CategorisationStatus.PENDING && (kind == TxnKind.EXPENSE || kind == TxnKind.INCOME));
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public UUID getAccountId() { return accountId; }
    public void setAccountId(UUID accountId) { this.accountId = accountId; }
    public Direction getDirection() { return direction; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) throw new IllegalArgumentException("amount must be positive");
        this.amount = amount.setScale(2, java.math.RoundingMode.UNNECESSARY);
    }
    public String getCurrency() { return currency; }
    public Instant getTxnAt() { return txnAt; }
    public void setTxnAt(Instant txnAt) { this.txnAt = txnAt; }
    public boolean isTimeKnown() { return timeKnown; }
    public void setTimeKnown(boolean timeKnown) { this.timeKnown = timeKnown; }
    public UUID getMerchantId() { return merchantId; }
    public void setMerchantId(UUID merchantId) { this.merchantId = merchantId; }
    public String getCounterparty() { return counterparty; }
    public void setCounterparty(String counterparty) { this.counterparty = truncate(counterparty, 160); }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = truncate(description, 500); }
    public String getReferenceNumber() { return referenceNumber; }
    public void setReferenceNumber(String referenceNumber) { this.referenceNumber = truncate(referenceNumber, 64); }
    public PaymentMethod getPaymentMethod() { return paymentMethod; }
    public void setPaymentMethod(PaymentMethod paymentMethod) { this.paymentMethod = paymentMethod == null ? PaymentMethod.OTHER : paymentMethod; }
    public String getPaymentProvider() { return paymentProvider; }
    public void setPaymentProvider(String paymentProvider) { this.paymentProvider = truncate(paymentProvider, 30); }
    public TxnStatus getStatus() { return status; }
    public void setStatus(TxnStatus status) { this.status = status; }
    public TxnKind getKind() { return kind; }
    public void setKind(TxnKind kind) { this.kind = kind; }
    public UUID getCategoryId() { return categoryId; }
    public void setCategoryId(UUID categoryId) { this.categoryId = categoryId; }
    public CategorisationStatus getCategorisationStatus() { return categorisationStatus; }
    public void setCategorisationStatus(CategorisationStatus s) { this.categorisationStatus = s; }
    public UUID getSuggestedCategoryId() { return suggestedCategoryId; }
    public void setSuggestedCategoryId(UUID id) { this.suggestedCategoryId = id; }
    public BigDecimal getSuggestionConfidence() { return suggestionConfidence; }
    public void setSuggestionConfidence(BigDecimal c) { this.suggestionConfidence = c; }
    public String getSuggestionReason() { return suggestionReason; }
    public void setSuggestionReason(String r) { this.suggestionReason = truncate(r, 200); }
    public DuplicateStatus getDuplicateStatus() { return duplicateStatus; }
    public void setDuplicateStatus(DuplicateStatus d) { this.duplicateStatus = d; }
    public UUID getDuplicateOfId() { return duplicateOfId; }
    public void setDuplicateOfId(UUID id) { this.duplicateOfId = id; }
    public UUID getTransferGroupId() { return transferGroupId; }
    public void setTransferGroupId(UUID id) { this.transferGroupId = id; }
    public UUID getLinkedTransactionId() { return linkedTransactionId; }
    public void setLinkedTransactionId(UUID id) { this.linkedTransactionId = id; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) {
        this.notes = truncate(notes, 1000);
        this.noteKey = com.expensetracker.categorisation.NoteDictionary.coreKey(this.notes);
    }
    public String getNoteKey() { return noteKey; }
    public boolean isBusiness() { return business; }
    public void setBusiness(boolean business) { this.business = business; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getVersion() { return version; }
    public Set<Tag> getTags() { return tags; }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        String t = s.trim();
        if (t.isEmpty()) return null;
        return t.length() > max ? t.substring(0, max) : t;
    }
}
