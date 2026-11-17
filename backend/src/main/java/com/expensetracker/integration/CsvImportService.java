package com.expensetracker.integration;

import com.expensetracker.account.AccountService;
import com.expensetracker.audit.AuditService;
import com.expensetracker.common.ApiException;
import com.expensetracker.common.Hashing;
import com.expensetracker.common.TimeConfig;
import com.expensetracker.ingestion.IngestOutcome;
import com.expensetracker.ingestion.IngestionService;
import com.expensetracker.ingestion.RawTransaction;
import com.expensetracker.integration.parsing.CsvStatementParser;
import com.expensetracker.integration.parsing.NarrationParser;
import com.expensetracker.transaction.SourceType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class CsvImportService {

    public record ImportResult(String layout, int rows, int imported, int merged, int suspectedDuplicates, int alreadyImported, int previouslyDeleted,
                               int updatedWithNotes, int categorisedFromFile, int withNotes, int autoCategorised, int needsReview,
                               List<String> errors, List<String> warnings, UUID syncId) {
    }

    private static final int MAX_ROWS = 20_000;

    private final IngestionService ingestion;
    private final com.expensetracker.category.CategoryService categories;
    private final com.expensetracker.transaction.TransactionRepository transactions;
    private final AccountService accounts;
    private final SyncHistoryRepository syncs;
    private final AuditService audit;
    /** Keeps the persistence context small during big imports (otherwise every query dirty-checks every row so far). */
    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager em;
    static final int FLUSH_EVERY = 100;

    public CsvImportService(IngestionService ingestion, com.expensetracker.category.CategoryService categories,
                            com.expensetracker.transaction.TransactionRepository transactions, AccountService accounts,
                            SyncHistoryRepository syncs, AuditService audit) {
        this.ingestion = ingestion;
        this.categories = categories;
        this.transactions = transactions;
        this.accounts = accounts;
        this.syncs = syncs;
        this.audit = audit;
    }

    @Transactional
    public ImportResult importStatement(UUID userId, UUID accountId, String content) {
        if (accountId == null) throw ApiException.badRequest("Choose the account this statement belongs to");
        accounts.require(userId, accountId);
        CsvStatementParser.Result parsed;
        try {
            parsed = CsvStatementParser.parse(content);
        } catch (CsvStatementParser.UnrecognisedFormatException e) {
            throw ApiException.unprocessable(e.getMessage());
        }
        if (parsed.rows().size() > MAX_ROWS) throw ApiException.badRequest("Statement has more than " + MAX_ROWS + " rows; split it by period");

        SyncHistory sync = syncs.save(new SyncHistory(userId, null, SourceType.CSV_IMPORT.name()));
        int imported = 0, merged = 0, suspected = 0, already = 0, deletedBefore = 0, annotated = 0, fromFile = 0, withNotes = 0, auto = 0, review = 0;
        List<String> warnings = new ArrayList<>();
        Map<String, java.util.Optional<UUID>> categoryCache = new HashMap<>();
        Map<String, Integer> occurrences = new HashMap<>();
        UUID syncId = sync.getId();
        int processed = 0;
        for (CsvStatementParser.StatementRow row : parsed.rows()) {
            if (++processed % FLUSH_EVERY == 0) {
                em.flush();
                em.clear();
            }
            NarrationParser.ParsedNarration n = NarrationParser.parse(row.description());
            String reference = row.reference() != null ? row.reference() : n.reference();
            // your own note column wins; otherwise the note you typed in the UPI app, if the bank kept it
            String note = row.note() != null ? row.note() : n.remark();
            UUID presetCategory = null;
            if (row.category() != null) {
                presetCategory = categoryCache.computeIfAbsent(row.category().toLowerCase(java.util.Locale.ROOT),
                        k -> categories.resolveByName(userId, row.category()).map(com.expensetracker.category.Category::getId)).orElse(null);
                if (presetCategory == null) warnings.add("Row " + row.rowNumber() + ": no category called '" + row.category() + "'. Left for review.");
            }
            if (note != null) withNotes++;
            String key = String.join("|", accountId.toString(), row.date().toString(), row.direction().name(),
                    row.amount().toPlainString(), String.valueOf(row.description()), String.valueOf(row.reference()),
                    row.balance() == null ? "" : row.balance().toPlainString());
            // identical rows within one statement (two ₹20 chai payments, no balance column) are distinct transactions
            int nth = occurrences.merge(key, 1, Integer::sum);
            String externalId = Hashing.sha256Hex(key + "#" + nth);
            RawTransaction raw = RawTransaction.builder()
                    .account(accountId).direction(row.direction()).amount(row.amount())
                    .txnAt(row.date().atTime(LocalTime.NOON).atZone(TimeConfig.IST).toInstant()).timeKnown(false)
                    .counterparty(n.counterparty()).description(row.description()).reference(reference)
                    .method(n.method()).notes(note).category(presetCategory)
                    .source(SourceType.CSV_IMPORT, externalId).sync(syncId).raw("row " + row.rowNumber() + ": " + row.description())
                    .build();
            IngestOutcome outcome = ingestion.ingest(userId, raw);
            if (outcome.result() == com.expensetracker.ingestion.IngestOutcome.Result.CREATED) {
                var status = transactions.findByIdAndUserId(outcome.transactionId(), userId).map(com.expensetracker.transaction.Transaction::getCategorisationStatus).orElse(null);
                if (presetCategory != null) fromFile++;
                else if (status == com.expensetracker.transaction.CategorisationStatus.AUTO) auto++;
                else if (status == com.expensetracker.transaction.CategorisationStatus.PENDING) review++;
            }
            switch (outcome.result()) {
                case CREATED -> imported++;
                case MERGED -> merged++;
                case SUSPECTED_DUPLICATE -> suspected++;
                case ALREADY_IMPORTED -> already++;
                case UPDATED -> annotated++;
                case PREVIOUSLY_DELETED -> deletedBefore++;
            }
        }
        em.flush();
        sync = syncs.findById(syncId).orElseThrow(); // re-attach: the session was cleared during the loop
        List<String> errors = new ArrayList<>();
        parsed.errors().forEach(e -> errors.add("Row " + e.rowNumber() + ": " + e.message()));
        sync.finish(errors.isEmpty() ? SyncHistory.Status.SUCCESS : SyncHistory.Status.PARTIAL, imported, merged, suspected, already,
                errors.isEmpty() ? null : String.join("; ", errors.subList(0, Math.min(errors.size(), 10))));
        audit.record(userId, "STATEMENT_IMPORTED", "financial_account", accountId,
                imported + " new, " + merged + " merged, " + suspected + " suspected, " + already + " already imported");
        return new ImportResult(parsed.detectedLayout(), parsed.rows().size(), imported, merged, suspected, already, deletedBefore,
                annotated, fromFile, withNotes, auto, review, errors, warnings, sync.getId());
    }
}
