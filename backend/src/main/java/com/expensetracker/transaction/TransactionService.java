package com.expensetracker.transaction;

import com.expensetracker.account.AccountService;
import com.expensetracker.account.FinancialAccount;
import com.expensetracker.account.FinancialAccountRepository;
import com.expensetracker.audit.AuditService;
import com.expensetracker.categorisation.CategorisationHistory;
import com.expensetracker.categorisation.CategorisationHistoryRepository;
import com.expensetracker.category.CategoryService;
import com.expensetracker.common.ApiException;
import com.expensetracker.common.PageResponse;
import com.expensetracker.common.TimeConfig;
import com.expensetracker.ingestion.IngestOutcome;
import com.expensetracker.ingestion.IngestionService;
import com.expensetracker.ingestion.RawTransaction;
import com.expensetracker.merchant.Merchant;
import com.expensetracker.merchant.MerchantService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class TransactionService {

    private final TransactionRepository repository;
    private final TransactionSplitRepository splits;
    private final TransactionSourceRepository sources;
    private final TagRepository tags;
    private final CategorisationHistoryRepository history;
    private final FinancialAccountRepository accountRepository;
    private final AccountService accountService;
    private final CategoryService categoryService;
    private final MerchantService merchantService;
    private final IngestionService ingestion;
    private final AuditService audit;
    private final DeletedImports deletedImports;

    public TransactionService(TransactionRepository repository, TransactionSplitRepository splits, TransactionSourceRepository sources,
                              TagRepository tags, CategorisationHistoryRepository history, FinancialAccountRepository accountRepository,
                              AccountService accountService, CategoryService categoryService, MerchantService merchantService,
                              IngestionService ingestion, AuditService audit, DeletedImports deletedImports) {
        this.repository = repository;
        this.splits = splits;
        this.sources = sources;
        this.tags = tags;
        this.history = history;
        this.accountRepository = accountRepository;
        this.accountService = accountService;
        this.categoryService = categoryService;
        this.merchantService = merchantService;
        this.ingestion = ingestion;
        this.audit = audit;
        this.deletedImports = deletedImports;
    }

    // ------------------------------------------------------------------ queries

    @Transactional(readOnly = true)
    public PageResponse<TransactionDtos.TransactionDto> list(UUID userId, TransactionDtos.TransactionFilter filter, Pageable pageable) {
        Page<Transaction> page = repository.findAll(TransactionSpecifications.build(userId, filter, categoryIds(userId, filter)), pageable);
        return PageResponse.of(page, toDtos(userId, page.getContent()));
    }

    @Transactional(readOnly = true)
    public List<Transaction> listAll(UUID userId, TransactionDtos.TransactionFilter filter) {
        return repository.findAll(TransactionSpecifications.build(userId, filter, categoryIds(userId, filter)),
                org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "txnAt"));
    }

    @Transactional(readOnly = true)
    public TransactionDtos.TransactionDto get(UUID userId, UUID id) {
        return toDtos(userId, List.of(require(userId, id))).get(0);
    }

    @Transactional(readOnly = true)
    public TransactionDtos.TransactionDetailDto detail(UUID userId, UUID id) {
        Transaction t = require(userId, id);
        TransactionDtos.TransactionDto dto = toDtos(userId, List.of(t)).get(0);
        var cats = categoryService.infoMap(userId);
        List<TransactionDtos.SourceDto> srcs = sources.findByTransactionIdOrderByImportedAt(id).stream()
                .map(s -> new TransactionDtos.SourceDto(s.getId(), s.getSourceType(), s.getExternalId(), s.getImportedAt(), s.getRawPayload()))
                .toList();
        List<TransactionDtos.HistoryDto> hist = history.findByTransactionIdOrderByCreatedAtAsc(id).stream()
                .map(h -> new TransactionDtos.HistoryDto(h.getCreatedAt(), h.getAction(), h.getActor(),
                        ref(cats.get(h.getFromCategoryId())), ref(cats.get(h.getToCategoryId())), h.getDetail()))
                .toList();
        return new TransactionDtos.TransactionDetailDto(dto, srcs, hist);
    }

    @Transactional(readOnly = true)
    public long countNeedsReview(UUID userId) {
        return repository.countNeedsReview(userId);
    }

    // ------------------------------------------------------------------ commands

    /** Manual entry: the fallback when no automatic source covers a payment (cash, for example). */
    @Transactional
    public TransactionDtos.TransactionDto createManual(UUID userId, TransactionDtos.ManualTransactionRequest req) {
        if (req.date() == null) throw ApiException.badRequest("date is required");
        if (req.amount() == null) throw ApiException.badRequest("amount is required");
        Instant at = req.date().atTime(req.time() != null ? req.time() : LocalTime.NOON).atZone(TimeConfig.IST).toInstant();
        RawTransaction raw = RawTransaction.builder()
                .account(req.accountId()).direction(req.direction()).amount(req.amount()).txnAt(at).timeKnown(req.time() != null)
                .counterparty(req.merchant()).description(req.description()).method(req.paymentMethod()).provider(req.paymentProvider())
                .category(req.categoryId()).notes(req.notes())
                .source(SourceType.MANUAL, UUID.randomUUID().toString())
                .build();
        IngestOutcome outcome = ingestion.ingest(userId, raw);
        Transaction t = require(userId, outcome.transactionId());
        if (req.business() != null) t.setBusiness(req.business());
        applyTags(userId, t, req.tags());
        audit.record(userId, "TRANSACTION_CREATED_MANUAL", "transaction", t.getId(), outcome.result().name());
        return get(userId, t.getId());
    }

    @Transactional
    public TransactionDtos.TransactionDto update(UUID userId, UUID id, TransactionDtos.UpdateTransactionRequest req) {
        Transaction t = require(userId, id);
        if (req.version() != null && req.version() != t.getVersion()) {
            throw ApiException.conflict("This transaction was changed elsewhere. Reload and try again.");
        }
        List<String> changes = new ArrayList<>();
        if (req.amount() != null && req.amount().compareTo(t.getAmount()) != 0) {
            if (req.amount().signum() <= 0 || req.amount().scale() > 2) throw ApiException.badRequest("amount must be positive with at most 2 decimals");
            if (!splits.findByTransactionId(id).isEmpty()) throw ApiException.badRequest("Remove the split before changing the amount");
            if (t.getTransferGroupId() != null) throw ApiException.badRequest("Unlink the transfer before changing the amount");
            changes.add("amount " + t.getAmount().toPlainString() + " -> " + req.amount().toPlainString());
            t.setAmount(req.amount());
        }
        if (req.date() != null) {
            LocalTime time = req.time() != null ? req.time()
                    : (t.isTimeKnown() ? t.getTxnAt().atZone(TimeConfig.IST).toLocalTime() : LocalTime.NOON);
            Instant at = req.date().atTime(time).atZone(TimeConfig.IST).toInstant();
            if (!at.equals(t.getTxnAt())) changes.add("date");
            t.setTxnAt(at);
            if (req.time() != null) t.setTimeKnown(true);
        }
        if (req.accountId() != null && !req.accountId().equals(t.getAccountId())) {
            accountService.require(userId, req.accountId());
            if (t.getTransferGroupId() != null) throw ApiException.badRequest("Unlink the transfer before moving it to another account");
            changes.add("account");
            t.setAccountId(req.accountId());
        }
        if (req.merchant() != null) {
            String m = req.merchant().trim();
            t.setCounterparty(m);
            t.setMerchantId(merchantService.resolve(userId, m).map(Merchant::getId).orElse(null));
            changes.add("merchant");
        }
        if (req.description() != null) t.setDescription(req.description());
        if (req.paymentMethod() != null) t.setPaymentMethod(req.paymentMethod());
        if (req.paymentProvider() != null) t.setPaymentProvider(req.paymentProvider().isBlank() ? null : req.paymentProvider());
        if (req.status() != null && req.status() != t.getStatus()) {
            changes.add("status " + t.getStatus() + " -> " + req.status());
            t.setStatus(req.status());
        }
        if (req.notes() != null) t.setNotes(req.notes());
        if (req.business() != null) t.setBusiness(req.business());
        if (req.tags() != null) applyTags(userId, t, req.tags());
        if (!changes.isEmpty()) audit.record(userId, "TRANSACTION_EDITED", "transaction", id, String.join(", ", changes));
        repository.flush();
        return get(userId, id);
    }

    public record DeleteResult(int deleted, int linkedTransfersUnpaired, int sourcesDetached) {
    }

    /**
     * Deletes any transaction, manual or imported. Imported rows leave a tombstone so re-importing the same
     * statement does not bring them back. If it was one side of a transfer, the other side becomes a normal
     * transaction again and goes back to review.
     */
    @Transactional
    public DeleteResult delete(UUID userId, UUID id) {
        DeleteResult r = deleteOne(userId, require(userId, id), true);
        repository.flush();
        return r;
    }

    @Transactional
    public DeleteResult deleteMany(UUID userId, Collection<UUID> ids) {
        if (ids == null || ids.isEmpty()) throw ApiException.badRequest("Choose at least one transaction to delete");
        if (ids.size() > 1000) throw ApiException.badRequest("Delete at most 1,000 transactions at a time");
        int deleted = 0, unpaired = 0;
        Set<UUID> seen = new HashSet<>();
        for (UUID id : ids) {
            if (!seen.add(id)) continue;
            Transaction t = repository.findByIdAndUserId(id, userId).orElse(null);
            if (t == null) continue; // already gone (e.g. both sides of a transfer were selected)
            DeleteResult r = deleteOne(userId, t, true);
            deleted += r.deleted();
            unpaired += r.linkedTransfersUnpaired();
            repository.flush();
        }
        audit.record(userId, "TRANSACTIONS_BULK_DELETED", "transaction", null, deleted + " deleted");
        return new DeleteResult(deleted, unpaired, 0);
    }

    /**
     * Undo a whole CSV import: removes every transaction that came only from that import (no tombstones, so you can
     * import the right file again). Transactions that already existed and were merged with a row from this file keep
     * existing; only that file's source record is removed from them.
     */
    @Transactional
    public DeleteResult undoImport(UUID userId, UUID syncId) {
        List<TransactionSource> batch = sources.findByUserIdAndSyncId(userId, syncId);
        if (batch.isEmpty()) throw ApiException.notFound("Import");
        int deleted = 0, unpaired = 0, detached = 0;
        for (TransactionSource s : batch) {
            List<TransactionSource> all = sources.findByTransactionIdOrderByImportedAt(s.getTransactionId());
            boolean onlyThisImport = all.stream().allMatch(o -> syncId.equals(o.getSyncId()));
            if (onlyThisImport) {
                Transaction t = repository.findByIdAndUserId(s.getTransactionId(), userId).orElse(null);
                if (t == null) continue;
                DeleteResult r = deleteOne(userId, t, false);
                deleted += r.deleted();
                unpaired += r.linkedTransfersUnpaired();
            } else {
                sources.delete(s);
                detached++;
            }
            repository.flush();
        }
        audit.record(userId, "IMPORT_UNDONE", "sync_history", syncId, deleted + " deleted, " + detached + " detached");
        return new DeleteResult(deleted, unpaired, detached);
    }

    /** Removes every transaction of an account (used when deleting the account). No tombstones. */
    @Transactional
    public DeleteResult deleteAllInAccount(UUID userId, UUID accountId) {
        int deleted = 0, unpaired = 0;
        for (UUID id : repository.findIdsByAccount(userId, accountId)) {
            Transaction t = repository.findByIdAndUserId(id, userId).orElse(null);
            if (t == null) continue;
            DeleteResult r = deleteOne(userId, t, false);
            deleted += r.deleted();
            unpaired += r.linkedTransfersUnpaired();
        }
        repository.flush();
        return new DeleteResult(deleted, unpaired, 0);
    }

    private DeleteResult deleteOne(UUID userId, Transaction t, boolean tombstone) {
        int unpaired = 0;
        if (t.getTransferGroupId() != null) {
            for (Transaction other : repository.findByTransferGroupId(t.getTransferGroupId())) {
                if (other.getId().equals(t.getId())) continue;
                other.setTransferGroupId(null);
                other.setLinkedTransactionId(null);
                other.setKind(TxnKind.defaultFor(other.getDirection()));
                other.setCategorisationStatus(CategorisationStatus.PENDING);
                unpaired++;
            }
        }
        if (tombstone) {
            for (TransactionSource s : sources.findByTransactionIdOrderByImportedAt(t.getId())) {
                if (s.getSourceType() != SourceType.MANUAL) deletedImports.remember(userId, s.getSourceType(), s.getExternalId());
            }
        }
        repository.delete(t);
        audit.record(userId, "TRANSACTION_DELETED", "transaction", t.getId(), t.getAmount().toPlainString());
        return new DeleteResult(1, unpaired, 0);
    }

    @Transactional(readOnly = true)
    public String exportCsv(UUID userId, TransactionDtos.TransactionFilter filter) {
        List<Transaction> all = listAll(userId, filter);
        List<TransactionDtos.TransactionDto> dtos = toDtos(userId, all);
        DateTimeFormatter date = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(TimeConfig.IST);
        DateTimeFormatter time = DateTimeFormatter.ofPattern("HH:mm").withZone(TimeConfig.IST);
        StringBuilder sb = new StringBuilder("Date,Time,Account,Merchant,Description,Direction,Amount,Kind,Category,Categorisation,Payment method,Provider,Reference,Status,Duplicate,Notes,Tags\n");
        for (TransactionDtos.TransactionDto d : dtos) {
            String category = d.category() != null ? d.category().path()
                    : (!d.splits().isEmpty() ? d.splits().stream().map(s -> s.category().path() + " " + s.amount().toPlainString()).collect(Collectors.joining(" | ")) : "");
            sb.append(String.join(",",
                    date.format(d.txnAt()), d.timeKnown() ? time.format(d.txnAt()) : "",
                    csv(d.account().name()), csv(d.merchant() != null ? d.merchant().name() : d.counterparty()), csv(d.description()),
                    d.direction().name(), d.amount().toPlainString(), d.kind().name(), csv(category), d.categorisationStatus().name(),
                    d.paymentMethod().name(), csv(d.paymentProvider()), csv(d.referenceNumber()), d.status().name(),
                    d.duplicateStatus().name(), csv(d.notes()), csv(String.join(";", d.tags())))).append('\n');
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ mapping

    List<TransactionDtos.TransactionDto> toDtos(UUID userId, List<Transaction> list) {
        if (list.isEmpty()) return List.of();
        Set<UUID> ids = list.stream().map(Transaction::getId).collect(Collectors.toSet());
        Map<UUID, FinancialAccount> accounts = new HashMap<>();
        accountRepository.findByUserIdOrderByArchivedAscCreatedAtAsc(userId).forEach(a -> accounts.put(a.getId(), a));
        Map<UUID, Merchant> merchants = merchantService.byIds(userId,
                list.stream().map(Transaction::getMerchantId).filter(Objects::nonNull).collect(Collectors.toSet()));
        var cats = categoryService.infoMap(userId);
        Map<UUID, List<TransactionSplit>> splitMap = splits.findByTransactionIdIn(ids).stream()
                .collect(Collectors.groupingBy(TransactionSplit::getTransactionId));
        Map<UUID, List<SourceType>> sourceMap = new HashMap<>();
        sources.findByTransactionIdIn(ids).forEach(s -> sourceMap.computeIfAbsent(s.getTransactionId(), k -> new ArrayList<>()).add(s.getSourceType()));

        List<TransactionDtos.TransactionDto> out = new ArrayList<>(list.size());
        for (Transaction t : list) {
            FinancialAccount a = accounts.get(t.getAccountId());
            Merchant m = t.getMerchantId() == null ? null : merchants.get(t.getMerchantId());
            List<TransactionDtos.SplitDto> sp = splitMap.getOrDefault(t.getId(), List.of()).stream()
                    .map(s -> new TransactionDtos.SplitDto(s.getId(), ref(cats.get(s.getCategoryId())), s.getAmount(), s.getNote()))
                    .toList();
            out.add(new TransactionDtos.TransactionDto(
                    t.getId(), t.getTxnAt(), t.isTimeKnown(), t.getAmount(), t.getCurrency(), t.getDirection(), t.getKind(), t.getStatus(),
                    a == null ? null : new TransactionDtos.AccountRef(a.getId(), a.getName(), a.getType()),
                    m == null ? null : new TransactionDtos.MerchantRef(m.getId(), m.getName()),
                    t.getCounterparty(), t.getDescription(), t.getReferenceNumber(), t.getPaymentMethod(), t.getPaymentProvider(),
                    ref(cats.get(t.getCategoryId())), t.getCategorisationStatus(), ref(cats.get(t.getSuggestedCategoryId())),
                    t.getSuggestionConfidence(), t.getSuggestionReason(), t.getDuplicateStatus(), t.getDuplicateOfId(),
                    t.getTransferGroupId(), t.getLinkedTransactionId(), t.getNotes(), t.isBusiness(),
                    t.getTags().stream().map(Tag::getName).sorted().toList(), sp,
                    sourceMap.getOrDefault(t.getId(), List.of()).stream().distinct().toList(),
                    t.needsReview() && t.getStatus() != TxnStatus.FAILED, t.getVersion()));
        }
        return out;
    }

    private static TransactionDtos.CategoryRef ref(CategoryService.CategoryInfo info) {
        return info == null ? null : new TransactionDtos.CategoryRef(info.id(), info.name(), info.path(), info.color(), info.icon());
    }

    private Collection<UUID> categoryIds(UUID userId, TransactionDtos.TransactionFilter f) {
        if (f == null || f.categoryId() == null) return null;
        Set<UUID> ids = new HashSet<>();
        categoryService.infoMap(userId).values().forEach(i -> {
            if (i.id().equals(f.categoryId()) || i.topId().equals(f.categoryId())) ids.add(i.id());
        });
        if (ids.isEmpty()) ids.add(f.categoryId()); // unknown id: matches nothing for this user
        return ids;
    }

    private void applyTags(UUID userId, Transaction t, List<String> names) {
        if (names == null) return;
        t.getTags().clear();
        for (String raw : names) {
            if (raw == null || raw.isBlank()) continue;
            String name = raw.trim().toLowerCase(Locale.ROOT);
            if (name.length() > 40) name = name.substring(0, 40);
            final String n = name;
            Tag tag = tags.findByUserIdAndNameIgnoreCase(userId, n).orElseGet(() -> tags.save(new Tag(userId, n)));
            t.getTags().add(tag);
        }
    }

    private Transaction require(UUID userId, UUID id) {
        return repository.findByIdAndUserId(id, userId).orElseThrow(() -> ApiException.notFound("Transaction"));
    }

    private static String csv(String v) {
        if (v == null) return "";
        String s = v;
        // neutralise spreadsheet formula injection
        if (!s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0) s = "'" + s;
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) s = "\"" + s.replace("\"", "\"\"") + "\"";
        return s;
    }

    static BigDecimal zeroIfNull(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
