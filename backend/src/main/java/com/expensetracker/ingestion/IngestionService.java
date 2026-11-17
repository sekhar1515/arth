package com.expensetracker.ingestion;

import com.expensetracker.account.AccountService;
import com.expensetracker.categorisation.CategorisationService;
import com.expensetracker.category.Category;
import com.expensetracker.category.CategoryKind;
import com.expensetracker.category.CategoryService;
import com.expensetracker.common.ApiException;
import com.expensetracker.merchant.Merchant;
import com.expensetracker.merchant.MerchantService;
import com.expensetracker.transaction.CategorisationStatus;
import com.expensetracker.transaction.Direction;
import com.expensetracker.transaction.DuplicateStatus;
import com.expensetracker.transaction.Transaction;
import com.expensetracker.transaction.TransactionRepository;
import com.expensetracker.transaction.TransactionSource;
import com.expensetracker.transaction.TransactionSourceRepository;
import com.expensetracker.transaction.TxnKind;
import com.expensetracker.transaction.TxnStatus;
import com.expensetracker.transaction.dedup.DedupMatcher;
import com.expensetracker.user.UserPreferences;
import com.expensetracker.user.UserPreferencesRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The single entry point for new transactions: validate → resolve merchant → deduplicate → suggest category.
 * "Never count the same transaction twice": exact reference matches are merged, fuzzy matches are flagged
 * SUSPECTED and excluded from totals until the user decides.
 */
@Service
public class IngestionService {

    private static final Duration CANDIDATE_WINDOW = Duration.ofDays(2);

    private final TransactionRepository transactions;
    private final TransactionSourceRepository sources;
    private final MerchantService merchants;
    private final AccountService accounts;
    private final CategoryService categories;
    private final CategorisationService categorisation;
    private final UserPreferencesRepository preferences;
    private final com.expensetracker.transaction.DeletedImports deletedImports;

    public IngestionService(TransactionRepository transactions, TransactionSourceRepository sources, MerchantService merchants,
                            AccountService accounts, CategoryService categories, CategorisationService categorisation,
                            UserPreferencesRepository preferences, com.expensetracker.transaction.DeletedImports deletedImports) {
        this.transactions = transactions;
        this.sources = sources;
        this.merchants = merchants;
        this.accounts = accounts;
        this.categories = categories;
        this.categorisation = categorisation;
        this.preferences = preferences;
        this.deletedImports = deletedImports;
    }

    @Transactional
    public IngestOutcome ingest(UUID userId, RawTransaction raw) {
        validate(raw);
        accounts.require(userId, raw.accountId()); // ownership check
        Optional<TransactionSource> seen = sources.findByUserIdAndSourceTypeAndExternalId(userId, raw.sourceType(), raw.externalId());
        if (seen.isPresent()) {
            UUID id = seen.get().getTransactionId();
            boolean updated = applyAnnotationsOnReimport(userId, id, raw);
            return new IngestOutcome(updated ? IngestOutcome.Result.UPDATED : IngestOutcome.Result.ALREADY_IMPORTED, id,
                    updated ? "added your note/category to the existing transaction" : "already imported");
        }
        if (deletedImports.wasDeleted(userId, raw.sourceType(), raw.externalId())) {
            return new IngestOutcome(IngestOutcome.Result.PREVIOUSLY_DELETED, null, "you deleted this transaction earlier, so it was skipped");
        }
        Optional<Merchant> merchant = merchants.resolve(userId, raw.counterparty());

        DedupMatcher.Candidate incoming = new DedupMatcher.Candidate(null, raw.accountId(), raw.direction(), raw.amount(),
                raw.txnAt(), raw.timeKnown(), raw.reference(), merchant.map(Merchant::getNormalizedName).orElse(null));
        List<Transaction> existing = candidates(userId, raw);
        DedupMatcher.Match match = DedupMatcher.match(incoming, toCandidates(userId, existing));

        if (match.type() == DedupMatcher.MatchType.EXACT) {
            Transaction t = existing.stream().filter(e -> e.getId().equals(match.existingId())).findFirst().orElseThrow();
            boolean hadNote = t.getNotes() != null;
            enrich(t, raw);
            if (!hadNote && t.getNotes() != null && t.getCategorisationStatus() == CategorisationStatus.PENDING) {
                // the second source carried your note: refresh the suggestion
                UserPreferences prefs = preferences.findById(userId).orElseGet(() -> new UserPreferences(userId));
                categorisation.applyOnIngest(t, merchant.map(Merchant::getName).orElse(raw.counterparty()), prefs);
            }
            saveSource(userId, t.getId(), raw);
            return new IngestOutcome(IngestOutcome.Result.MERGED, t.getId(), match.reason());
        }

        Transaction t = new Transaction(userId, raw.accountId(), raw.direction(), raw.amount(), raw.txnAt());
        t.setTimeKnown(raw.timeKnown());
        t.setMerchantId(merchant.map(Merchant::getId).orElse(null));
        t.setCounterparty(raw.counterparty());
        t.setDescription(raw.description());
        t.setReferenceNumber(raw.reference());
        t.setPaymentMethod(raw.method());
        t.setPaymentProvider(raw.paymentProvider());
        t.setStatus(raw.status());
        t.setNotes(raw.notes());
        if (match.type() == DedupMatcher.MatchType.FUZZY) {
            t.setDuplicateStatus(DuplicateStatus.SUSPECTED);
            t.setDuplicateOfId(match.existingId());
        }
        transactions.save(t); // persist first: categorisation history rows reference it

        if (raw.status() == TxnStatus.FAILED || raw.status() == TxnStatus.REVERSED) {
            // never counted; nothing to categorise
            t.setKind(TxnKind.EXCLUDED);
            t.setCategorisationStatus(CategorisationStatus.CONFIRMED);
            t.setSuggestionReason(raw.status() == TxnStatus.FAILED ? "Failed transaction: not counted" : "Reversed transaction: not counted");
        } else if (raw.presetCategoryId() != null) {
            Category c = categories.require(userId, raw.presetCategoryId());
            if (raw.direction() == Direction.DEBIT && c.getKind() == CategoryKind.INCOME) {
                throw ApiException.badRequest("A debit cannot go into an income category");
            }
            t.setCategoryId(c.getId());
            t.setKind(raw.direction() == Direction.CREDIT && c.getKind() == CategoryKind.EXPENSE ? TxnKind.REFUND : TxnKind.defaultFor(raw.direction()));
            t.setCategorisationStatus(CategorisationStatus.CONFIRMED);
        } else {
            UserPreferences prefs = preferences.findById(userId).orElseGet(() -> new UserPreferences(userId));
            categorisation.applyOnIngest(t, merchant.map(Merchant::getName).orElse(raw.counterparty()), prefs);
        }
        saveSource(userId, t.getId(), raw);
        return t.getDuplicateStatus() == DuplicateStatus.SUSPECTED
                ? new IngestOutcome(IngestOutcome.Result.SUSPECTED_DUPLICATE, t.getId(), match.reason())
                : new IngestOutcome(IngestOutcome.Result.CREATED, t.getId(), null);
    }

    /**
     * Re-importing a statement after adding Note/Category columns enriches what's already there instead of skipping it.
     * Never overrides a category you already confirmed.
     */
    private boolean applyAnnotationsOnReimport(UUID userId, UUID transactionId, RawTransaction raw) {
        Optional<Transaction> found = transactions.findByIdAndUserId(transactionId, userId);
        if (found.isEmpty()) return false;
        Transaction t = found.get();
        boolean changed = false;
        if (raw.notes() != null && t.getNotes() == null) {
            t.setNotes(raw.notes());
            changed = true;
        }
        if (t.getCategorisationStatus() != CategorisationStatus.CONFIRMED && t.getDuplicateStatus() == DuplicateStatus.NONE
                && (t.getKind() == TxnKind.EXPENSE || t.getKind() == TxnKind.INCOME || t.getKind() == TxnKind.REFUND)) {
            if (raw.presetCategoryId() != null) {
                Category c = categories.require(userId, raw.presetCategoryId());
                if (!(raw.direction() == Direction.DEBIT && c.getKind() == CategoryKind.INCOME)) {
                    t.setCategoryId(c.getId());
                    t.setKind(raw.direction() == Direction.CREDIT && c.getKind() == CategoryKind.EXPENSE ? TxnKind.REFUND : TxnKind.defaultFor(raw.direction()));
                    t.setCategorisationStatus(CategorisationStatus.CONFIRMED);
                    changed = true;
                }
            } else if (changed && t.getCategorisationStatus() == CategorisationStatus.PENDING) {
                UserPreferences prefs = preferences.findById(userId).orElseGet(() -> new UserPreferences(userId));
                String merchantName = t.getMerchantId() == null ? t.getCounterparty()
                        : merchants.byIds(userId, List.of(t.getMerchantId())).values().stream().findFirst().map(Merchant::getName).orElse(t.getCounterparty());
                categorisation.applyOnIngest(t, merchantName, prefs);
            }
        }
        return changed;
    }

    private List<Transaction> candidates(UUID userId, RawTransaction raw) {
        Map<UUID, Transaction> byId = new LinkedHashMap<>();
        transactions.findByUserIdAndDirectionAndAmountAndTxnAtBetween(userId, raw.direction(), raw.amount(),
                raw.txnAt().minus(CANDIDATE_WINDOW), raw.txnAt().plus(CANDIDATE_WINDOW)).forEach(t -> byId.put(t.getId(), t));
        String ref = DedupMatcher.normaliseRef(raw.reference());
        if (ref != null) {
            transactions.findByReferenceSuffix(userId, raw.amount(), ref).forEach(t -> byId.put(t.getId(), t));
        }
        return new ArrayList<>(byId.values());
    }

    private List<DedupMatcher.Candidate> toCandidates(UUID userId, List<Transaction> existing) {
        var merchantIds = existing.stream().map(Transaction::getMerchantId).filter(java.util.Objects::nonNull).toList();
        Map<UUID, Merchant> m = merchants.byIds(userId, merchantIds);
        return existing.stream()
                .filter(t -> t.getDuplicateStatus() == DuplicateStatus.NONE)
                .map(t -> new DedupMatcher.Candidate(t.getId(), t.getAccountId(), t.getDirection(), t.getAmount(), t.getTxnAt(),
                        t.isTimeKnown(), t.getReferenceNumber(),
                        t.getMerchantId() == null ? null : Optional.ofNullable(m.get(t.getMerchantId())).map(Merchant::getNormalizedName).orElse(null)))
                .toList();
    }

    /** A second source can add detail the first one lacked (exact time from an alert, reference from a statement). */
    private static void enrich(Transaction t, RawTransaction raw) {
        if (t.getReferenceNumber() == null && raw.reference() != null) t.setReferenceNumber(raw.reference());
        if (!t.isTimeKnown() && raw.timeKnown()) {
            t.setTxnAt(raw.txnAt());
            t.setTimeKnown(true);
        }
        if (t.getPaymentProvider() == null && raw.paymentProvider() != null) t.setPaymentProvider(raw.paymentProvider());
        if (t.getDescription() == null && raw.description() != null) t.setDescription(raw.description());
        if (t.getNotes() == null && raw.notes() != null) t.setNotes(raw.notes());
    }

    private void saveSource(UUID userId, UUID transactionId, RawTransaction raw) {
        TransactionSource source = new TransactionSource(userId, transactionId, raw.sourceType(), raw.externalId(), raw.integrationId(), raw.rawPayload());
        source.setSyncId(raw.syncId());
        sources.save(source);
    }

    private static void validate(RawTransaction raw) {
        if (raw.accountId() == null) throw ApiException.badRequest("accountId is required");
        if (raw.direction() == null) throw ApiException.badRequest("direction is required");
        if (raw.amount() == null || raw.amount().signum() <= 0) throw ApiException.badRequest("amount must be positive");
        if (raw.amount().scale() > 2) throw ApiException.badRequest("amount can have at most 2 decimal places");
        if (raw.amount().compareTo(new java.math.BigDecimal("1000000000")) >= 0) throw ApiException.badRequest("amount is unrealistically large");
        if (raw.txnAt() == null) throw ApiException.badRequest("transaction date is required");
        if (raw.sourceType() == null || raw.externalId() == null || raw.externalId().isBlank()) throw new IllegalArgumentException("source is required");
    }
}
