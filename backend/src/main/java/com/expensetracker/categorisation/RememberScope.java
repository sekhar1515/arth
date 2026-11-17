package com.expensetracker.categorisation;

/** Answer to "Should I remember this category for future transactions from this merchant?" */
public enum RememberScope {
    ONLY_THIS,
    /** Always use this category for this merchant. */
    MERCHANT,
    /** Remember for this payment description text. */
    DESCRIPTION,
    /** Remember for the note you typed ("sabzi" → Groceries), whoever you paid. */
    NOTE,
    /** Remember for the merchant AND re-categorise this merchant's existing transactions. */
    APPLY_HISTORICAL
}
