package com.expensetracker.user;

/** How new transactions are categorised. Default ALWAYS_ASK: nothing is finalised without user approval. */
public enum CategorisationMode {
    /** Every transaction waits for confirmation (a suggestion is still shown). */
    ALWAYS_ASK,
    /** Suggestions learned from previous decisions; still waits for approval. */
    SMART_SUGGESTION,
    /** Auto-categorise when confidence ≥ threshold, otherwise ask. */
    SEMI_AUTOMATIC,
    /** Auto-categorise whenever any suggestion exists. */
    FULL_AUTOMATIC
}
