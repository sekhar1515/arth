package com.expensetracker.transaction;

/**
 * Economic meaning of a transaction, independent of its direction.
 * TRANSFER and EXCLUDED never count as income or expense; REFUND reduces expenses.
 */
public enum TxnKind {
    EXPENSE, INCOME, TRANSFER, REFUND, EXCLUDED;

    public static TxnKind defaultFor(Direction direction) {
        return direction == Direction.DEBIT ? EXPENSE : INCOME;
    }
}
