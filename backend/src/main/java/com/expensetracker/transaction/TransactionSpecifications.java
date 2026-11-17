package com.expensetracker.transaction;

import com.expensetracker.common.TimeConfig;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

final class TransactionSpecifications {
    private TransactionSpecifications() {
    }

    static Specification<Transaction> build(UUID userId, TransactionDtos.TransactionFilter f, Collection<UUID> categoryIds) {
        return (root, query, cb) -> {
            List<Predicate> p = new ArrayList<>();
            p.add(cb.equal(root.get("userId"), userId));
            if (f == null) return cb.and(p.toArray(new Predicate[0]));
            if (f.from() != null) p.add(cb.greaterThanOrEqualTo(root.<java.time.Instant>get("txnAt"), TimeConfig.startOfDay(f.from())));
            if (f.to() != null) p.add(cb.lessThan(root.<java.time.Instant>get("txnAt"), TimeConfig.startOfDay(f.to().plusDays(1))));
            if (f.accountId() != null) p.add(cb.equal(root.get("accountId"), f.accountId()));
            if (f.merchantId() != null) p.add(cb.equal(root.get("merchantId"), f.merchantId()));
            if (f.direction() != null) p.add(cb.equal(root.get("direction"), f.direction()));
            if (f.kind() != null) p.add(cb.equal(root.get("kind"), f.kind()));
            if (f.categorisationStatus() != null) p.add(cb.equal(root.get("categorisationStatus"), f.categorisationStatus()));
            if (f.paymentMethod() != null) p.add(cb.equal(root.get("paymentMethod"), f.paymentMethod()));
            if (f.status() != null) p.add(cb.equal(root.get("status"), f.status()));
            if (f.provider() != null && !f.provider().isBlank()) {
                p.add(cb.equal(cb.lower(root.<String>get("paymentProvider")), f.provider().toLowerCase(Locale.ROOT)));
            }
            if (f.minAmount() != null) p.add(cb.greaterThanOrEqualTo(root.<java.math.BigDecimal>get("amount"), f.minAmount()));
            if (f.maxAmount() != null) p.add(cb.lessThanOrEqualTo(root.<java.math.BigDecimal>get("amount"), f.maxAmount()));
            if (f.q() != null && !f.q().isBlank()) {
                String like = "%" + f.q().toLowerCase(Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
                Subquery<UUID> merchantMatch = query.subquery(UUID.class);
                Root<com.expensetracker.merchant.Merchant> m = merchantMatch.from(com.expensetracker.merchant.Merchant.class);
                merchantMatch.select(m.<UUID>get("id")).where(cb.equal(m.get("userId"), userId), cb.like(cb.lower(m.<String>get("name")), like, '\\'));
                p.add(cb.or(
                        cb.like(cb.lower(root.<String>get("description")), like, '\\'),
                        cb.like(cb.lower(root.<String>get("counterparty")), like, '\\'),
                        cb.like(cb.lower(root.<String>get("notes")), like, '\\'),
                        cb.like(cb.lower(root.<String>get("referenceNumber")), like, '\\'),
                        root.get("merchantId").in(merchantMatch)));
            }
            if (categoryIds != null && !categoryIds.isEmpty()) {
                Subquery<UUID> splitMatch = query.subquery(UUID.class);
                Root<TransactionSplit> s = splitMatch.from(TransactionSplit.class);
                splitMatch.select(s.<UUID>get("transactionId")).where(s.get("categoryId").in(categoryIds));
                p.add(cb.or(root.get("categoryId").in(categoryIds), root.get("id").in(splitMatch)));
            }
            if (Boolean.TRUE.equals(f.uncategorised())) {
                p.add(cb.equal(root.get("categorisationStatus"), CategorisationStatus.PENDING));
            }
            if (Boolean.TRUE.equals(f.needsReview())) {
                p.add(cb.or(
                        cb.equal(root.get("duplicateStatus"), DuplicateStatus.SUSPECTED),
                        cb.and(cb.equal(root.get("categorisationStatus"), CategorisationStatus.PENDING),
                                root.get("kind").in(TxnKind.EXPENSE, TxnKind.INCOME),
                                cb.notEqual(root.get("status"), TxnStatus.FAILED))));
            }
            return cb.and(p.toArray(new Predicate[0]));
        };
    }
}
