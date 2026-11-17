package com.expensetracker.transaction;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface TransactionSplitRepository extends JpaRepository<TransactionSplit, UUID> {
    List<TransactionSplit> findByTransactionId(UUID transactionId);

    List<TransactionSplit> findByTransactionIdIn(Collection<UUID> transactionIds);

    @Modifying
    @Query("delete from TransactionSplit s where s.transactionId = :txnId")
    void deleteByTransactionId(@Param("txnId") UUID transactionId);
}
