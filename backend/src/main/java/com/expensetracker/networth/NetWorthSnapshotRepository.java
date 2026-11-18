package com.expensetracker.networth;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NetWorthSnapshotRepository extends JpaRepository<NetWorthSnapshot, UUID> {
    Optional<NetWorthSnapshot> findByUserIdAndSnapshotDate(UUID userId, LocalDate date);

    Optional<NetWorthSnapshot> findFirstByUserIdOrderBySnapshotDateAsc(UUID userId);

    List<NetWorthSnapshot> findByUserIdAndSnapshotDateGreaterThanEqualOrderBySnapshotDateAsc(UUID userId, LocalDate from);

    Optional<NetWorthSnapshot> findFirstByUserIdAndSnapshotDateLessThanEqualOrderBySnapshotDateDesc(UUID userId, LocalDate onOrBefore);
}
