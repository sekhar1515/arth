package com.expensetracker.merchant;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MerchantRepository extends JpaRepository<Merchant, UUID> {
    Optional<Merchant> findByUserIdAndNormalizedName(UUID userId, String normalizedName);

    Optional<Merchant> findByIdAndUserId(UUID id, UUID userId);

    List<Merchant> findByUserIdAndIdIn(UUID userId, Collection<UUID> ids);

    List<Merchant> findTop20ByUserIdAndNameContainingIgnoreCaseOrderByName(UUID userId, String q);
}
