package com.expensetracker.merchant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class MerchantService {

    private final MerchantRepository repository;

    public MerchantService(MerchantRepository repository) {
        this.repository = repository;
    }

    /** Finds or creates the merchant for a raw counterparty string. Returns empty when nothing usable remains. */
    @Transactional
    public Optional<Merchant> resolve(UUID userId, String rawCounterparty) {
        MerchantNormaliser.Normalised n = MerchantNormaliser.normalise(rawCounterparty);
        if (n == null || n.key().isEmpty()) return Optional.empty();
        return Optional.of(repository.findByUserIdAndNormalizedName(userId, n.key())
                .orElseGet(() -> repository.save(new Merchant(userId, n.displayName(), n.key()))));
    }

    @Transactional(readOnly = true)
    public Map<UUID, Merchant> byIds(UUID userId, Collection<UUID> ids) {
        Map<UUID, Merchant> out = new HashMap<>();
        if (ids.isEmpty()) return out;
        repository.findByUserIdAndIdIn(userId, ids).forEach(m -> out.put(m.getId(), m));
        return out;
    }

    @Transactional(readOnly = true)
    public List<Merchant> search(UUID userId, String q) {
        return repository.findTop20ByUserIdAndNameContainingIgnoreCaseOrderByName(userId, q == null ? "" : q.trim());
    }
}
