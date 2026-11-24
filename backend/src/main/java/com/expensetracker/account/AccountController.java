package com.expensetracker.account;

import com.expensetracker.auth.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/accounts")
public class AccountController {

    private final AccountService service;

    private final AccountDeletionService deletion;
    private final com.expensetracker.networth.InvestmentPlanService plans;

    public AccountController(AccountService service, AccountDeletionService deletion, com.expensetracker.networth.InvestmentPlanService plans) {
        this.service = service;
        this.deletion = deletion;
        this.plans = plans;
    }

    @GetMapping
    public List<AccountService.AccountDto> list() {
        UUID userId = CurrentUser.id();
        plans.runDue(userId); // SIP/contribution debits due by today, so balances are current
        return service.list(userId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AccountService.AccountDto create(@RequestBody AccountService.AccountRequest req) {
        return service.create(CurrentUser.id(), req);
    }

    @PutMapping("/{id}")
    public AccountService.AccountDto update(@PathVariable UUID id, @RequestBody AccountService.AccountRequest req) {
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

    @org.springframework.web.bind.annotation.DeleteMapping("/{id}")
    public AccountDeletionService.AccountDeleteResult delete(@PathVariable UUID id,
                                                             @org.springframework.web.bind.annotation.RequestParam(defaultValue = "false") boolean withTransactions) {
        return deletion.delete(CurrentUser.id(), id, withTransactions);
    }
}
