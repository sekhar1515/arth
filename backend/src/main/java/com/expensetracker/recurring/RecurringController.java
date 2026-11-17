package com.expensetracker.recurring;

import com.expensetracker.auth.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/recurring")
public class RecurringController {
    private final RecurringService service;

    public RecurringController(RecurringService service) {
        this.service = service;
    }

    @GetMapping
    public RecurringService.Summary summary() {
        return service.summary(CurrentUser.id());
    }

    @PostMapping("/detect")
    public Map<String, Integer> detect() {
        return Map.of("newSuggestions", service.detect(CurrentUser.id()));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RecurringService.RecurringDto create(@RequestBody RecurringService.RecurringRequest req) {
        return service.create(CurrentUser.id(), req);
    }

    @PutMapping("/{id}")
    public RecurringService.RecurringDto update(@PathVariable UUID id, @RequestBody RecurringService.RecurringRequest req) {
        return service.update(CurrentUser.id(), id, req);
    }

    public record ConfirmRequest(RecurringDetector.Kind kind) {
    }

    @PostMapping("/{id}/confirm")
    public RecurringService.RecurringDto confirm(@PathVariable UUID id, @RequestBody(required = false) ConfirmRequest req) {
        return service.setStatus(CurrentUser.id(), id, RecurringPayment.Status.ACTIVE, req == null ? null : req.kind());
    }

    @PostMapping("/{id}/dismiss")
    public RecurringService.RecurringDto dismiss(@PathVariable UUID id) {
        return service.setStatus(CurrentUser.id(), id, RecurringPayment.Status.DISMISSED, null);
    }

    @PostMapping("/{id}/cancel")
    public RecurringService.RecurringDto cancel(@PathVariable UUID id) {
        return service.setStatus(CurrentUser.id(), id, RecurringPayment.Status.CANCELLED, null);
    }

    @PostMapping("/{id}/reviewed")
    public RecurringService.RecurringDto reviewed(@PathVariable UUID id) {
        return service.markReviewed(CurrentUser.id(), id);
    }

    @PostMapping("/{id}/paid")
    public RecurringService.RecurringDto paid(@PathVariable UUID id) {
        return service.markPaid(CurrentUser.id(), id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        service.delete(CurrentUser.id(), id);
    }
}
