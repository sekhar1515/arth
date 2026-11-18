-- SIPs and regular contributions (PF/EPF, PPF, NPS, RD...). Each due date adds to the investment and, when a bank
-- account is chosen, records the debit there, so net worth stays right between statement imports.
CREATE TABLE investment_plans (
    id               UUID PRIMARY KEY,
    user_id          UUID          NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    holding_id       UUID          NOT NULL REFERENCES holdings (id) ON DELETE CASCADE,
    kind             VARCHAR(12)   NOT NULL CHECK (kind IN ('SIP', 'CONTRIBUTION')),
    amount           NUMERIC(19,2) NOT NULL CHECK (amount > 0),
    employer_amount  NUMERIC(19,2) CHECK (employer_amount IS NULL OR employer_amount >= 0),
    day_of_month     INT           NOT NULL CHECK (day_of_month BETWEEN 1 AND 28),
    start_date       DATE          NOT NULL,
    end_date         DATE,
    account_id       UUID REFERENCES financial_accounts (id) ON DELETE SET NULL,
    active           BOOLEAN       NOT NULL DEFAULT TRUE,
    last_run_date    DATE,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version          BIGINT        NOT NULL DEFAULT 0,
    CHECK (end_date IS NULL OR end_date >= start_date)
);
CREATE INDEX ix_plans_user ON investment_plans (user_id);
CREATE INDEX ix_plans_holding ON investment_plans (holding_id);

-- One row per due date that has been applied.
CREATE TABLE investment_installments (
    id              UUID PRIMARY KEY,
    plan_id         UUID          NOT NULL REFERENCES investment_plans (id) ON DELETE CASCADE,
    user_id         UUID          NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    due_date        DATE          NOT NULL,
    amount          NUMERIC(19,2) NOT NULL,
    nav             NUMERIC(19,6),
    nav_date        DATE,
    units           NUMERIC(20,6),
    transaction_id  UUID REFERENCES transactions (id) ON DELETE SET NULL,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    UNIQUE (plan_id, due_date)
);
CREATE INDEX ix_installments_user ON investment_installments (user_id, due_date);

-- Debits Arth records on a SIP date ("SCHEDULED") merge with the real line when you import the bank statement.
ALTER TABLE transaction_sources DROP CONSTRAINT IF EXISTS transaction_sources_source_type_check;
ALTER TABLE transaction_sources ADD CONSTRAINT transaction_sources_source_type_check
    CHECK (source_type IN ('MANUAL', 'CSV_IMPORT', 'SMS', 'EMAIL', 'DEMO', 'GMAIL', 'ACCOUNT_AGGREGATOR', 'PHONEPE_STATEMENT', 'SCHEDULED'));
