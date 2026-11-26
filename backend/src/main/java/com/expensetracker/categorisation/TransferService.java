package com.expensetracker.categorisation;

import com.expensetracker.account.AccountService;
import com.expensetracker.common.ApiException;
import com.expensetracker.transaction.Direction;
import com.expensetracker.transaction.PaymentMethod;
import com.expensetracker.transaction.TransactionDtos;
import com.expensetracker.transaction.TransactionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

/**
 * Moving money between two of your own accounts: money out of one, money in to the other, linked as one transfer, so
 * it never counts as spending or income. If the other side is already there (e.g. imported from that bank's
 * statement), it is paired with that instead of being added twice.
 */
@Service
public class TransferService {

    public record TransferRequest(UUID fromAccountId, UUID toAccountId, BigDecimal amount, LocalDate date, LocalTime time,
                                  PaymentMethod paymentMethod, String notes) {
    }

    public record TransferResult(TransactionDtos.TransactionDto out, UUID inTransactionId, boolean matchedExisting) {
    }

    private final TransactionService transactions;
    private final CategorisationService categorisation;
    private final AccountService accounts;

    public TransferService(TransactionService transactions, CategorisationService categorisation, AccountService accounts) {
        this.transactions = transactions;
        this.categorisation = categorisation;
        this.accounts = accounts;
    }

    @Transactional
    public TransferResult transfer(UUID userId, TransferRequest req) {
        if (req.fromAccountId() == null || req.toAccountId() == null) throw ApiException.badRequest("Choose both accounts");
        if (req.fromAccountId().equals(req.toAccountId())) throw ApiException.badRequest("Choose two different accounts");
        if (req.amount() == null || req.amount().signum() <= 0) throw ApiException.badRequest("Enter an amount above zero");
        if (req.date() == null) throw ApiException.badRequest("date is required");
        var from = accounts.require(userId, req.fromAccountId());
        var to = accounts.require(userId, req.toAccountId());
        PaymentMethod method = req.paymentMethod() == null ? PaymentMethod.OTHER : req.paymentMethod();
        TransactionDtos.TransactionDto out = transactions.createManual(userId, new TransactionDtos.ManualTransactionRequest(
                from.getId(), Direction.DEBIT, req.amount(), req.date(), req.time(), "Transfer to " + to.getName(), null, method, null,
                null, req.notes(), null, null));
        var linked = categorisation.markTransfer(userId, out.id(), new CategorisationService.MarkTransferRequest(null, to.getId()));
        return new TransferResult(transactions.get(userId, out.id()), linked.counterpartTransactionId(), !linked.counterpartCreated());
    }
}
