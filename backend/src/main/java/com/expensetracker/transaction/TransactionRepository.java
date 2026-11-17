package com.expensetracker.transaction;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TransactionRepository extends JpaRepository<Transaction, UUID>, JpaSpecificationExecutor<Transaction> {

    Optional<Transaction> findByIdAndUserId(UUID id, UUID userId);

    long countByUserIdAndAccountId(UUID userId, UUID accountId);

    @org.springframework.data.jpa.repository.Query("select t.id from Transaction t where t.userId = :userId and t.accountId = :accountId")
    List<UUID> findIdsByAccount(@org.springframework.data.repository.query.Param("userId") UUID userId,
                                @org.springframework.data.repository.query.Param("accountId") UUID accountId);

    List<Transaction> findByUserIdAndIdIn(UUID userId, Collection<UUID> ids);

    /** Dedup candidates: same user, direction and amount within a time window. */
    List<Transaction> findByUserIdAndDirectionAndAmountAndTxnAtBetween(UUID userId, Direction direction, BigDecimal amount,
                                                                        Instant from, Instant to);

    /** Dedup candidates: same reference number (any date), amount checked by the matcher. */
    List<Transaction> findByUserIdAndReferenceNumber(UUID userId, String referenceNumber);

    /** Transactions whose reference number ends with the given digits (statements often zero-pad references). */
    @Query("select t from Transaction t where t.userId = :userId and t.amount = :amount and t.referenceNumber like concat('%', :suffix)")
    List<Transaction> findByReferenceSuffix(@Param("userId") UUID userId, @Param("amount") BigDecimal amount, @Param("suffix") String suffix);

    @Query("""
            select t from Transaction t
            where t.userId = :userId and t.accountId = :accountId and t.direction = :direction and t.amount = :amount
              and t.txnAt between :from and :to and t.id <> :excludeId and t.transferGroupId is null
            order by t.txnAt
            """)
    List<Transaction> findTransferCounterparts(@Param("userId") UUID userId, @Param("accountId") UUID accountId,
                                               @Param("direction") Direction direction, @Param("amount") BigDecimal amount,
                                               @Param("from") Instant from, @Param("to") Instant to,
                                               @Param("excludeId") UUID excludeId);

    /** The user's confirmed category choices for a merchant, most frequent first (JPQL so pending changes are flushed first). */
    @Query("""
            select t.categoryId, count(t) from Transaction t
            where t.userId = :userId and t.merchantId = :merchantId and t.direction = :direction
              and t.categorisationStatus = com.expensetracker.transaction.CategorisationStatus.CONFIRMED and t.categoryId is not null
            group by t.categoryId
            order by count(t) desc
            """)
    List<Object[]> confirmedCategoryCounts(@Param("userId") UUID userId, @Param("merchantId") UUID merchantId,
                                           @Param("direction") Direction direction);

    /** The user's confirmed choices for transactions carrying the same note, most frequent first. */
    @Query("""
            select t.categoryId, count(t) from Transaction t
            where t.userId = :userId and t.noteKey = :noteKey and t.direction = :direction
              and t.categorisationStatus = com.expensetracker.transaction.CategorisationStatus.CONFIRMED and t.categoryId is not null
            group by t.categoryId
            order by count(t) desc
            """)
    List<Object[]> confirmedCategoryCountsForNote(@Param("userId") UUID userId, @Param("noteKey") String noteKey,
                                                  @Param("direction") Direction direction);

    List<Transaction> findByUserIdAndMerchantIdAndCategorisationStatus(UUID userId, UUID merchantId, CategorisationStatus status);

    List<Transaction> findByUserIdAndMerchantId(UUID userId, UUID merchantId);

    List<Transaction> findByUserIdAndCategorisationStatus(UUID userId, CategorisationStatus status);

    List<Transaction> findByTransferGroupId(UUID transferGroupId);

    @Query("""
            select count(t) from Transaction t where t.userId = :userId
              and ((t.categorisationStatus = com.expensetracker.transaction.CategorisationStatus.PENDING
                    and t.kind in (com.expensetracker.transaction.TxnKind.EXPENSE, com.expensetracker.transaction.TxnKind.INCOME)
                    and t.status <> com.expensetracker.transaction.TxnStatus.FAILED)
                   or t.duplicateStatus = com.expensetracker.transaction.DuplicateStatus.SUSPECTED)
            """)
    long countNeedsReview(@Param("userId") UUID userId);
}
