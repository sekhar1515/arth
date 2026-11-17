package com.expensetracker.transaction;

import com.expensetracker.auth.CurrentUser;
import com.expensetracker.common.PageResponse;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class TransactionController {

    private static final Set<String> SORTABLE = Set.of("txnAt", "amount", "createdAt");

    private final TransactionService service;

    public TransactionController(TransactionService service) {
        this.service = service;
    }

    @GetMapping("/transactions")
    public PageResponse<TransactionDtos.TransactionDto> list(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) UUID accountId,
            @RequestParam(required = false) UUID categoryId,
            @RequestParam(required = false) UUID merchantId,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) BigDecimal minAmount,
            @RequestParam(required = false) BigDecimal maxAmount,
            @RequestParam(required = false) Direction direction,
            @RequestParam(required = false) TxnKind kind,
            @RequestParam(required = false) CategorisationStatus categorisationStatus,
            @RequestParam(required = false) PaymentMethod paymentMethod,
            @RequestParam(required = false) String provider,
            @RequestParam(required = false) TxnStatus status,
            @RequestParam(required = false) Boolean uncategorised,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(defaultValue = "txnAt") String sort,
            @RequestParam(defaultValue = "desc") String dir) {
        var filter = new TransactionDtos.TransactionFilter(from, to, accountId, categoryId, merchantId, q, minAmount, maxAmount,
                direction, kind, categorisationStatus, paymentMethod, provider, status, null, uncategorised);
        return service.list(CurrentUser.id(), filter, pageable(page, size, sort, dir));
    }

    @GetMapping("/review")
    public PageResponse<TransactionDtos.TransactionDto> review(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) UUID accountId,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) BigDecimal minAmount,
            @RequestParam(required = false) BigDecimal maxAmount,
            @RequestParam(required = false) Direction direction,
            @RequestParam(required = false) PaymentMethod paymentMethod,
            @RequestParam(required = false) String provider,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        var filter = new TransactionDtos.TransactionFilter(from, to, accountId, null, null, q, minAmount, maxAmount,
                direction, null, null, paymentMethod, provider, null, true, null);
        return service.list(CurrentUser.id(), filter, pageable(page, size, "txnAt", "desc"));
    }

    @GetMapping("/transactions/{id}")
    public TransactionDtos.TransactionDetailDto detail(@PathVariable UUID id) {
        return service.detail(CurrentUser.id(), id);
    }

    @PostMapping("/transactions")
    @ResponseStatus(HttpStatus.CREATED)
    public TransactionDtos.TransactionDto create(@RequestBody TransactionDtos.ManualTransactionRequest req) {
        return service.createManual(CurrentUser.id(), req);
    }

    @PutMapping("/transactions/{id}")
    public TransactionDtos.TransactionDto update(@PathVariable UUID id, @RequestBody TransactionDtos.UpdateTransactionRequest req) {
        return service.update(CurrentUser.id(), id, req);
    }

    @DeleteMapping("/transactions/{id}")
    public TransactionService.DeleteResult delete(@PathVariable UUID id) {
        return service.delete(CurrentUser.id(), id);
    }

    public record BulkDeleteRequest(java.util.List<UUID> ids) {
    }

    @PostMapping("/transactions/bulk-delete")
    public TransactionService.DeleteResult bulkDelete(@org.springframework.web.bind.annotation.RequestBody BulkDeleteRequest req) {
        return service.deleteMany(CurrentUser.id(), req.ids());
    }

    @PostMapping("/imports/{syncId}/undo")
    public TransactionService.DeleteResult undoImport(@PathVariable UUID syncId) {
        return service.undoImport(CurrentUser.id(), syncId);
    }

    @GetMapping("/transactions/export.csv")
    public ResponseEntity<byte[]> export(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) UUID accountId,
            @RequestParam(required = false) UUID categoryId) {
        var filter = new TransactionDtos.TransactionFilter(from, to, accountId, categoryId, null, null, null, null,
                null, null, null, null, null, null, null, null);
        byte[] body = service.exportCsv(CurrentUser.id(), filter).getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"arth-transactions.csv\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(body);
    }

    private static Pageable pageable(int page, int size, String sort, String dir) {
        String field = SORTABLE.contains(sort) ? sort : "txnAt";
        Sort.Direction d = "asc".equalsIgnoreCase(dir) ? Sort.Direction.ASC : Sort.Direction.DESC;
        return PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 200), Sort.by(d, field).and(Sort.by(Sort.Direction.DESC, "createdAt")));
    }
}
