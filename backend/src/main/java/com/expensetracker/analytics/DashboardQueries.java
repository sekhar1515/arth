package com.expensetracker.analytics;

/**
 * SQL for the dashboard. Rules (ARCHITECTURE.md ADR-3/5/9):
 * <ul>
 *   <li>Only POSTED, non-suspected-duplicate rows count.</li>
 *   <li>Expenses = DEBIT/EXPENSE − CREDIT/REFUND. Income = CREDIT/INCOME. TRANSFER and EXCLUDED never count.</li>
 *   <li>Pending (uncategorised) rows count in totals but appear only as the "Pending categorisation" bucket.</li>
 *   <li>Split transactions contribute each split line to its own category.</li>
 * </ul>
 * Calendar grouping uses Asia/Kolkata.
 */
final class DashboardQueries {
    private DashboardQueries() {
    }

    static final String BASE = " t.user_id = :userId AND t.txn_at >= :from AND t.txn_at < :to AND t.status = 'POSTED' AND t.duplicate_status = 'NONE' ";

    static final String TOTALS = """
            SELECT
              COALESCE(SUM(t.amount) FILTER (WHERE t.direction = 'CREDIT' AND t.kind = 'INCOME'), 0) AS income,
              COALESCE(SUM(t.amount) FILTER (WHERE t.direction = 'DEBIT' AND t.kind = 'EXPENSE'), 0) AS gross_expense,
              COALESCE(SUM(t.amount) FILTER (WHERE t.direction = 'CREDIT' AND t.kind = 'REFUND'), 0) AS refunds,
              COALESCE(SUM(t.amount) FILTER (WHERE t.direction = 'DEBIT' AND t.kind = 'EXPENSE' AND t.categorisation_status = 'PENDING'), 0) AS pending_expense,
              COALESCE(SUM(t.amount) FILTER (WHERE t.direction = 'CREDIT' AND t.kind = 'INCOME' AND t.categorisation_status = 'PENDING'), 0) AS pending_income,
              COUNT(*) FILTER (WHERE t.categorisation_status = 'PENDING' AND t.kind IN ('EXPENSE', 'INCOME')) AS pending_count,
              COALESCE(SUM(t.amount) FILTER (WHERE t.kind = 'TRANSFER' AND t.direction = 'DEBIT'), 0) AS transfers_out
            FROM transactions t
            WHERE """ + BASE;

    static final String CATEGORY_BREAKDOWN = """
            WITH lines AS (
              SELECT COALESCE(s.category_id, t.category_id) AS category_id,
                     CASE WHEN t.direction = :sign THEN COALESCE(s.amount, t.amount) ELSE -COALESCE(s.amount, t.amount) END AS amount
              FROM transactions t
              LEFT JOIN transaction_splits s ON s.transaction_id = t.id
              WHERE """ + BASE + """
                AND t.categorisation_status IN ('CONFIRMED', 'AUTO')
                AND t.kind IN (:kinds)
            )
            SELECT COALESCE(c.parent_id, c.id) AS top_id, SUM(l.amount) AS total, COUNT(*) AS n
            FROM lines l JOIN categories c ON c.id = l.category_id
            GROUP BY COALESCE(c.parent_id, c.id)
            HAVING SUM(l.amount) <> 0
            ORDER BY total DESC
            """;

    static final String TOP_MERCHANTS = """
            SELECT m.id, m.name, SUM(t.amount) AS total, COUNT(*) AS n
            FROM transactions t JOIN merchants m ON m.id = t.merchant_id
            WHERE """ + BASE + """
              AND t.direction = 'DEBIT' AND t.kind = 'EXPENSE'
            GROUP BY m.id, m.name
            ORDER BY total DESC
            LIMIT 5
            """;

    static final String PAYMENT_METHODS = """
            SELECT t.payment_method, t.payment_provider, SUM(t.amount) AS total, COUNT(*) AS n
            FROM transactions t
            WHERE """ + BASE + """
              AND t.direction = 'DEBIT' AND t.kind = 'EXPENSE'
            GROUP BY t.payment_method, t.payment_provider
            ORDER BY total DESC
            """;

    static final String MONTHLY = """
            SELECT to_char(date_trunc('month', t.txn_at AT TIME ZONE 'Asia/Kolkata'), 'YYYY-MM') AS month,
              COALESCE(SUM(t.amount) FILTER (WHERE t.direction = 'CREDIT' AND t.kind = 'INCOME'), 0) AS income,
              COALESCE(SUM(t.amount) FILTER (WHERE t.direction = 'DEBIT' AND t.kind = 'EXPENSE'), 0)
                - COALESCE(SUM(t.amount) FILTER (WHERE t.direction = 'CREDIT' AND t.kind = 'REFUND'), 0) AS expenses
            FROM transactions t
            WHERE """ + BASE + """
            GROUP BY 1
            ORDER BY 1
            """;

    /** Confirmed money moved into investment categories, per month (top-level flag decides; splits included). */
    static final String MONTHLY_INVESTED = """
            WITH lines AS (
              SELECT t.txn_at, COALESCE(s.category_id, t.category_id) AS category_id,
                     CASE WHEN t.direction = 'DEBIT' THEN COALESCE(s.amount, t.amount) ELSE -COALESCE(s.amount, t.amount) END AS amount
              FROM transactions t
              LEFT JOIN transaction_splits s ON s.transaction_id = t.id
              WHERE """ + BASE + """
                AND t.categorisation_status IN ('CONFIRMED', 'AUTO')
                AND t.kind IN ('EXPENSE', 'REFUND')
            )
            SELECT to_char(date_trunc('month', l.txn_at AT TIME ZONE 'Asia/Kolkata'), 'YYYY-MM') AS month, SUM(l.amount) AS invested
            FROM lines l
            JOIN categories c ON c.id = l.category_id
            LEFT JOIN categories p ON p.id = c.parent_id
            WHERE COALESCE(p.is_investment, c.is_investment)
            GROUP BY 1
            """;

    static final String DAILY = """
            SELECT CAST(t.txn_at AT TIME ZONE 'Asia/Kolkata' AS date) AS day,
              COALESCE(SUM(t.amount) FILTER (WHERE t.direction = 'DEBIT' AND t.kind = 'EXPENSE'), 0)
                - COALESCE(SUM(t.amount) FILTER (WHERE t.direction = 'CREDIT' AND t.kind = 'REFUND'), 0) AS expenses,
              COALESCE(SUM(t.amount) FILTER (WHERE t.direction = 'CREDIT' AND t.kind = 'INCOME'), 0) AS income
            FROM transactions t
            WHERE """ + BASE + """
            GROUP BY 1
            ORDER BY 1
            """;

    static final String SUSPECTED_COUNT = "SELECT COUNT(*) FROM transactions WHERE user_id = :userId AND duplicate_status = 'SUSPECTED'";
}
