-- Purchase history per holding: each buy (and sell) on its date, for exact XIRR and per-purchase returns.
CREATE TABLE holding_lots (
    id              UUID PRIMARY KEY,
    user_id         UUID          NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    holding_id      UUID          NOT NULL REFERENCES holdings (id) ON DELETE CASCADE,
    trade_date      DATE          NOT NULL,
    kind            VARCHAR(4)    NOT NULL CHECK (kind IN ('BUY', 'SELL')),
    amount          NUMERIC(19,2) NOT NULL CHECK (amount > 0),
    units           NUMERIC(20,6) CHECK (units IS NULL OR units > 0),
    price           NUMERIC(19,6) CHECK (price IS NULL OR price > 0),
    source          VARCHAR(8)    NOT NULL DEFAULT 'MANUAL' CHECK (source IN ('MANUAL', 'SIP')),
    installment_id  UUID REFERENCES investment_installments (id) ON DELETE CASCADE,
    note            VARCHAR(200),
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX ix_lots_holding ON holding_lots (holding_id, trade_date);

-- SIP/contribution installments already applied become purchases
INSERT INTO holding_lots (id, user_id, holding_id, trade_date, kind, amount, units, price, source, installment_id)
SELECT gen_random_uuid(), i.user_id, p.holding_id, i.due_date, 'BUY', i.amount, i.units, i.nav, 'SIP', i.id
FROM investment_installments i JOIN investment_plans p ON p.id = i.plan_id;

-- ETFs filed as mutual funds (their names say ETF / BeES): show them under ETF. Fund-of-funds that invest in ETFs stay funds.
UPDATE holdings SET asset_class = 'ETF'
WHERE asset_class = 'MUTUAL_FUND'
  AND (name ~* '\yETF\y' OR name ~* 'BEES')
  AND name !~* 'fund of fund|\yFOF\y|\yFoF\y';
