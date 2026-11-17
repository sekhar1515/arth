package com.expensetracker.categorisation;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * A category suggestion. {@code source} explains where it came from (shown to the user as the reason).
 * When {@code transferHint} is set, the transaction looks like a credit card bill payment or self transfer.
 */
public record Suggestion(UUID categoryId, BigDecimal confidence, Source source, String reason, KeywordDictionary.Hint transferHint) {

    public enum Source { NOTE_RULE, NOTE_HISTORY, NOTE_DICTIONARY, MERCHANT_RULE, DESCRIPTION_RULE, HISTORY, DICTIONARY, TRANSFER_HINT }

    public boolean hasCategory() {
        return categoryId != null;
    }
}
