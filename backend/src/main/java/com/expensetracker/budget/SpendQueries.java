package com.expensetracker.budget;

import com.expensetracker.common.TimeConfig;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Net spending for budgets, with the same rules as the dashboard: posted, non-duplicate rows; expenses minus
 * refunds; transfers and excluded rows never count; money moved into investment categories is saving, not spending.
 * An overall budget counts uncategorised spending too; a category budget counts confirmed lines of that category
 * and its subcategories (split lines go to their own category).
 */
@Component
public class SpendQueries {

    private static final String LINES = """
            WITH lines AS (
              SELECT COALESCE(s.category_id, t.category_id) AS category_id, t.categorisation_status AS st,
                     CASE WHEN t.direction = 'DEBIT' THEN COALESCE(s.amount, t.amount) ELSE -COALESCE(s.amount, t.amount) END AS amount
              FROM transactions t
              LEFT JOIN transaction_splits s ON s.transaction_id = t.id
              WHERE t.user_id = :userId AND t.txn_at >= :from AND t.txn_at < :to AND t.status = 'POSTED' AND t.duplicate_status = 'NONE'
                AND ((t.direction = 'DEBIT' AND t.kind = 'EXPENSE') OR (t.direction = 'CREDIT' AND t.kind = 'REFUND'))
            )
            """;

    static final String OVERALL = LINES + """
            SELECT COALESCE(SUM(l.amount), 0)
            FROM lines l
            LEFT JOIN categories c ON c.id = l.category_id
            LEFT JOIN categories p ON p.id = c.parent_id
            WHERE NOT COALESCE(p.is_investment, c.is_investment, FALSE) OR l.st = 'PENDING'
            """;

    static final String CATEGORY = LINES + """
            SELECT COALESCE(SUM(l.amount), 0)
            FROM lines l
            JOIN categories c ON c.id = l.category_id
            WHERE l.st IN ('CONFIRMED', 'AUTO') AND (c.id = :categoryId OR c.parent_id = :categoryId)
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public SpendQueries(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** @param toExclusive first day not included */
    public BigDecimal spent(UUID userId, UUID categoryId, LocalDate from, LocalDate toExclusive) {
        MapSqlParameterSource p = new MapSqlParameterSource("userId", userId)
                .addValue("from", java.sql.Timestamp.from(TimeConfig.startOfDay(from)))
                .addValue("to", java.sql.Timestamp.from(TimeConfig.startOfDay(toExclusive)));
        BigDecimal v;
        if (categoryId == null) v = jdbc.queryForObject(OVERALL, p, BigDecimal.class);
        else v = jdbc.queryForObject(CATEGORY, p.addValue("categoryId", categoryId), BigDecimal.class);
        return v == null ? BigDecimal.ZERO : v;
    }
}
