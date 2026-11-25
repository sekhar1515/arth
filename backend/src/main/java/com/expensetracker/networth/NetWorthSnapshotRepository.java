package com.expensetracker.networth;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NetWorthSnapshotRepository extends JpaRepository<NetWorthSnapshot, UUID> {
    Optional<NetWorthSnapshot> findByUserIdAndSnapshotDate(UUID userId, LocalDate date);

    Optional<NetWorthSnapshot> findFirstByUserIdOrderBySnapshotDateAsc(UUID userId);

    List<NetWorthSnapshot> findByUserIdAndSnapshotDateGreaterThanEqualOrderBySnapshotDateAsc(UUID userId, LocalDate from);

    Optional<NetWorthSnapshot> findFirstByUserIdAndSnapshotDateLessThanEqualOrderBySnapshotDateDesc(UUID userId, LocalDate onOrBefore);

    /**
     * Saves the day's point in one statement. A read-then-insert raced with the background snapshot run (started
     * when new prices load), so two saves for the same day collided and the request that caused it failed.
     */
    @Modifying
    @org.springframework.transaction.annotation.Transactional // also called from the background run, outside a request
    @Query(nativeQuery = true, value = """
            INSERT INTO net_worth_snapshots (id, user_id, snapshot_date, assets, liabilities, net_worth, breakdown, created_at)
            VALUES (:id, :userId, :date, :assets, :liabilities, :netWorth, :breakdown, now())
            ON CONFLICT (user_id, snapshot_date) DO UPDATE
               SET assets = EXCLUDED.assets, liabilities = EXCLUDED.liabilities, net_worth = EXCLUDED.net_worth, breakdown = EXCLUDED.breakdown
            """)
    void upsert(@Param("id") UUID id, @Param("userId") UUID userId, @Param("date") LocalDate date, @Param("assets") BigDecimal assets,
                @Param("liabilities") BigDecimal liabilities, @Param("netWorth") BigDecimal netWorth, @Param("breakdown") String breakdown);
}
