package com.expensetracker.ingestion;

import com.expensetracker.transaction.Direction;
import com.expensetracker.transaction.PaymentMethod;
import com.expensetracker.transaction.SourceType;
import com.expensetracker.transaction.TxnStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Provider-neutral transaction handed to {@link IngestionService}. Every source (manual entry, CSV, SMS,
 * e-mail, demo, future Account Aggregator) produces one of these, so the core engine never depends on a provider.
 */
public record RawTransaction(
        UUID accountId,
        Direction direction,
        BigDecimal amount,
        Instant txnAt,
        boolean timeKnown,
        String counterparty,
        String description,
        String reference,
        PaymentMethod method,
        String paymentProvider,
        TxnStatus status,
        SourceType sourceType,
        String externalId,
        UUID integrationId,
        String rawPayload,
        UUID presetCategoryId,
        String notes,
        UUID syncId) {

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private UUID accountId;
        private Direction direction;
        private BigDecimal amount;
        private Instant txnAt;
        private boolean timeKnown = true;
        private String counterparty;
        private String description;
        private String reference;
        private PaymentMethod method = PaymentMethod.OTHER;
        private String paymentProvider;
        private TxnStatus status = TxnStatus.POSTED;
        private SourceType sourceType;
        private String externalId;
        private UUID integrationId;
        private String rawPayload;
        private UUID presetCategoryId;
        private String notes;
        private UUID syncId;

        public Builder account(UUID v) { accountId = v; return this; }
        public Builder direction(Direction v) { direction = v; return this; }
        public Builder amount(BigDecimal v) { amount = v; return this; }
        public Builder txnAt(Instant v) { txnAt = v; return this; }
        public Builder timeKnown(boolean v) { timeKnown = v; return this; }
        public Builder counterparty(String v) { counterparty = v; return this; }
        public Builder description(String v) { description = v; return this; }
        public Builder reference(String v) { reference = v; return this; }
        public Builder method(PaymentMethod v) { method = v == null ? PaymentMethod.OTHER : v; return this; }
        public Builder provider(String v) { paymentProvider = v; return this; }
        public Builder status(TxnStatus v) { status = v == null ? TxnStatus.POSTED : v; return this; }
        public Builder source(SourceType v, String externalId) { sourceType = v; this.externalId = externalId; return this; }
        public Builder integration(UUID v) { integrationId = v; return this; }
        public Builder raw(String v) { rawPayload = v; return this; }
        public Builder category(UUID v) { presetCategoryId = v; return this; }
        public Builder notes(String v) { notes = v; return this; }
        /** The import run this row belongs to, so the whole import can be undone. */
        public Builder sync(UUID v) { syncId = v; return this; }

        public RawTransaction build() {
            return new RawTransaction(accountId, direction, amount, txnAt, timeKnown, counterparty, description, reference,
                    method, paymentProvider, status, sourceType, externalId, integrationId, rawPayload, presetCategoryId, notes, syncId);
        }
    }
}
