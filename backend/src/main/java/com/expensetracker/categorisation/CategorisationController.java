package com.expensetracker.categorisation;

import com.expensetracker.auth.CurrentUser;
import com.expensetracker.transaction.TransactionDtos;
import com.expensetracker.transaction.TransactionService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class CategorisationController {

    public record CategoriseResponse(TransactionDtos.TransactionDto transaction, boolean ruleSaved, int appliedToOthers) {
    }

    public record ExcludeRequest(String note) {
    }

    public record BulkConfirmRequest(List<UUID> ids) {
    }

    public record ResolveDuplicateRequest(CategorisationService.DuplicateAction action) {
    }

    private final CategorisationService service;
    private final TransactionService transactions;

    public CategorisationController(CategorisationService service, TransactionService transactions) {
        this.service = service;
        this.transactions = transactions;
    }

    @PostMapping("/transactions/{id}/categorise")
    public CategoriseResponse categorise(@PathVariable UUID id, @RequestBody CategorisationService.CategoriseRequest req) {
        UUID userId = CurrentUser.id();
        var result = service.categorise(userId, id, req);
        return new CategoriseResponse(transactions.get(userId, id), result.ruleSaved(), result.appliedToOthers());
    }

    @PutMapping("/transactions/{id}/splits")
    public TransactionDtos.TransactionDto split(@PathVariable UUID id, @RequestBody List<CategorisationService.SplitLine> lines) {
        UUID userId = CurrentUser.id();
        service.setSplits(userId, id, lines);
        return transactions.get(userId, id);
    }

    @PostMapping("/transactions/{id}/mark-transfer")
    public CategorisationService.MarkTransferResult markTransfer(@PathVariable UUID id,
                                                                 @RequestBody(required = false) CategorisationService.MarkTransferRequest req) {
        return service.markTransfer(CurrentUser.id(), id, req);
    }

    @PostMapping("/transactions/{id}/exclude")
    public TransactionDtos.TransactionDto exclude(@PathVariable UUID id, @RequestBody(required = false) ExcludeRequest req) {
        UUID userId = CurrentUser.id();
        service.exclude(userId, id, req == null ? null : req.note());
        return transactions.get(userId, id);
    }

    @PostMapping("/transactions/{id}/mark-refund")
    public TransactionDtos.TransactionDto markRefund(@PathVariable UUID id, @RequestBody CategorisationService.MarkRefundRequest req) {
        UUID userId = CurrentUser.id();
        service.markRefund(userId, id, req);
        return transactions.get(userId, id);
    }

    @PostMapping("/review/bulk-confirm")
    public CategorisationService.BulkResult bulkConfirm(@RequestBody BulkConfirmRequest req) {
        return service.bulkConfirm(CurrentUser.id(), req.ids());
    }

    @PostMapping("/review/duplicates/{id}/resolve")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resolveDuplicate(@PathVariable UUID id, @RequestBody ResolveDuplicateRequest req) {
        service.resolveDuplicate(CurrentUser.id(), id, req.action() == null ? CategorisationService.DuplicateAction.KEEP_BOTH : req.action());
    }

    @GetMapping("/review/count")
    public Map<String, Long> count() {
        return Map.of("count", transactions.countNeedsReview(CurrentUser.id()));
    }

    @GetMapping("/rules")
    public List<CategorisationService.RuleDto> rules() {
        return service.listRules(CurrentUser.id());
    }

    @DeleteMapping("/rules/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteRule(@PathVariable UUID id) {
        service.deleteRule(CurrentUser.id(), id);
    }
}
