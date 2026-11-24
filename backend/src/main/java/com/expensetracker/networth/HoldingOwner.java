package com.expensetracker.networth;

/** Whose investment a holding is. Only {@link #SELF} counts in your net worth. */
public enum HoldingOwner {
    SELF("Mine"),
    PARENTS("Parents");

    private final String label;

    HoldingOwner(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
