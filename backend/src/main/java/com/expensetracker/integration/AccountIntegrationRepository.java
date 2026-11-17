package com.expensetracker.integration;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AccountIntegrationRepository extends JpaRepository<AccountIntegration, UUID> {
    List<AccountIntegration> findByUserIdOrderByCreatedAtDesc(UUID userId);

    Optional<AccountIntegration> findByIdAndUserId(UUID id, UUID userId);

    Optional<AccountIntegration> findByTokenHashAndStatus(String tokenHash, AccountIntegration.Status status);

    Optional<AccountIntegration> findFirstByUserIdAndProviderAndStatusNot(UUID userId, String provider, AccountIntegration.Status status);
}
