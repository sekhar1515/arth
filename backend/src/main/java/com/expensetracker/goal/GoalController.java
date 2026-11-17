package com.expensetracker.goal;

import com.expensetracker.auth.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/goals")
public class GoalController {
    private final GoalService service;

    public GoalController(GoalService service) {
        this.service = service;
    }

    @GetMapping
    public List<GoalService.GoalDto> list() {
        return service.list(CurrentUser.id());
    }

    @GetMapping("/emergency-fund-suggestion")
    public Map<String, Object> emergency() {
        return service.emergencyFundSuggestion(CurrentUser.id());
    }

    @GetMapping("/{id}")
    public GoalService.GoalDto get(@PathVariable UUID id) {
        return service.get(CurrentUser.id(), id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public GoalService.GoalDto create(@RequestBody GoalService.GoalRequest req) {
        return service.create(CurrentUser.id(), req);
    }

    @PutMapping("/{id}")
    public GoalService.GoalDto update(@PathVariable UUID id, @RequestBody GoalService.GoalRequest req) {
        return service.update(CurrentUser.id(), id, req);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        service.delete(CurrentUser.id(), id);
    }

    @PostMapping("/{id}/contributions")
    public GoalService.GoalDto contribute(@PathVariable UUID id, @RequestBody GoalService.ContributionRequest req) {
        return service.contribute(CurrentUser.id(), id, req);
    }

    @DeleteMapping("/{id}/contributions/{contributionId}")
    public GoalService.GoalDto deleteContribution(@PathVariable UUID id, @PathVariable UUID contributionId) {
        return service.deleteContribution(CurrentUser.id(), id, contributionId);
    }

    @PostMapping("/{id}/holdings")
    public GoalService.GoalDto link(@PathVariable UUID id, @RequestBody GoalService.LinkRequest req) {
        return service.link(CurrentUser.id(), id, req);
    }

    @DeleteMapping("/{id}/holdings/{holdingId}")
    public GoalService.GoalDto unlink(@PathVariable UUID id, @PathVariable UUID holdingId) {
        return service.unlink(CurrentUser.id(), id, holdingId);
    }
}
