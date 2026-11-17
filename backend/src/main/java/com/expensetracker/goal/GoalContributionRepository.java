package com.expensetracker.goal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GoalContributionRepository extends JpaRepository<GoalContribution, UUID> {
    List<GoalContribution> findByGoalIdOrderByContributedOnDesc(UUID goalId);

    List<GoalContribution> findByUserId(UUID userId);

    Optional<GoalContribution> findByIdAndUserId(UUID id, UUID userId);
}
