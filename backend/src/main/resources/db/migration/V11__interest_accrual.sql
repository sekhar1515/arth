-- Interest for PF/EPF, PPF and other rate-based balances: worked out monthly, credited into the balance each 31 March.
ALTER TABLE holdings ADD COLUMN accrued_interest NUMERIC(19,2) NOT NULL DEFAULT 0;
ALTER TABLE holdings ADD COLUMN interest_accrued_to DATE;

-- Current official rates (EPF FY 2024-25: 8.25%; PPF Oct-Dec 2026 quarter: 7.1%) for balances already added
UPDATE holdings SET interest_rate = 8.25 WHERE asset_class = 'EPF' AND interest_rate IS NULL;
UPDATE holdings SET interest_rate = 7.10 WHERE asset_class = 'PPF' AND interest_rate IS NULL;
UPDATE holdings SET interest_accrued_to = CURRENT_DATE WHERE valuation_method = 'MANUAL' AND interest_rate IS NOT NULL;
