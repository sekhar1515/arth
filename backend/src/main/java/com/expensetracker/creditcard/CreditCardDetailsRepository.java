package com.expensetracker.creditcard;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CreditCardDetailsRepository extends JpaRepository<CreditCardDetails, UUID> {
    List<CreditCardDetails> findByUserId(UUID userId);

    Optional<CreditCardDetails> findByAccountIdAndUserId(UUID accountId, UUID userId);
}
