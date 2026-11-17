package com.expensetracker.budget;

import com.expensetracker.auth.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/budgets")
public class BudgetController {
    private final BudgetService service;

    public BudgetController(BudgetService service) {
        this.service = service;
    }

    @GetMapping
    public BudgetService.Overview overview() {
        return service.overview(CurrentUser.id());
    }

    @GetMapping("/suggest")
    public Map<String, Object> suggest(@RequestParam(required = false) UUID categoryId,
                                       @RequestParam(defaultValue = "MONTHLY") BudgetPeriods.Period period) {
        return service.suggest(CurrentUser.id(), categoryId, period);
    }

    @GetMapping("/{id}")
    public BudgetService.BudgetDto get(@PathVariable UUID id) {
        return service.get(CurrentUser.id(), id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BudgetService.BudgetDto create(@RequestBody BudgetService.BudgetRequest req) {
        return service.create(CurrentUser.id(), req);
    }

    @PutMapping("/{id}")
    public BudgetService.BudgetDto update(@PathVariable UUID id, @RequestBody BudgetService.BudgetRequest req) {
        return service.update(CurrentUser.id(), id, req);
    }

    @PostMapping("/{id}/archive")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void archive(@PathVariable UUID id) {
        service.setArchived(CurrentUser.id(), id, true);
    }

    @PostMapping("/{id}/restore")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void restore(@PathVariable UUID id) {
        service.setArchived(CurrentUser.id(), id, false);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        service.delete(CurrentUser.id(), id);
    }
}
