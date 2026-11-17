package com.expensetracker.category;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CategoryRepository extends JpaRepository<Category, UUID> {
    List<Category> findByUserIdOrderBySortOrderAscNameAsc(UUID userId);

    Optional<Category> findByIdAndUserId(UUID id, UUID userId);

    List<Category> findByUserIdAndParentId(UUID userId, UUID parentId);

    @Modifying
    @Query(value = "UPDATE transactions SET category_id = :target, updated_at = now() WHERE user_id = :userId AND category_id = :source", nativeQuery = true)
    int reassignTransactions(@Param("userId") UUID userId, @Param("source") UUID source, @Param("target") UUID target);

    @Modifying
    @Query(value = "UPDATE transactions SET suggested_category_id = :target WHERE user_id = :userId AND suggested_category_id = :source", nativeQuery = true)
    int reassignSuggestions(@Param("userId") UUID userId, @Param("source") UUID source, @Param("target") UUID target);

    @Modifying
    @Query(value = "UPDATE transaction_splits SET category_id = :target WHERE category_id = :source AND transaction_id IN (SELECT id FROM transactions WHERE user_id = :userId)", nativeQuery = true)
    int reassignSplits(@Param("userId") UUID userId, @Param("source") UUID source, @Param("target") UUID target);

    @Modifying
    @Query(value = "UPDATE merchant_rules SET category_id = :target WHERE user_id = :userId AND category_id = :source", nativeQuery = true)
    int reassignRules(@Param("userId") UUID userId, @Param("source") UUID source, @Param("target") UUID target);

    // ---- hard delete support (ids = the category and its subcategories)

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "UPDATE transactions SET category_id = NULL, categorisation_status = 'PENDING', updated_at = now(), version = version + 1 "
            + "WHERE user_id = :userId AND id IN (SELECT transaction_id FROM transaction_splits WHERE category_id IN (:ids))", nativeQuery = true)
    int resetSplitTransactions(@Param("userId") UUID userId, @Param("ids") java.util.Collection<UUID> ids);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "DELETE FROM transaction_splits WHERE transaction_id IN (SELECT transaction_id FROM transaction_splits s JOIN transactions t ON t.id = s.transaction_id "
            + "WHERE t.user_id = :userId AND s.category_id IN (:ids))", nativeQuery = true)
    int deleteSplitsTouching(@Param("userId") UUID userId, @Param("ids") java.util.Collection<UUID> ids);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "UPDATE transactions SET category_id = NULL, categorisation_status = 'PENDING', updated_at = now(), version = version + 1 "
            + "WHERE user_id = :userId AND category_id IN (:ids)", nativeQuery = true)
    int resetTransactions(@Param("userId") UUID userId, @Param("ids") java.util.Collection<UUID> ids);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "DELETE FROM categories WHERE user_id = :userId AND parent_id = :parentId", nativeQuery = true)
    int deleteChildren(@Param("userId") UUID userId, @Param("parentId") UUID parentId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "DELETE FROM categories WHERE user_id = :userId AND id = :id", nativeQuery = true)
    int deleteOne(@Param("userId") UUID userId, @Param("id") UUID id);
}
