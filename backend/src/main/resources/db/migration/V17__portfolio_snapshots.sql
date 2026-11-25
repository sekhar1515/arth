-- One row per day per portfolio (your investments, your parents'), so each tab can show how it changed day by day
-- and month by month. "gain" is value minus money put in, so SIPs and new purchases don't count as growth.
CREATE TABLE portfolio_snapshots (
    user_id        UUID          NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    owner          VARCHAR(16)   NOT NULL,
    snapshot_date  DATE          NOT NULL,
    value          NUMERIC(19,2) NOT NULL,
    invested       NUMERIC(19,2) NOT NULL,
    gain           NUMERIC(19,2) NOT NULL,
    PRIMARY KEY (user_id, owner, snapshot_date)
);
