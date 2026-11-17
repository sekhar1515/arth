package com.expensetracker.ingestion;

import java.util.UUID;

public record IngestOutcome(Result result, UUID transactionId, String detail) {

    public enum Result {
        /** New transaction recorded. */
        CREATED,
        /** Same transaction already known (reference match): this source was attached to it. */
        MERGED,
        /** Probably the same as an existing one: recorded but excluded from totals until the user resolves it. */
        SUSPECTED_DUPLICATE,
        /** This exact source record was imported before (idempotent re-import). */
        ALREADY_IMPORTED,
        /** Imported before; this time it carried a note or category that was added to the existing transaction. */
        UPDATED,
        /** You deleted this exact source record before; it stays deleted. */
        PREVIOUSLY_DELETED
    }
}
