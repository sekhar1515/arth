package com.expensetracker.budget;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BudgetRepository extends JpaRepository<Budget, UUID> {
    List<Budget> findByUserIdOrderByArchivedAscCreatedAtAsc(UUID userId);

    List<Budget> findByUserIdAndArchivedFalse(UUID userId);

    Optional<Budget> findByIdAndUserId(UUID id, UUID userId);
}
