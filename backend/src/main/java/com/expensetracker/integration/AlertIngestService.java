package com.expensetracker.integration;

import com.expensetracker.account.AccountService;
import com.expensetracker.account.FinancialAccount;
import com.expensetracker.common.ApiException;
import com.expensetracker.common.Hashing;
import com.expensetracker.common.TimeConfig;
import com.expensetracker.ingestion.IngestOutcome;
import com.expensetracker.ingestion.IngestionService;
import com.expensetracker.ingestion.RawTransaction;
import com.expensetracker.integration.parsing.AlertParser;
import com.expensetracker.transaction.SourceType;
import com.expensetracker.transaction.TxnStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.UUID;

/** Turns a forwarded bank SMS / e-mail alert into a transaction. */
@Service
public class AlertIngestService {

    public record AlertResult(String outcome, UUID transactionId, String message) {
    }

    private final AlertParserFacade parser = new AlertParserFacade();
    private final IngestionService ingestion;
    private final AccountService accounts;
    private final SyncHistoryRepository syncs;
    private final AccountIntegrationRepository integrations;
    private final Clock clock;

    public AlertIngestService(IngestionService ingestion, AccountService accounts, SyncHistoryRepository syncs,
                              AccountIntegrationRepository integrations, Clock clock) {
        this.ingestion = ingestion;
        this.accounts = accounts;
        this.syncs = syncs;
        this.integrations = integrations;
        this.clock = clock;
    }

    @Transactional(noRollbackFor = ApiException.class)
    public AlertResult ingest(AccountIntegration integration, SourceType sourceType, String text, Instant receivedAt) {
        UUID userId = integration.getUserId();
        AccountIntegration managed = integrations.findById(integration.getId()).orElseThrow();
        SyncHistory sync = syncs.save(new SyncHistory(userId, managed.getId(), sourceType.name()));
        managed.setLastSyncAt(clock.instant());
        AlertParser.Result result = parser.parse(text);
        if (result instanceof AlertParser.Rejected rejected) {
            sync.finish(SyncHistory.Status.SUCCESS, 0, 0, 0, 1, "ignored: " + rejected.reason());
            return new AlertResult("IGNORED", null, rejected.reason());
        }
        AlertParser.ParsedAlert alert = ((AlertParser.Parsed) result).alert();
        FinancialAccount account = accounts.findByLast4(userId, alert.accountLast4())
                .or(() -> managed.getDefaultAccountId() == null ? java.util.Optional.empty()
                        : java.util.Optional.of(accounts.require(userId, managed.getDefaultAccountId())))
                .orElse(null);
        if (account == null) {
            String msg = alert.accountLast4() == null
                    ? "Alert has no account digits and this integration has no default account"
                    : "No account ending in " + alert.accountLast4() + ". Add the account (with its last digits) in Accounts.";
            sync.finish(SyncHistory.Status.FAILED, 0, 0, 0, 0, msg);
            managed.setLastError(msg);
            managed.setStatus(AccountIntegration.Status.CONNECTED);
            throw ApiException.unprocessable(msg);
        }
        Instant received = receivedAt != null ? receivedAt : clock.instant();
        LocalDateTime local = AlertParser.timestamp(alert, LocalDateTime.ofInstant(received, TimeConfig.IST));
        boolean timeKnown = alert.time() != null || alert.date() == null;
        String normalisedText = text.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
        RawTransaction raw = RawTransaction.builder()
                .account(account.getId()).direction(alert.direction()).amount(alert.amount())
                .txnAt(local.atZone(TimeConfig.IST).toInstant()).timeKnown(timeKnown)
                .counterparty(alert.counterparty()).description(text.length() > 500 ? text.substring(0, 500) : text)
                .reference(alert.reference()).method(alert.method()).provider(alert.paymentProvider())
                .status(alert.failed() ? TxnStatus.FAILED : TxnStatus.POSTED)
                .source(sourceType, Hashing.sha256Hex(normalisedText)).integration(managed.getId()).raw(text)
                .build();
        IngestOutcome outcome = ingestion.ingest(userId, raw);
        switch (outcome.result()) {
            case CREATED -> sync.finish(SyncHistory.Status.SUCCESS, 1, 0, 0, 0, null);
            case MERGED -> sync.finish(SyncHistory.Status.SUCCESS, 0, 1, 0, 0, null);
            case SUSPECTED_DUPLICATE -> sync.finish(SyncHistory.Status.SUCCESS, 0, 0, 1, 0, null);
            case ALREADY_IMPORTED, UPDATED, PREVIOUSLY_DELETED -> sync.finish(SyncHistory.Status.SUCCESS, 0, 0, 0, 1, null);
        }
        managed.setLastError(null);
        return new AlertResult(outcome.result().name(), outcome.transactionId(), outcome.detail());
    }

    /** Small seam so the parser can be swapped (e.g. bank-specific templates) without touching this service. */
    static final class AlertParserFacade {
        AlertParser.Result parse(String text) {
            return AlertParser.parse(text);
        }
    }
}
