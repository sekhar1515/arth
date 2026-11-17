package com.expensetracker.account;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FinancialAccountRepository extends JpaRepository<FinancialAccount, UUID> {
    List<FinancialAccount> findByUserIdOrderByArchivedAscCreatedAtAsc(UUID userId);

    Optional<FinancialAccount> findByIdAndUserId(UUID id, UUID userId);

    List<FinancialAccount> findByUserIdAndLast4AndArchivedFalse(UUID userId, String last4);
}
