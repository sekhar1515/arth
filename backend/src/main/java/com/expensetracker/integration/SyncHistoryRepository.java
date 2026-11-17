package com.expensetracker.integration;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SyncHistoryRepository extends JpaRepository<SyncHistory, UUID> {
    List<SyncHistory> findTop20ByUserIdOrderByStartedAtDesc(UUID userId);

    List<SyncHistory> findTop20ByUserIdAndIntegrationIdOrderByStartedAtDesc(UUID userId, UUID integrationId);

    Optional<SyncHistory> findFirstByUserIdAndSourceTypeOrderByStartedAtDesc(UUID userId, String sourceType);
}
