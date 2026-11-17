package com.expensetracker.transaction;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface TransactionSourceRepository extends JpaRepository<TransactionSource, UUID> {
    boolean existsByUserIdAndSourceTypeAndExternalId(UUID userId, SourceType type, String externalId);

    java.util.Optional<TransactionSource> findByUserIdAndSourceTypeAndExternalId(UUID userId, SourceType type, String externalId);

    List<TransactionSource> findByTransactionIdOrderByImportedAt(UUID transactionId);

    List<TransactionSource> findByTransactionIdIn(Collection<UUID> transactionIds);

    List<TransactionSource> findByUserIdAndSyncId(UUID userId, UUID syncId);
}
