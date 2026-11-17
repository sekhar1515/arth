package com.expensetracker.account;

import com.expensetracker.audit.AuditService;
import com.expensetracker.common.ApiException;
import com.expensetracker.transaction.TransactionRepository;
import com.expensetracker.transaction.TransactionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** Deleting an account you added by mistake. Its transactions go with it only when you say so. */
@Service
public class AccountDeletionService {

    public record AccountDeleteResult(String account, int transactionsDeleted, int transfersUnpaired) {
    }

    private final FinancialAccountRepository accounts;
    private final AccountService accountService;
    private final TransactionRepository transactions;
    private final TransactionService transactionService;
    private final AuditService audit;

    public AccountDeletionService(FinancialAccountRepository accounts, AccountService accountService, TransactionRepository transactions,
                                  TransactionService transactionService, AuditService audit) {
        this.accounts = accounts;
        this.accountService = accountService;
        this.transactions = transactions;
        this.transactionService = transactionService;
        this.audit = audit;
    }

    @Transactional
    public AccountDeleteResult delete(UUID userId, UUID accountId, boolean withTransactions) {
        FinancialAccount account = accountService.require(userId, accountId);
        long count = transactions.countByUserIdAndAccountId(userId, accountId);
        if (count > 0 && !withTransactions) {
            throw ApiException.conflict(account.getName() + " has " + count + " transactions. Delete them too, or archive the account to keep them.");
        }
        TransactionService.DeleteResult r = count > 0 ? transactionService.deleteAllInAccount(userId, accountId)
                : new TransactionService.DeleteResult(0, 0, 0);
        accounts.delete(account);
        accounts.flush();
        audit.record(userId, "ACCOUNT_DELETED", "financial_account", accountId, account.getName() + " (" + r.deleted() + " transactions)");
        return new AccountDeleteResult(account.getName(), r.deleted(), r.linkedTransfersUnpaired());
    }
}
