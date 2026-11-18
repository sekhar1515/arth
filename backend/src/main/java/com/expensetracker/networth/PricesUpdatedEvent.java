package com.expensetracker.networth;

/** Published after new fund NAVs or exchange closes are loaded and holdings re-priced. */
public record PricesUpdatedEvent(String what) {
}
