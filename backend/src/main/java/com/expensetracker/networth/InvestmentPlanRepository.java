package com.expensetracker.networth;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InvestmentPlanRepository extends JpaRepository<InvestmentPlan, UUID> {
    List<InvestmentPlan> findByUserIdOrderByCreatedAt(UUID userId);

    Optional<InvestmentPlan> findByIdAndUserId(UUID id, UUID userId);

    List<InvestmentPlan> findByActiveTrue();

    List<InvestmentPlan> findByUserIdAndActiveTrue(UUID userId);
}
