package com.expensetracker.categorisation;

import com.expensetracker.account.AccountService;
import com.expensetracker.account.FinancialAccount;
import com.expensetracker.audit.AuditService;
import com.expensetracker.category.Category;
import com.expensetracker.category.CategoryKind;
import com.expensetracker.category.CategoryService;
import com.expensetracker.common.ApiException;
import com.expensetracker.merchant.Merchant;
import com.expensetracker.merchant.MerchantRepository;
import com.expensetracker.transaction.CategorisationStatus;
import com.expensetracker.transaction.Direction;
import com.expensetracker.transaction.DuplicateStatus;
import com.expensetracker.transaction.SourceType;
import com.expensetracker.transaction.Transaction;
import com.expensetracker.transaction.TransactionRepository;
import com.expensetracker.transaction.TransactionSource;
import com.expensetracker.transaction.TransactionSourceRepository;
import com.expensetracker.transaction.TransactionSplit;
import com.expensetracker.transaction.TransactionSplitRepository;
import com.expensetracker.transaction.TxnKind;
import com.expensetracker.transaction.TxnStatus;
import com.expensetracker.user.CategorisationMode;
import com.expensetracker.user.UserPreferences;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The approval workflow. By default (ALWAYS_ASK) nothing is finalised without the user: transactions arrive
 * PENDING with a suggestion, and the user confirms, changes, splits, marks as transfer/refund or excludes them.
 */
@Service
public class CategorisationService {

    public record CategoriseRequest(UUID categoryId, RememberScope remember, String note, Boolean business, String descriptionPattern) {
    }

    public record CategoriseResult(UUID transactionId, boolean ruleSaved, int appliedToOthers) {
    }

    public record SplitLine(UUID categoryId, BigDecimal amount, String note) {
    }

    public record MarkTransferRequest(UUID counterpartTransactionId, UUID counterpartAccountId) {
    }

    public record MarkTransferResult(UUID transactionId, UUID counterpartTransactionId, boolean counterpartCreated) {
    }

    public record MarkRefundRequest(UUID originalTransactionId, UUID categoryId) {
    }

    public record BulkResult(int confirmed, int skipped) {
    }

    public enum DuplicateAction { KEEP_BOTH, MERGE }

    private static final Duration TRANSFER_WINDOW = Duration.ofDays(3);

    private final TransactionRepository transactions;
    private final TransactionSplitRepository splits;
    private final TransactionSourceRepository sources;
    private final CategorisationHistoryRepository history;
    private final MerchantRuleRepository rules;
    private final MerchantRepository merchants;
    private final CategoryService categoryService;
    private final AccountService accountService;
    private final CategorySuggester suggester;
    private final AuditService audit;

    public CategorisationService(TransactionRepository transactions, TransactionSplitRepository splits,
                                 TransactionSourceRepository sources, CategorisationHistoryRepository history,
                                 MerchantRuleRepository rules, MerchantRepository merchants, CategoryService categoryService,
                                 AccountService accountService, CategorySuggester suggester, AuditService audit) {
        this.transactions = transactions;
        this.splits = splits;
        this.sources = sources;
        this.history = history;
        this.rules = rules;
        this.merchants = merchants;
        this.categoryService = categoryService;
        this.accountService = accountService;
        this.suggester = suggester;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ ingestion hook

    /** Called for every newly ingested transaction. Sets a suggestion and, only if the user's mode allows, auto-categorises. */
    public void applyOnIngest(Transaction t, String merchantName, UserPreferences prefs) {
        Optional<Suggestion> s = suggester.suggest(t.getUserId(), t.getMerchantId(), merchantName, t.getDescription(), t.getNotes(), t.getDirection());
        if (s.isEmpty()) return;
        Suggestion suggestion = s.get();
        t.setSuggestedCategoryId(suggestion.categoryId());
        t.setSuggestionConfidence(suggestion.confidence());
        t.setSuggestionReason(suggestion.reason());
        if (!suggestion.hasCategory()) return; // transfer hints always wait for the user
        CategorisationMode mode = prefs.getCategorisationMode();
        boolean auto = switch (mode) {
            case ALWAYS_ASK, SMART_SUGGESTION -> false;
            case SEMI_AUTOMATIC -> suggestion.confidence().compareTo(prefs.getAutoConfidenceThreshold()) >= 0;
            case FULL_AUTOMATIC -> true;
        };
        if (auto && t.getDuplicateStatus() == DuplicateStatus.NONE) {
            Category category = categoryService.require(t.getUserId(), suggestion.categoryId());
            t.setCategoryId(category.getId());
            t.setKind(kindFor(t.getDirection(), category));
            t.setCategorisationStatus(CategorisationStatus.AUTO);
            history.save(new CategorisationHistory(t.getId(), null, category.getId(), "AUTO_CATEGORISED",
                    suggestion.source() == Suggestion.Source.DICTIONARY || suggestion.source() == Suggestion.Source.NOTE_DICTIONARY ? "SYSTEM" : "RULE",
                    mode + " · " + suggestion.reason()));
        }
    }

    // ------------------------------------------------------------------ user actions

    @Transactional
    public CategoriseResult categorise(UUID userId, UUID transactionId, CategoriseRequest req) {
        if (req.categoryId() == null) throw ApiException.badRequest("categoryId is required");
        Transaction t = require(userId, transactionId);
        Category category = categoryService.require(userId, req.categoryId());
        if (category.isArchived()) throw ApiException.badRequest("This category is archived");
        if (t.getDirection() == Direction.DEBIT && category.getKind() == CategoryKind.INCOME) {
            throw ApiException.badRequest("A debit cannot go into an income category. Mark it as a transfer or choose an expense category.");
        }
        assignCategory(t, category, "USER", "CATEGORISED");
        if (req.note() != null) t.setNotes(req.note());
        if (req.business() != null) t.setBusiness(req.business());

        RememberScope scope = req.remember() == null ? RememberScope.ONLY_THIS : req.remember();
        boolean ruleSaved = false;
        int applied = 0;
        switch (scope) {
            case ONLY_THIS -> {
            }
            case MERCHANT -> ruleSaved = saveMerchantRule(userId, t, category);
            case DESCRIPTION -> ruleSaved = savePatternRule(userId, t, category, req.descriptionPattern());
            case NOTE -> ruleSaved = saveNoteRule(userId, t, category, req.descriptionPattern());
            case APPLY_HISTORICAL -> {
                ruleSaved = saveMerchantRule(userId, t, category);
                applied = applyToMerchantHistory(userId, t, category);
            }
        }
        audit.record(userId, "TRANSACTION_CATEGORISED", "transaction", t.getId(),
                category.getName() + " · remember=" + scope + (applied > 0 ? " · applied to " + applied : ""));
        return new CategoriseResult(t.getId(), ruleSaved, applied);
    }

    /** Confirms the suggested category for each listed transaction that has one. */
    @Transactional
    public BulkResult bulkConfirm(UUID userId, List<UUID> ids) {
        if (ids == null || ids.isEmpty()) return new BulkResult(0, 0);
        if (ids.size() > 500) throw ApiException.badRequest("At most 500 transactions per bulk action");
        int confirmed = 0;
        int skipped = 0;
        for (Transaction t : transactions.findByUserIdAndIdIn(userId, ids)) {
            if (t.getSuggestedCategoryId() == null || t.getDuplicateStatus() != DuplicateStatus.NONE
                    || t.getCategorisationStatus() == CategorisationStatus.CONFIRMED) {
                skipped++;
                continue;
            }
            Optional<Category> c = Optional.of(categoryService.require(userId, t.getSuggestedCategoryId())).filter(x -> !x.isArchived());
            if (c.isEmpty() || (t.getDirection() == Direction.DEBIT && c.get().getKind() == CategoryKind.INCOME)) {
                skipped++;
                continue;
            }
            assignCategory(t, c.get(), "USER", "BULK_CONFIRMED");
            confirmed++;
        }
        skipped = ids.size() - confirmed; // includes ids that do not exist or belong to someone else
        audit.record(userId, "BULK_CONFIRM", "transaction", null, confirmed + " confirmed, " + skipped + " skipped");
        return new BulkResult(confirmed, skipped);
    }

    @Transactional
    public void setSplits(UUID userId, UUID transactionId, List<SplitLine> lines) {
        Transaction t = require(userId, transactionId);
        if (lines == null || lines.size() < 2) throw ApiException.badRequest("A split needs at least two parts");
        if (lines.size() > 20) throw ApiException.badRequest("A split can have at most 20 parts");
        BigDecimal sum = BigDecimal.ZERO;
        CategoryKind expected = t.getDirection() == Direction.DEBIT ? CategoryKind.EXPENSE : CategoryKind.INCOME;
        for (SplitLine line : lines) {
            if (line.amount() == null || line.amount().signum() <= 0 || line.amount().scale() > 2) {
                throw ApiException.badRequest("Each split amount must be positive with at most 2 decimals");
            }
            Category c = categoryService.require(userId, line.categoryId());
            if (c.getKind() != expected) throw ApiException.badRequest("Split category '" + c.getName() + "' does not match the transaction type");
            sum = sum.add(line.amount());
        }
        if (sum.compareTo(t.getAmount()) != 0) {
            throw ApiException.badRequest("Split parts add up to " + sum.toPlainString() + " but the transaction is " + t.getAmount().toPlainString());
        }
        unlinkTransfer(t);
        splits.deleteByTransactionId(t.getId());
        splits.flush();
        for (SplitLine line : lines) {
            splits.save(new TransactionSplit(t.getId(), line.categoryId(), line.amount().setScale(2), line.note()));
        }
        UUID from = t.getCategoryId();
        t.setCategoryId(null);
        t.setKind(TxnKind.defaultFor(t.getDirection()));
        t.setCategorisationStatus(CategorisationStatus.CONFIRMED);
        history.save(new CategorisationHistory(t.getId(), from, null, "SPLIT", "USER", lines.size() + " parts"));
        audit.record(userId, "TRANSACTION_SPLIT", "transaction", t.getId(), lines.size() + " parts");
    }

    @Transactional
    public MarkTransferResult markTransfer(UUID userId, UUID transactionId, MarkTransferRequest req) {
        Transaction t = require(userId, transactionId);
        unlinkTransfer(t);
        Transaction counterpart = null;
        boolean created = false;
        if (req != null && req.counterpartTransactionId() != null) {
            counterpart = require(userId, req.counterpartTransactionId());
            if (counterpart.getId().equals(t.getId())) throw ApiException.badRequest("A transaction cannot be its own counterpart");
            if (counterpart.getDirection() == t.getDirection()) throw ApiException.badRequest("The counterpart must move money in the opposite direction");
            if (counterpart.getAmount().compareTo(t.getAmount()) != 0) throw ApiException.badRequest("Transfer amounts must match");
            if (counterpart.getAccountId().equals(t.getAccountId())) throw ApiException.badRequest("A transfer must be between two different accounts");
            unlinkTransfer(counterpart);
        } else if (req != null && req.counterpartAccountId() != null) {
            FinancialAccount other = accountService.require(userId, req.counterpartAccountId());
            if (other.getId().equals(t.getAccountId())) throw ApiException.badRequest("Choose a different account for the other side of the transfer");
            counterpart = transactions.findTransferCounterparts(userId, other.getId(), t.getDirection().opposite(), t.getAmount(),
                            t.getTxnAt().minus(TRANSFER_WINDOW), t.getTxnAt().plus(TRANSFER_WINDOW), t.getId()).stream()
                    .min(Comparator.comparing(c -> Duration.between(c.getTxnAt(), t.getTxnAt()).abs()))
                    .orElse(null);
            if (counterpart == null) {
                FinancialAccount source = accountService.require(userId, t.getAccountId());
                counterpart = new Transaction(userId, other.getId(), t.getDirection().opposite(), t.getAmount(), t.getTxnAt());
                counterpart.setTimeKnown(t.isTimeKnown());
                counterpart.setDescription((t.getDirection() == Direction.DEBIT ? "Transfer from " : "Transfer to ") + source.getName());
                counterpart.setPaymentMethod(t.getPaymentMethod());
                counterpart.setReferenceNumber(t.getReferenceNumber());
                counterpart = transactions.save(counterpart);
                sources.save(new TransactionSource(userId, counterpart.getId(), SourceType.MANUAL, "transfer:" + t.getId(), null,
                        "Created as the other side of transfer " + t.getId()));
                created = true;
            }
        }
        UUID group = UUID.randomUUID();
        makeTransfer(t, group, counterpart);
        if (counterpart != null) makeTransfer(counterpart, group, t);
        audit.record(userId, "MARKED_TRANSFER", "transaction", t.getId(),
                counterpart == null ? "one-sided" : "paired with " + counterpart.getId() + (created ? " (created)" : ""));
        return new MarkTransferResult(t.getId(), counterpart == null ? null : counterpart.getId(), created);
    }

    @Transactional
    public void exclude(UUID userId, UUID transactionId, String note) {
        Transaction t = require(userId, transactionId);
        unlinkTransfer(t);
        splits.deleteByTransactionId(t.getId());
        UUID from = t.getCategoryId();
        t.setKind(TxnKind.EXCLUDED);
        t.setCategoryId(null);
        t.setCategorisationStatus(CategorisationStatus.CONFIRMED);
        if (note != null) t.setNotes(note);
        history.save(new CategorisationHistory(t.getId(), from, null, "EXCLUDED", "USER", note));
        audit.record(userId, "TRANSACTION_EXCLUDED", "transaction", t.getId(), note);
    }

    /** Marks a credit as a refund: it reduces spending in the original category instead of counting as income. */
    @Transactional
    public void markRefund(UUID userId, UUID transactionId, MarkRefundRequest req) {
        Transaction t = require(userId, transactionId);
        if (t.getDirection() != Direction.CREDIT) throw ApiException.badRequest("Only a credit can be a refund");
        UUID categoryId = req == null ? null : req.categoryId();
        UUID linked = null;
        if (req != null && req.originalTransactionId() != null) {
            Transaction original = require(userId, req.originalTransactionId());
            if (original.getDirection() != Direction.DEBIT) throw ApiException.badRequest("The original transaction must be a debit");
            linked = original.getId();
            if (categoryId == null) categoryId = original.getCategoryId();
        }
        if (categoryId == null) throw ApiException.badRequest("Choose the original transaction or the expense category this refund belongs to");
        Category c = categoryService.require(userId, categoryId);
        if (c.getKind() != CategoryKind.EXPENSE) throw ApiException.badRequest("A refund must belong to an expense category");
        unlinkTransfer(t);
        splits.deleteByTransactionId(t.getId());
        UUID from = t.getCategoryId();
        t.setKind(TxnKind.REFUND);
        t.setCategoryId(c.getId());
        t.setLinkedTransactionId(linked);
        t.setCategorisationStatus(CategorisationStatus.CONFIRMED);
        history.save(new CategorisationHistory(t.getId(), from, c.getId(), "MARKED_REFUND", "USER", linked == null ? null : "original " + linked));
        audit.record(userId, "MARKED_REFUND", "transaction", t.getId(), c.getName());
    }

    @Transactional
    public void resolveDuplicate(UUID userId, UUID transactionId, DuplicateAction action) {
        Transaction t = require(userId, transactionId);
        if (t.getDuplicateStatus() != DuplicateStatus.SUSPECTED) throw ApiException.badRequest("This transaction is not flagged as a possible duplicate");
        if (action == DuplicateAction.KEEP_BOTH || t.getDuplicateOfId() == null) {
            t.setDuplicateStatus(DuplicateStatus.NONE);
            t.setDuplicateOfId(null);
            history.save(new CategorisationHistory(t.getId(), null, null, "DUPLICATE_KEPT", "USER", null));
            audit.record(userId, "DUPLICATE_KEPT", "transaction", t.getId(), null);
            return;
        }
        Transaction original = require(userId, t.getDuplicateOfId());
        for (TransactionSource s : sources.findByTransactionIdOrderByImportedAt(t.getId())) {
            s.setTransactionId(original.getId());
        }
        sources.flush();
        if (original.getReferenceNumber() == null) original.setReferenceNumber(t.getReferenceNumber());
        if (!original.isTimeKnown() && t.isTimeKnown()) {
            original.setTxnAt(t.getTxnAt());
            original.setTimeKnown(true);
        }
        if (original.getPaymentProvider() == null) original.setPaymentProvider(t.getPaymentProvider());
        history.save(new CategorisationHistory(original.getId(), null, null, "DUPLICATE_MERGED", "USER", "merged " + t.getId()));
        transactions.delete(t);
        audit.record(userId, "DUPLICATE_MERGED", "transaction", original.getId(), "removed " + t.getId());
    }

    // ------------------------------------------------------------------ rules

    public record RuleDto(UUID id, RuleMatchType matchType, UUID merchantId, String merchantName, String pattern,
                          UUID categoryId, int hitCount, java.time.Instant createdAt) {
    }

    @Transactional(readOnly = true)
    public List<RuleDto> listRules(UUID userId) {
        List<MerchantRule> all = rules.findByUserIdOrderByCreatedAtDesc(userId);
        var merchantIds = all.stream().map(MerchantRule::getMerchantId).filter(java.util.Objects::nonNull).toList();
        var names = new java.util.HashMap<UUID, String>();
        merchants.findByUserIdAndIdIn(userId, merchantIds).forEach(m -> names.put(m.getId(), m.getName()));
        return all.stream().map(r -> new RuleDto(r.getId(), r.getMatchType(), r.getMerchantId(), names.get(r.getMerchantId()),
                r.getPattern(), r.getCategoryId(), r.getHitCount(), r.getCreatedAt())).toList();
    }

    @Transactional
    public void deleteRule(UUID userId, UUID ruleId) {
        MerchantRule r = rules.findByIdAndUserId(ruleId, userId).orElseThrow(() -> ApiException.notFound("Rule"));
        rules.delete(r);
        audit.record(userId, "RULE_DELETED", "merchant_rule", ruleId, null);
    }

    // ------------------------------------------------------------------ internals

    private Transaction require(UUID userId, UUID id) {
        return transactions.findByIdAndUserId(id, userId).orElseThrow(() -> ApiException.notFound("Transaction"));
    }

    private void assignCategory(Transaction t, Category category, String actor, String action) {
        unlinkTransfer(t);
        splits.deleteByTransactionId(t.getId());
        UUID from = t.getCategoryId();
        t.setCategoryId(category.getId());
        t.setKind(kindFor(t.getDirection(), category));
        t.setCategorisationStatus(CategorisationStatus.CONFIRMED);
        if (t.getKind() != TxnKind.REFUND) t.setLinkedTransactionId(null);
        history.save(new CategorisationHistory(t.getId(), from, category.getId(), action, actor, null));
    }

    /** A credit placed in an expense category is a refund of that kind of spending, not income. */
    static TxnKind kindFor(Direction direction, Category category) {
        if (direction == Direction.CREDIT && category.getKind() == CategoryKind.EXPENSE) return TxnKind.REFUND;
        return TxnKind.defaultFor(direction);
    }

    private void makeTransfer(Transaction t, UUID group, Transaction counterpart) {
        splits.deleteByTransactionId(t.getId());
        UUID from = t.getCategoryId();
        t.setKind(TxnKind.TRANSFER);
        t.setCategoryId(null);
        t.setCategorisationStatus(CategorisationStatus.CONFIRMED);
        t.setTransferGroupId(group);
        t.setLinkedTransactionId(counterpart == null ? null : counterpart.getId());
        history.save(new CategorisationHistory(t.getId(), from, null, "MARKED_TRANSFER", "USER",
                counterpart == null ? null : "paired with " + counterpart.getId()));
    }

    /** If the transaction was part of a transfer pair, detach the other side and send it back for review. */
    private void unlinkTransfer(Transaction t) {
        if (t.getTransferGroupId() == null) return;
        for (Transaction other : transactions.findByTransferGroupId(t.getTransferGroupId())) {
            if (other.getId().equals(t.getId())) continue;
            other.setTransferGroupId(null);
            other.setLinkedTransactionId(null);
            other.setKind(TxnKind.defaultFor(other.getDirection()));
            other.setCategorisationStatus(CategorisationStatus.PENDING);
            history.save(new CategorisationHistory(other.getId(), null, null, "TRANSFER_UNLINKED", "SYSTEM", "other side was re-categorised"));
        }
        t.setTransferGroupId(null);
        t.setLinkedTransactionId(null);
        t.setKind(TxnKind.defaultFor(t.getDirection()));
    }

    private boolean saveMerchantRule(UUID userId, Transaction t, Category category) {
        if (t.getMerchantId() == null) throw ApiException.badRequest("This transaction has no recognised merchant to remember. Use a description rule instead.");
        MerchantRule rule = rules.findByUserIdAndMatchTypeAndMerchantId(userId, RuleMatchType.MERCHANT, t.getMerchantId())
                .orElseGet(() -> MerchantRule.forMerchant(userId, t.getMerchantId(), category.getId()));
        rule.setCategoryId(category.getId());
        rules.save(rule);
        return true;
    }

    private boolean savePatternRule(UUID userId, Transaction t, Category category, String requestedPattern) {
        String pattern = requestedPattern;
        if (pattern == null || pattern.isBlank()) {
            pattern = t.getMerchantId() == null ? t.getCounterparty()
                    : merchants.findByIdAndUserId(t.getMerchantId(), userId).map(Merchant::getName).orElse(t.getCounterparty());
        }
        if (pattern == null || pattern.trim().length() < 3) throw ApiException.badRequest("Description rule needs at least 3 characters of text to match");
        String p = pattern.trim();
        if (p.length() > 120) p = p.substring(0, 120);
        final String finalPattern = p;
        MerchantRule rule = rules.findByUserIdAndMatchType(userId, RuleMatchType.DESCRIPTION_CONTAINS).stream()
                .filter(r -> r.getPattern().equalsIgnoreCase(finalPattern)).findFirst()
                .orElseGet(() -> MerchantRule.forPattern(userId, finalPattern, category.getId()));
        rule.setCategoryId(category.getId());
        rules.save(rule);
        return true;
    }

    /** "Always file this note this way": keyword defaults to the note's core words ("sabzi nov" → "sabzi"). */
    private boolean saveNoteRule(UUID userId, Transaction t, Category category, String requestedKeyword) {
        String keyword = requestedKeyword != null && !requestedKeyword.isBlank()
                ? NoteDictionary.normalise(requestedKeyword) : NoteDictionary.coreKey(t.getNotes());
        if (keyword == null || keyword.length() < 2) throw ApiException.badRequest("This transaction has no note to remember. Add a note first.");
        MerchantRule rule = rules.findByUserIdAndMatchType(userId, RuleMatchType.NOTE_KEYWORD).stream()
                .filter(r -> r.getPattern().equalsIgnoreCase(keyword)).findFirst()
                .orElseGet(() -> MerchantRule.forNote(userId, keyword, category.getId()));
        rule.setCategoryId(category.getId());
        rules.save(rule);
        return true;
    }

    private int applyToMerchantHistory(UUID userId, Transaction source, Category category) {
        int count = 0;
        for (Transaction other : transactions.findByUserIdAndMerchantId(userId, source.getMerchantId())) {
            if (other.getId().equals(source.getId())) continue;
            if (other.getDirection() != source.getDirection()) continue;
            if (other.getKind() == TxnKind.TRANSFER || other.getKind() == TxnKind.EXCLUDED) continue;
            if (other.getStatus() == TxnStatus.FAILED || other.getDuplicateStatus() != DuplicateStatus.NONE) continue;
            if (!splits.findByTransactionId(other.getId()).isEmpty()) continue;
            if (category.getId().equals(other.getCategoryId()) && other.getCategorisationStatus() == CategorisationStatus.CONFIRMED) continue;
            assignCategory(other, category, "RULE", "APPLIED_FROM_RULE");
            count++;
        }
        return count;
    }
}
