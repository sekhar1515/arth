package com.expensetracker.networth;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LiabilityRepository extends JpaRepository<Liability, UUID> {
    List<Liability> findByUserIdOrderByArchivedAscOutstandingDesc(UUID userId);

    List<Liability> findByUserIdAndArchivedFalse(UUID userId);

    Optional<Liability> findByIdAndUserId(UUID id, UUID userId);
}
