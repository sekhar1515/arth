package com.expensetracker.transaction;

/** What happened to the account balance. Amounts are always stored positive. */
public enum Direction {
    DEBIT, CREDIT;

    public Direction opposite() {
        return this == DEBIT ? CREDIT : DEBIT;
    }
}
