-- Whose investment a holding is. SELF counts in your net worth; PARENTS is tracked on its own
-- (e.g. parents' investments you manage) and is left out of your net worth.
ALTER TABLE holdings ADD COLUMN owner VARCHAR(16) NOT NULL DEFAULT 'SELF';
CREATE INDEX idx_holdings_user_owner ON holdings (user_id, owner);
