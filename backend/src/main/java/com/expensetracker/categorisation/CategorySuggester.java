package com.expensetracker.categorisation;

import com.expensetracker.category.Category;
import com.expensetracker.category.CategoryKind;
import com.expensetracker.category.CategoryRepository;
import com.expensetracker.category.CategoryService;
import com.expensetracker.transaction.Direction;
import com.expensetracker.transaction.TransactionRepository;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Suggests a category. Your note says what a payment was for, so it is read first; then who you paid.
 * <ol>
 *   <li>Your note rules ("always: sabzi → Groceries")                 0.98</li>
 *   <li>Your past confirmations of the same note                      0.85–0.97</li>
 *   <li>Built-in note dictionary (English + Hinglish)                 0.92</li>
 *   <li>Your merchant rule                                            0.99</li>
 *   <li>Your description rule                                         0.95</li>
 *   <li>Your past confirmations for the merchant                      0.70–0.95</li>
 *   <li>Built-in merchant dictionary                                  0.50–0.90</li>
 * </ol>
 * Notes are checked before merchants because the same payee (a person, a local shop) can be paid for different things.
 */
@Component
public class CategorySuggester {

    private record CountRow(UUID categoryId, long count) {
    }

    private final MerchantRuleRepository rules;
    private final CategoryRepository categories;
    private final CategoryService categoryService;
    private final TransactionRepository transactions;

    public CategorySuggester(MerchantRuleRepository rules, CategoryRepository categories, CategoryService categoryService,
                             TransactionRepository transactions) {
        this.rules = rules;
        this.categories = categories;
        this.categoryService = categoryService;
        this.transactions = transactions;
    }

    public Optional<Suggestion> suggest(UUID userId, UUID merchantId, String merchantName, String description, String note, Direction direction) {
        String noteKey = NoteDictionary.coreKey(note);
        if (noteKey != null) {
            Optional<Suggestion> fromNote = suggestFromNote(userId, note, noteKey, direction);
            if (fromNote.isPresent()) return fromNote;
        }
        Optional<Suggestion> fromMerchant = suggestFromMerchant(userId, merchantId, merchantName, description, direction);
        if (fromMerchant.isPresent() || noteKey != null) return fromMerchant;
        return suggestFromDescriptionWords(userId, description, direction);
    }

    /**
     * Last resort when there is no note and the payee is unknown: words in the description itself ("medicines",
     * "haircut", "gym") still say what the payment was for. Lower confidence than a note, and only real categories:
     * transfer hints from bank text are too easily misread.
     */
    private Optional<Suggestion> suggestFromDescriptionWords(UUID userId, String description, Direction direction) {
        String key = NoteDictionary.normalise(description);
        if (key == null) return Optional.empty();
        Optional<NoteDictionary.Match> m = NoteDictionary.lookup(key, direction);
        if (m.isEmpty() || m.get().hint() != NoteDictionary.Hint.CATEGORY) return Optional.empty();
        return categoryService.resolvePath(userId, m.get().categoryPath())
                .filter(c -> usable(userId, c.getId(), direction))
                .map(c -> new Suggestion(c.getId(), bd(0.75), Suggestion.Source.NOTE_DICTIONARY,
                        "The description mentions \"" + m.get().keyword() + "\"", null));
    }

    private Optional<Suggestion> suggestFromNote(UUID userId, String note, String noteKey, Direction direction) {
        // 1. explicit note rules: longest matching keyword wins
        MerchantRule bestRule = null;
        for (MerchantRule r : rules.findByUserIdAndMatchType(userId, RuleMatchType.NOTE_KEYWORD)) {
            if (NoteDictionary.matchesPattern(note, r.getPattern()) && usable(userId, r.getCategoryId(), direction)
                    && (bestRule == null || r.getPattern().length() > bestRule.getPattern().length())) {
                bestRule = r;
            }
        }
        if (bestRule != null) {
            bestRule.recordHit();
            return Optional.of(new Suggestion(bestRule.getCategoryId(), bd(0.98), Suggestion.Source.NOTE_RULE,
                    "Your note says \"" + note.trim() + "\": you file \"" + bestRule.getPattern() + "\" this way", null));
        }
        // 2. what you chose before for the same note
        List<CountRow> history = counts(transactions.confirmedCategoryCountsForNote(userId, noteKey, direction));
        long total = history.stream().mapToLong(CountRow::count).sum();
        for (CountRow top : history) {
            if (!usable(userId, top.categoryId(), direction)) continue;
            double share = (double) top.count() / total;
            double confidence = total == 1 ? 0.85 : Math.min(0.97, 0.60 + 0.37 * share);
            return Optional.of(new Suggestion(top.categoryId(), bd(confidence), Suggestion.Source.NOTE_HISTORY,
                    String.format("Your note says \"%s\": you filed it this way %d of %d times", note.trim(), top.count(), total), null));
        }
        // 3. built-in understanding of common notes
        Optional<NoteDictionary.Match> m = NoteDictionary.lookup(note, direction);
        if (m.isPresent()) {
            NoteDictionary.Match match = m.get();
            switch (match.hint()) {
                case LENT_OR_REPAID -> {
                    return Optional.of(new Suggestion(null, bd(0.90), Suggestion.Source.TRANSFER_HINT,
                            "Your note says \"" + note.trim() + "\": money lent or paid back isn't spending or income. Exclude it, or pick a category.",
                            KeywordDictionary.Hint.LENT_OR_REPAID));
                }
                case CREDIT_CARD_PAYMENT -> {
                    return Optional.of(new Suggestion(null, bd(0.92), Suggestion.Source.TRANSFER_HINT,
                            "Your note says this is a credit card bill: mark it as a transfer so it is not counted twice",
                            KeywordDictionary.Hint.CREDIT_CARD_PAYMENT));
                }
                case SELF_TRANSFER -> {
                    return Optional.of(new Suggestion(null, bd(0.90), Suggestion.Source.TRANSFER_HINT,
                            "Your note says this went to your own account: mark it as a transfer", KeywordDictionary.Hint.SELF_TRANSFER));
                }
                case CATEGORY -> {
                    Optional<Category> c = categoryService.resolvePath(userId, match.categoryPath());
                    if (c.isPresent() && usable(userId, c.get().getId(), direction)) {
                        return Optional.of(new Suggestion(c.get().getId(), bd(0.92), Suggestion.Source.NOTE_DICTIONARY,
                                "Your note says \"" + note.trim() + "\"", null));
                    }
                }
            }
        }
        return Optional.empty();
    }

    private Optional<Suggestion> suggestFromMerchant(UUID userId, UUID merchantId, String merchantName, String description, Direction direction) {
        // 4. explicit merchant rule
        if (merchantId != null) {
            Optional<MerchantRule> rule = rules.findByUserIdAndMatchTypeAndMerchantId(userId, RuleMatchType.MERCHANT, merchantId);
            if (rule.isPresent() && usable(userId, rule.get().getCategoryId(), direction)) {
                rule.get().recordHit();
                return Optional.of(new Suggestion(rule.get().getCategoryId(), bd(0.99), Suggestion.Source.MERCHANT_RULE,
                        "You always categorise " + (merchantName == null ? "this merchant" : merchantName) + " this way", null));
            }
        }
        // 5. description rules (longest pattern wins)
        String haystack = ((merchantName == null ? "" : merchantName) + " " + (description == null ? "" : description)).toLowerCase(Locale.ROOT);
        MerchantRule best = null;
        for (MerchantRule r : rules.findByUserIdAndMatchType(userId, RuleMatchType.DESCRIPTION_CONTAINS)) {
            String p = r.getPattern().toLowerCase(Locale.ROOT);
            if (!p.isBlank() && haystack.contains(p) && usable(userId, r.getCategoryId(), direction)
                    && (best == null || p.length() > best.getPattern().length())) {
                best = r;
            }
        }
        if (best != null) {
            best.recordHit();
            return Optional.of(new Suggestion(best.getCategoryId(), bd(0.95), Suggestion.Source.DESCRIPTION_RULE,
                    "Matches your rule for \"" + best.getPattern() + "\"", null));
        }
        // 6. your history with this merchant
        if (merchantId != null) {
            List<CountRow> history = counts(transactions.confirmedCategoryCounts(userId, merchantId, direction));
            long total = history.stream().mapToLong(CountRow::count).sum();
            for (CountRow top : history) {
                if (!usable(userId, top.categoryId(), direction)) continue;
                double share = (double) top.count() / total;
                double confidence = total == 1 ? 0.70 : Math.min(0.95, 0.50 + 0.45 * share);
                return Optional.of(new Suggestion(top.categoryId(), bd(confidence), Suggestion.Source.HISTORY,
                        String.format("You chose this for %d of %d previous %s transactions", top.count(), total,
                                merchantName == null ? "similar" : merchantName), null));
            }
        }
        // 7. built-in merchant dictionary
        Optional<KeywordDictionary.Match> match = KeywordDictionary.lookup(merchantName, description, direction);
        if (match.isPresent()) {
            KeywordDictionary.Match m = match.get();
            if (m.hint() != KeywordDictionary.Hint.CATEGORY) {
                String why = m.hint() == KeywordDictionary.Hint.CREDIT_CARD_PAYMENT
                        ? "Looks like a credit card bill payment: mark it as a transfer so it is not counted twice"
                        : "Looks like a transfer between your own accounts";
                return Optional.of(new Suggestion(null, bd(m.confidence()), Suggestion.Source.TRANSFER_HINT, why, m.hint()));
            }
            Optional<Category> category = categoryService.resolvePath(userId, m.categoryPath());
            if (category.isPresent() && kindMatches(category.get(), direction)) {
                return Optional.of(new Suggestion(category.get().getId(), bd(m.confidence()), Suggestion.Source.DICTIONARY,
                        "Known merchant keyword \"" + m.keyword().toLowerCase(Locale.ROOT) + "\"", null));
            }
        }
        return Optional.empty();
    }

    private static List<CountRow> counts(List<Object[]> rows) {
        List<CountRow> out = new ArrayList<>();
        for (Object[] row : rows) out.add(new CountRow((UUID) row[0], ((Number) row[1]).longValue()));
        return out;
    }

    private boolean usable(UUID userId, UUID categoryId, Direction direction) {
        return categories.findByIdAndUserId(categoryId, userId)
                .filter(c -> !c.isArchived())
                .filter(c -> kindMatches(c, direction) || direction == Direction.CREDIT)
                .isPresent();
    }

    private static boolean kindMatches(Category c, Direction d) {
        return (d == Direction.DEBIT) == (c.getKind() == CategoryKind.EXPENSE);
    }

    private static BigDecimal bd(double v) {
        return BigDecimal.valueOf(v).setScale(3, RoundingMode.HALF_UP);
    }
}
