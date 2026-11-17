package com.expensetracker.creditcard;

import com.expensetracker.auth.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/credit-cards")
public class CreditCardController {
    private final CreditCardService service;

    public CreditCardController(CreditCardService service) {
        this.service = service;
    }

    @GetMapping
    public CreditCardService.Overview overview() {
        return service.overview(CurrentUser.id());
    }

    @GetMapping("/{accountId}")
    public CreditCardService.CardDto get(@PathVariable UUID accountId) {
        return service.get(CurrentUser.id(), accountId);
    }

    @PutMapping("/{accountId}")
    public CreditCardService.CardDto configure(@PathVariable UUID accountId, @RequestBody CreditCardService.CardRequest req) {
        return service.configure(CurrentUser.id(), accountId, req);
    }

    @DeleteMapping("/{accountId}/details")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeDetails(@PathVariable UUID accountId) {
        service.removeDetails(CurrentUser.id(), accountId);
    }
}
