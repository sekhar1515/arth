package com.expensetracker.integration;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface IntegrationConsentRepository extends JpaRepository<IntegrationConsent, UUID> {
    List<IntegrationConsent> findByIntegrationIdOrderByGrantedAtDesc(UUID integrationId);
}
