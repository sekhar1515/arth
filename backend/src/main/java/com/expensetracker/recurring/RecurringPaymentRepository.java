package com.expensetracker.recurring;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RecurringPaymentRepository extends JpaRepository<RecurringPayment, UUID> {
    List<RecurringPayment> findByUserIdOrderByNextDueAsc(UUID userId);

    List<RecurringPayment> findByUserIdAndStatus(UUID userId, RecurringPayment.Status status);

    Optional<RecurringPayment> findByIdAndUserId(UUID id, UUID userId);

    Optional<RecurringPayment> findByUserIdAndMatchKeyAndDirection(UUID userId, String matchKey, String direction);
}
