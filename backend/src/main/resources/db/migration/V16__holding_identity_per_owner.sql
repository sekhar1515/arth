-- The same fund (ISIN + folio + platform) may be held by you and, separately, by your parents: make the
-- "no duplicate holding" rule per owner. Before this, importing or adding a parent's fund you also hold failed.
DROP INDEX IF EXISTS ux_holdings_identity;
CREATE UNIQUE INDEX ux_holdings_identity ON holdings (user_id, owner, isin, COALESCE(folio, ''), COALESCE(platform, ''))
    WHERE isin IS NOT NULL AND NOT archived;
