package com.expensetracker.integration;

import com.expensetracker.account.AccountService;
import com.expensetracker.audit.AuditService;
import com.expensetracker.common.ApiException;
import com.expensetracker.common.Hashing;
import com.expensetracker.integration.provider.IntegrationProvider;
import com.expensetracker.integration.provider.ProviderDescriptor;
import com.expensetracker.integration.provider.Providers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class IntegrationService {

    public record ConnectionDto(UUID id, String status, UUID defaultAccountId, String tokenHint, Instant connectedAt,
                                Instant lastSyncAt, Instant nextSyncAt, String lastError, List<String> permissions) {
    }

    public record SyncDto(UUID id, String sourceType, Instant startedAt, Instant finishedAt, String status, int imported,
                          int merged, int suspected, int skipped, String error) {
        static SyncDto of(SyncHistory s) {
            return new SyncDto(s.getId(), s.getSourceType(), s.getStartedAt(), s.getFinishedAt(), s.getStatus().name(),
                    s.getImported(), s.getMerged(), s.getSuspected(), s.getSkipped(), s.getError());
        }
    }

    public record CatalogueEntry(ProviderDescriptor provider, ConnectionDto connection, SyncDto lastSync) {
    }

    /** Returned once when a token-based integration is connected or its token rotated; the token is never stored in plain text. */
    public record ConnectResult(ConnectionDto connection, String ingestToken, String ingestPath) {
    }

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String INGEST_SCOPE = "ingest:transaction-alerts";

    private final Map<String, IntegrationProvider> providers = new LinkedHashMap<>();
    private final AccountIntegrationRepository integrations;
    private final IntegrationConsentRepository consents;
    private final SyncHistoryRepository syncs;
    private final AccountService accounts;
    private final AuditService audit;

    public IntegrationService(List<IntegrationProvider> providerBeans, AccountIntegrationRepository integrations,
                              IntegrationConsentRepository consents, SyncHistoryRepository syncs, AccountService accounts,
                              AuditService audit) {
        providerBeans.stream()
                .sorted(Comparator.comparing((IntegrationProvider p) -> p.descriptor().availability()).thenComparing(IntegrationProvider::key))
                .forEach(p -> providers.put(p.key(), p));
        this.integrations = integrations;
        this.consents = consents;
        this.syncs = syncs;
        this.accounts = accounts;
        this.audit = audit;
    }

    public IntegrationProvider provider(String key) {
        IntegrationProvider p = providers.get(key);
        if (p == null) throw ApiException.notFound("Integration provider " + key);
        return p;
    }

    @Transactional(readOnly = true)
    public List<CatalogueEntry> catalogue(UUID userId) {
        List<AccountIntegration> mine = integrations.findByUserIdOrderByCreatedAtDesc(userId);
        return providers.values().stream().map(p -> {
            ProviderDescriptor d = p.descriptor();
            Optional<AccountIntegration> conn = mine.stream()
                    .filter(i -> i.getProvider().equals(d.key()) && i.getStatus() != AccountIntegration.Status.DISCONNECTED)
                    .findFirst();
            SyncDto last = null;
            if (conn.isPresent()) {
                last = syncs.findTop20ByUserIdAndIntegrationIdOrderByStartedAtDesc(userId, conn.get().getId()).stream()
                        .findFirst().map(SyncDto::of).orElse(null);
            } else if (p.sourceType() != null && d.connectionMode() == ProviderDescriptor.ConnectionMode.FILE_UPLOAD) {
                last = syncs.findFirstByUserIdAndSourceTypeOrderByStartedAtDesc(userId, p.sourceType().name()).map(SyncDto::of).orElse(null);
            }
            return new CatalogueEntry(d, conn.map(this::toDto).orElse(null), last);
        }).toList();
    }

    @Transactional
    public ConnectResult connect(UUID userId, String providerKey, UUID defaultAccountId) {
        IntegrationProvider p = provider(providerKey);
        ProviderDescriptor d = p.descriptor();
        if (d.availability() != ProviderDescriptor.Availability.AVAILABLE || d.connectionMode() != ProviderDescriptor.ConnectionMode.WEBHOOK_TOKEN) {
            throw ApiException.badRequest(d.name() + " cannot be connected: " + (d.limitation() == null ? "not supported" : d.limitation()));
        }
        if (defaultAccountId != null) accounts.require(userId, defaultAccountId);
        if (integrations.findFirstByUserIdAndProviderAndStatusNot(userId, providerKey, AccountIntegration.Status.DISCONNECTED).isPresent()) {
            throw ApiException.conflict(d.name() + " is already connected. Rotate its token or disconnect it first.");
        }
        AccountIntegration i = new AccountIntegration(userId, providerKey);
        i.setDefaultAccountId(defaultAccountId);
        String token = newToken();
        i.setTokenHash(Hashing.sha256Hex(token));
        i.setTokenHint(token.substring(token.length() - 4));
        integrations.save(i);
        consents.save(new IntegrationConsent(i.getId(), INGEST_SCOPE));
        audit.record(userId, "INTEGRATION_CONNECTED", "account_integration", i.getId(), providerKey);
        return new ConnectResult(toDto(i), token, ingestPath(providerKey));
    }

    @Transactional
    public ConnectResult rotateToken(UUID userId, UUID integrationId) {
        AccountIntegration i = require(userId, integrationId);
        if (i.getStatus() == AccountIntegration.Status.DISCONNECTED) throw ApiException.badRequest("Integration is disconnected");
        String token = newToken();
        i.setTokenHash(Hashing.sha256Hex(token));
        i.setTokenHint(token.substring(token.length() - 4));
        audit.record(userId, "INTEGRATION_TOKEN_ROTATED", "account_integration", i.getId(), i.getProvider());
        return new ConnectResult(toDto(i), token, ingestPath(i.getProvider()));
    }

    @Transactional
    public ConnectionDto update(UUID userId, UUID integrationId, UUID defaultAccountId) {
        AccountIntegration i = require(userId, integrationId);
        if (defaultAccountId != null) accounts.require(userId, defaultAccountId);
        i.setDefaultAccountId(defaultAccountId);
        return toDto(i);
    }

    @Transactional
    public void disconnect(UUID userId, UUID integrationId) {
        AccountIntegration i = require(userId, integrationId);
        i.setStatus(AccountIntegration.Status.DISCONNECTED);
        i.setTokenHash(null);
        consents.findByIntegrationIdOrderByGrantedAtDesc(i.getId()).stream()
                .filter(c -> c.getRevokedAt() == null).forEach(IntegrationConsent::revoke);
        audit.record(userId, "INTEGRATION_DISCONNECTED", "account_integration", i.getId(), i.getProvider());
    }

    @Transactional(readOnly = true)
    public List<SyncDto> history(UUID userId, UUID integrationId) {
        if (integrationId == null) return syncs.findTop20ByUserIdOrderByStartedAtDesc(userId).stream().map(SyncDto::of).toList();
        require(userId, integrationId);
        return syncs.findTop20ByUserIdAndIntegrationIdOrderByStartedAtDesc(userId, integrationId).stream().map(SyncDto::of).toList();
    }

    /** Resolves an ingest token to its integration. Constant-time not needed: we compare SHA-256 hashes via an index lookup. */
    @Transactional(readOnly = true)
    public Optional<AccountIntegration> byToken(String token) {
        if (token == null || token.length() < 20 || token.length() > 200) return Optional.empty();
        return integrations.findByTokenHashAndStatus(Hashing.sha256Hex(token), AccountIntegration.Status.CONNECTED);
    }

    private AccountIntegration require(UUID userId, UUID id) {
        return integrations.findByIdAndUserId(id, userId).orElseThrow(() -> ApiException.notFound("Integration"));
    }

    private ConnectionDto toDto(AccountIntegration i) {
        List<String> perms = consents.findByIntegrationIdOrderByGrantedAtDesc(i.getId()).stream()
                .filter(c -> c.getRevokedAt() == null).map(IntegrationConsent::getScope).toList();
        return new ConnectionDto(i.getId(), i.getStatus().name(), i.getDefaultAccountId(), i.getTokenHint(), i.getCreatedAt(),
                i.getLastSyncAt(), i.getNextSyncAt(), i.getLastError(), perms);
    }

    private static String ingestPath(String providerKey) {
        return Providers.SMS_FORWARDER.equals(providerKey) ? "/api/ingest/sms" : "/api/ingest/email";
    }

    private static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return "arth_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
