package com.expensetracker.categorisation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CategorisationHistoryRepository extends JpaRepository<CategorisationHistory, UUID> {
    List<CategorisationHistory> findByTransactionIdOrderByCreatedAtAsc(UUID transactionId);
}
