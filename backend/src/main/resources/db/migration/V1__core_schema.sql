-- Arth core schema (milestone 1). Money: NUMERIC(19,2), always positive with explicit direction.

CREATE TABLE users (
    id              UUID PRIMARY KEY,
    email           VARCHAR(254) NOT NULL,
    password_hash   VARCHAR(100) NOT NULL,
    display_name    VARCHAR(100) NOT NULL,
    is_demo         BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_login_at   TIMESTAMPTZ
);
CREATE UNIQUE INDEX ux_users_email ON users (lower(email));

CREATE TABLE user_preferences (
    user_id               UUID PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    categorisation_mode   VARCHAR(20)  NOT NULL DEFAULT 'ALWAYS_ASK'
        CHECK (categorisation_mode IN ('ALWAYS_ASK', 'SMART_SUGGESTION', 'SEMI_AUTOMATIC', 'FULL_AUTOMATIC')),
    auto_confidence_threshold NUMERIC(4,3) NOT NULL DEFAULT 0.900,
    currency              VARCHAR(3)      NOT NULL DEFAULT 'INR',
    theme                 VARCHAR(10)  NOT NULL DEFAULT 'system' CHECK (theme IN ('light', 'dark', 'system')),
    external_ai_consent   BOOLEAN      NOT NULL DEFAULT FALSE,
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE financial_accounts (
    id               UUID PRIMARY KEY,
    user_id          UUID          NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    name             VARCHAR(100)  NOT NULL,
    type             VARCHAR(20)   NOT NULL CHECK (type IN ('BANK', 'CREDIT_CARD', 'CASH', 'WALLET')),
    institution      VARCHAR(100),
    last4            VARCHAR(4),
    opening_balance  NUMERIC(19,2) NOT NULL DEFAULT 0,
    opening_date     DATE,
    currency         VARCHAR(3)       NOT NULL DEFAULT 'INR',
    archived         BOOLEAN       NOT NULL DEFAULT FALSE,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX ix_accounts_user ON financial_accounts (user_id);

CREATE TABLE categories (
    id             UUID PRIMARY KEY,
    user_id        UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    parent_id      UUID REFERENCES categories (id) ON DELETE RESTRICT,
    name           VARCHAR(60)  NOT NULL,
    kind           VARCHAR(10)  NOT NULL CHECK (kind IN ('EXPENSE', 'INCOME')),
    group_name     VARCHAR(30),
    icon           VARCHAR(40),
    color          VARCHAR(9),
    archived       BOOLEAN      NOT NULL DEFAULT FALSE,
    system_default BOOLEAN      NOT NULL DEFAULT FALSE,
    sort_order     INT          NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CHECK (parent_id IS NULL OR parent_id <> id)
);
CREATE UNIQUE INDEX ux_categories_name ON categories (user_id, COALESCE(parent_id, '00000000-0000-0000-0000-000000000000'::uuid), lower(name));
CREATE INDEX ix_categories_user ON categories (user_id);

CREATE TABLE merchants (
    id               UUID PRIMARY KEY,
    user_id          UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    name             VARCHAR(120) NOT NULL,
    normalized_name  VARCHAR(120) NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (user_id, normalized_name)
);

CREATE TABLE merchant_rules (
    id           UUID PRIMARY KEY,
    user_id      UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    match_type   VARCHAR(25)  NOT NULL CHECK (match_type IN ('MERCHANT', 'DESCRIPTION_CONTAINS')),
    merchant_id  UUID REFERENCES merchants (id) ON DELETE CASCADE,
    pattern      VARCHAR(120),
    category_id  UUID         NOT NULL REFERENCES categories (id) ON DELETE CASCADE,
    hit_count    INT          NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CHECK ((match_type = 'MERCHANT' AND merchant_id IS NOT NULL) OR (match_type = 'DESCRIPTION_CONTAINS' AND pattern IS NOT NULL))
);
CREATE UNIQUE INDEX ux_rules_merchant ON merchant_rules (user_id, merchant_id) WHERE match_type = 'MERCHANT';
CREATE UNIQUE INDEX ux_rules_pattern ON merchant_rules (user_id, lower(pattern)) WHERE match_type = 'DESCRIPTION_CONTAINS';

CREATE TABLE transactions (
    id                      UUID PRIMARY KEY,
    user_id                 UUID          NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    account_id              UUID          NOT NULL REFERENCES financial_accounts (id) ON DELETE RESTRICT,
    direction               VARCHAR(6)    NOT NULL CHECK (direction IN ('DEBIT', 'CREDIT')),
    amount                  NUMERIC(19,2) NOT NULL CHECK (amount > 0),
    currency                VARCHAR(3)       NOT NULL DEFAULT 'INR',
    txn_at                  TIMESTAMPTZ   NOT NULL,
    time_known              BOOLEAN       NOT NULL DEFAULT TRUE,
    merchant_id             UUID REFERENCES merchants (id) ON DELETE SET NULL,
    counterparty            VARCHAR(160),
    description             VARCHAR(500),
    reference_number        VARCHAR(64),
    payment_method          VARCHAR(12)   NOT NULL DEFAULT 'OTHER'
        CHECK (payment_method IN ('UPI', 'IMPS', 'NEFT', 'RTGS', 'CARD', 'ATM', 'CASH', 'CHEQUE', 'NACH', 'OTHER')),
    payment_provider        VARCHAR(30),
    status                  VARCHAR(10)   NOT NULL DEFAULT 'POSTED' CHECK (status IN ('POSTED', 'PENDING', 'FAILED', 'REVERSED')),
    kind                    VARCHAR(10)   NOT NULL CHECK (kind IN ('EXPENSE', 'INCOME', 'TRANSFER', 'REFUND', 'EXCLUDED')),
    category_id             UUID REFERENCES categories (id) ON DELETE SET NULL,
    categorisation_status   VARCHAR(10)   NOT NULL DEFAULT 'PENDING' CHECK (categorisation_status IN ('PENDING', 'CONFIRMED', 'AUTO')),
    suggested_category_id   UUID REFERENCES categories (id) ON DELETE SET NULL,
    suggestion_confidence   NUMERIC(4,3),
    suggestion_reason       VARCHAR(200),
    duplicate_status        VARCHAR(10)   NOT NULL DEFAULT 'NONE' CHECK (duplicate_status IN ('NONE', 'SUSPECTED')),
    duplicate_of_id         UUID REFERENCES transactions (id) ON DELETE SET NULL,
    transfer_group_id       UUID,
    linked_transaction_id   UUID REFERENCES transactions (id) ON DELETE SET NULL,
    notes                   VARCHAR(1000),
    business                BOOLEAN       NOT NULL DEFAULT FALSE,
    created_at              TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version                 BIGINT        NOT NULL DEFAULT 0
);
CREATE INDEX ix_txn_user_date ON transactions (user_id, txn_at DESC);
CREATE INDEX ix_txn_user_catstatus ON transactions (user_id, categorisation_status);
CREATE INDEX ix_txn_user_ref ON transactions (user_id, reference_number) WHERE reference_number IS NOT NULL;
CREATE INDEX ix_txn_dedup ON transactions (account_id, amount, txn_at);
CREATE INDEX ix_txn_user_merchant ON transactions (user_id, merchant_id);
CREATE INDEX ix_txn_transfer_group ON transactions (transfer_group_id) WHERE transfer_group_id IS NOT NULL;

CREATE TABLE transaction_splits (
    id              UUID PRIMARY KEY,
    transaction_id  UUID          NOT NULL REFERENCES transactions (id) ON DELETE CASCADE,
    category_id     UUID          NOT NULL REFERENCES categories (id) ON DELETE RESTRICT,
    amount          NUMERIC(19,2) NOT NULL CHECK (amount > 0),
    note            VARCHAR(200)
);
CREATE INDEX ix_splits_txn ON transaction_splits (transaction_id);

CREATE TABLE account_integrations (
    id                 UUID PRIMARY KEY,
    user_id            UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    provider           VARCHAR(30)  NOT NULL,
    status             VARCHAR(15)  NOT NULL CHECK (status IN ('CONNECTED', 'ERROR', 'DISCONNECTED')),
    default_account_id UUID REFERENCES financial_accounts (id) ON DELETE SET NULL,
    token_hash         VARCHAR(64),
    token_hint         VARCHAR(8),
    last_sync_at       TIMESTAMPTZ,
    next_sync_at       TIMESTAMPTZ,
    last_error         VARCHAR(500),
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_integration_token ON account_integrations (token_hash) WHERE token_hash IS NOT NULL;
CREATE INDEX ix_integrations_user ON account_integrations (user_id);

CREATE TABLE integration_consents (
    id              UUID PRIMARY KEY,
    integration_id  UUID         NOT NULL REFERENCES account_integrations (id) ON DELETE CASCADE,
    scope           VARCHAR(100) NOT NULL,
    granted_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    revoked_at      TIMESTAMPTZ
);

CREATE TABLE sync_history (
    id              UUID PRIMARY KEY,
    user_id         UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    integration_id  UUID REFERENCES account_integrations (id) ON DELETE CASCADE,
    source_type     VARCHAR(20)  NOT NULL,
    started_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    finished_at     TIMESTAMPTZ,
    status          VARCHAR(10)  NOT NULL CHECK (status IN ('RUNNING', 'SUCCESS', 'PARTIAL', 'FAILED')),
    imported        INT          NOT NULL DEFAULT 0,
    merged          INT          NOT NULL DEFAULT 0,
    suspected       INT          NOT NULL DEFAULT 0,
    skipped         INT          NOT NULL DEFAULT 0,
    error           VARCHAR(1000)
);
CREATE INDEX ix_sync_user ON sync_history (user_id, started_at DESC);

CREATE TABLE transaction_sources (
    id              UUID PRIMARY KEY,
    user_id         UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    transaction_id  UUID         NOT NULL REFERENCES transactions (id) ON DELETE CASCADE,
    source_type     VARCHAR(20)  NOT NULL CHECK (source_type IN ('MANUAL', 'CSV_IMPORT', 'SMS', 'EMAIL', 'DEMO', 'GMAIL', 'ACCOUNT_AGGREGATOR', 'PHONEPE_STATEMENT')),
    external_id     VARCHAR(128) NOT NULL,
    integration_id  UUID REFERENCES account_integrations (id) ON DELETE SET NULL,
    raw_payload     TEXT,
    imported_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (user_id, source_type, external_id)
);
CREATE INDEX ix_sources_txn ON transaction_sources (transaction_id);

CREATE TABLE categorisation_history (
    id               UUID PRIMARY KEY,
    transaction_id   UUID         NOT NULL REFERENCES transactions (id) ON DELETE CASCADE,
    from_category_id UUID REFERENCES categories (id) ON DELETE SET NULL,
    to_category_id   UUID REFERENCES categories (id) ON DELETE SET NULL,
    action           VARCHAR(20)  NOT NULL,
    actor            VARCHAR(10)  NOT NULL CHECK (actor IN ('USER', 'RULE', 'SYSTEM')),
    detail           VARCHAR(300),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_cathist_txn ON categorisation_history (transaction_id, created_at);

CREATE TABLE tags (
    id       UUID PRIMARY KEY,
    user_id  UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    name     VARCHAR(40) NOT NULL
);
CREATE UNIQUE INDEX ux_tags_name ON tags (user_id, lower(name));

CREATE TABLE transaction_tags (
    transaction_id UUID NOT NULL REFERENCES transactions (id) ON DELETE CASCADE,
    tag_id         UUID NOT NULL REFERENCES tags (id) ON DELETE CASCADE,
    PRIMARY KEY (transaction_id, tag_id)
);

CREATE TABLE audit_log (
    id          UUID PRIMARY KEY,
    user_id     UUID REFERENCES users (id) ON DELETE CASCADE,
    action      VARCHAR(50)  NOT NULL,
    entity_type VARCHAR(40),
    entity_id   UUID,
    detail      VARCHAR(1000),
    ip_address  VARCHAR(45),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_audit_user ON audit_log (user_id, created_at DESC);
