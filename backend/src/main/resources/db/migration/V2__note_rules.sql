-- Notes you type when paying ("sabzi", "rent nov") drive categorisation.
ALTER TABLE merchant_rules DROP CONSTRAINT merchant_rules_match_type_check;
ALTER TABLE merchant_rules DROP CONSTRAINT merchant_rules_check;
ALTER TABLE merchant_rules ADD CONSTRAINT merchant_rules_match_type_check
    CHECK (match_type IN ('MERCHANT', 'DESCRIPTION_CONTAINS', 'NOTE_KEYWORD'));
ALTER TABLE merchant_rules ADD CONSTRAINT merchant_rules_target_check
    CHECK ((match_type = 'MERCHANT' AND merchant_id IS NOT NULL) OR (match_type IN ('DESCRIPTION_CONTAINS', 'NOTE_KEYWORD') AND pattern IS NOT NULL));
CREATE UNIQUE INDEX ux_rules_note ON merchant_rules (user_id, lower(pattern)) WHERE match_type = 'NOTE_KEYWORD';

-- normalised note (lower-case, no punctuation) so Arth can learn "sabzi → Groceries" from your confirmations
ALTER TABLE transactions ADD COLUMN note_key VARCHAR(120);
UPDATE transactions
SET note_key = NULLIF(left(btrim(regexp_replace(regexp_replace(lower(notes), '[^a-z0-9 ]', ' ', 'g'), '\s+', ' ', 'g')), 120), '')
WHERE notes IS NOT NULL;
CREATE INDEX ix_txn_user_note ON transactions (user_id, note_key) WHERE note_key IS NOT NULL;
