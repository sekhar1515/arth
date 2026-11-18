package com.expensetracker.notification;

import com.expensetracker.auth.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/notifications")
public class NotificationController {
    private final NotificationService service;
    private final AlertScheduler alerts;

    public NotificationController(NotificationService service, AlertScheduler alerts) {
        this.service = service;
        this.alerts = alerts;
    }

    @GetMapping
    public List<NotificationService.NotificationDto> list(@RequestParam(defaultValue = "false") boolean unread, @RequestParam(defaultValue = "50") int limit) {
        return service.list(CurrentUser.id(), unread, limit);
    }

    @GetMapping("/unread-count")
    public Map<String, Long> unread() {
        return Map.of("count", service.unreadCount(CurrentUser.id()));
    }

    @PostMapping("/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void read(@PathVariable UUID id) {
        service.markRead(CurrentUser.id(), id);
    }

    @PostMapping("/read-all")
    public Map<String, Integer> readAll() {
        return Map.of("updated", service.markAllRead(CurrentUser.id()));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        service.delete(CurrentUser.id(), id);
    }

    @DeleteMapping("/read")
    public Map<String, Integer> clearRead() {
        return Map.of("deleted", service.clearRead(CurrentUser.id()));
    }

    /** Runs the checks (budgets, bills, cards, review) for the current user now instead of waiting for the hourly job. */
    @PostMapping("/check")
    public Map<String, Integer> check() {
        return Map.of("created", alerts.runFor(CurrentUser.id()));
    }

    @GetMapping("/preferences")
    public NotificationService.PreferencesDto prefs() {
        return service.preferences(CurrentUser.id());
    }

    @PutMapping("/preferences")
    public NotificationService.PreferencesDto update(@RequestBody NotificationService.PreferencesDto req) {
        return service.updatePreferences(CurrentUser.id(), req);
    }
}
