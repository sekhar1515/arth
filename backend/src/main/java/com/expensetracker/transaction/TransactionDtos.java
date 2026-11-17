package com.expensetracker.transaction;

import com.expensetracker.account.AccountType;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

public final class TransactionDtos {
    private TransactionDtos() {
    }

    public record AccountRef(UUID id, String name, AccountType type) {
    }

    public record MerchantRef(UUID id, String name) {
    }

    public record CategoryRef(UUID id, String name, String path, String color, String icon) {
    }

    public record SplitDto(UUID id, CategoryRef category, BigDecimal amount, String note) {
    }

    public record TransactionDto(
            UUID id, Instant txnAt, boolean timeKnown, BigDecimal amount, String currency, Direction direction, TxnKind kind,
            TxnStatus status, AccountRef account, MerchantRef merchant, String counterparty, String description,
            String referenceNumber, PaymentMethod paymentMethod, String paymentProvider, CategoryRef category,
            CategorisationStatus categorisationStatus, CategoryRef suggestedCategory, BigDecimal suggestionConfidence,
            String suggestionReason, DuplicateStatus duplicateStatus, UUID duplicateOfId, UUID transferGroupId,
            UUID linkedTransactionId, String notes, boolean business, List<String> tags, List<SplitDto> splits,
            List<SourceType> sources, boolean needsReview, long version) {
    }

    public record SourceDto(UUID id, SourceType type, String externalId, Instant importedAt, String rawPayload) {
    }

    public record HistoryDto(Instant at, String action, String actor, CategoryRef from, CategoryRef to, String detail) {
    }

    public record TransactionDetailDto(TransactionDto transaction, List<SourceDto> sources, List<HistoryDto> history) {
    }

    public record ManualTransactionRequest(UUID accountId, Direction direction, BigDecimal amount, LocalDate date,
                                           LocalTime time, String merchant, String description, PaymentMethod paymentMethod,
                                           String paymentProvider, UUID categoryId, String notes, List<String> tags,
                                           Boolean business) {
    }

    public record UpdateTransactionRequest(LocalDate date, LocalTime time, BigDecimal amount, UUID accountId, String merchant,
                                           String description, PaymentMethod paymentMethod, String paymentProvider,
                                           TxnStatus status, String notes, List<String> tags, Boolean business, Long version) {
    }

    public record TransactionFilter(LocalDate from, LocalDate to, UUID accountId, UUID categoryId, UUID merchantId, String q,
                                    BigDecimal minAmount, BigDecimal maxAmount, Direction direction, TxnKind kind,
                                    CategorisationStatus categorisationStatus, PaymentMethod paymentMethod, String provider,
                                    TxnStatus status, Boolean needsReview, Boolean uncategorised) {
    }
}
