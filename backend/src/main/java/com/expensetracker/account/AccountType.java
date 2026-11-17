package com.expensetracker.account;

public enum AccountType {
    BANK, CREDIT_CARD, CASH, WALLET;

    public boolean isLiability() {
        return this == CREDIT_CARD;
    }
}
