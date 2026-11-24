package com.expensetracker.networth;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface HoldingRepository extends JpaRepository<Holding, UUID> {
    List<Holding> findByUserIdOrderByAssetClassAscNameAsc(UUID userId);

    List<Holding> findByUserIdAndArchivedFalse(UUID userId);

    List<Holding> findByUserIdAndOwnerAndArchivedFalse(UUID userId, HoldingOwner owner);

    Optional<Holding> findByIdAndUserId(UUID id, UUID userId);

    List<Holding> findByArchivedFalseAndSchemeCodeIn(Collection<String> schemeCodes);

    List<Holding> findByArchivedFalseAndAssetClassAndSchemeCodeIsNullAndIsinIsNotNull(AssetClass assetClass);
}
