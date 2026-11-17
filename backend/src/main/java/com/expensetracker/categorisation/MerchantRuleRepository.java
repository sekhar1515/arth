package com.expensetracker.categorisation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MerchantRuleRepository extends JpaRepository<MerchantRule, UUID> {
    Optional<MerchantRule> findByUserIdAndMatchTypeAndMerchantId(UUID userId, RuleMatchType type, UUID merchantId);

    List<MerchantRule> findByUserIdAndMatchType(UUID userId, RuleMatchType type);

    List<MerchantRule> findByUserIdOrderByCreatedAtDesc(UUID userId);

    Optional<MerchantRule> findByIdAndUserId(UUID id, UUID userId);
}
