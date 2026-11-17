-- Milestone 3: deletion tombstones, budgets, recurring payments, credit-card details, goals, notifications, MFA.

-- When you delete an imported transaction we remember its source id, so re-importing the same file skips it.
CREATE TABLE deleted_imports (
    user_id      UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    source_type  VARCHAR(20)  NOT NULL,
    external_id  VARCHAR(128) NOT NULL,
    deleted_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, source_type, external_id)
);

-- Budgets: overall (category_id NULL) or per category (the category's children count too).
CREATE TABLE budgets (
    id               UUID PRIMARY KEY,
    user_id          UUID          NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    name             VARCHAR(80)   NOT NULL,
    category_id      UUID REFERENCES categories (id) ON DELETE CASCADE,
    period           VARCHAR(8)    NOT NULL CHECK (period IN ('WEEKLY', 'MONTHLY', 'YEARLY')),
    amount           NUMERIC(19,2) NOT NULL CHECK (amount > 0),
    rollover         BOOLEAN       NOT NULL DEFAULT FALSE,
    alert_thresholds VARCHAR(40)   NOT NULL DEFAULT '50,75,90,100',
    start_date       DATE          NOT NULL,
    archived         BOOLEAN       NOT NULL DEFAULT FALSE,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version          BIGINT        NOT NULL DEFAULT 0
);
CREATE INDEX ix_budgets_user ON budgets (user_id);
CREATE UNIQUE INDEX ux_budgets_scope ON budgets (user_id, COALESCE(category_id, '00000000-0000-0000-0000-000000000000'::uuid), period)
    WHERE NOT archived;

-- One row per threshold crossed per budget period, so an alert is sent once.
CREATE TABLE budget_alerts (
    budget_id    UUID        NOT NULL REFERENCES budgets (id) ON DELETE CASCADE,
    period_start DATE        NOT NULL,
    threshold    INT         NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (budget_id, period_start, threshold)
);

-- Recurring payments: detected (SUGGESTED) until you confirm them. Not every repeat is a subscription.
CREATE TABLE recurring_payments (
    id                UUID PRIMARY KEY,
    user_id           UUID          NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    name              VARCHAR(120)  NOT NULL,
    kind              VARCHAR(12)   NOT NULL CHECK (kind IN ('SUBSCRIPTION', 'BILL', 'EMI', 'RENT', 'INSURANCE', 'SIP', 'INCOME', 'OTHER')),
    status            VARCHAR(10)   NOT NULL CHECK (status IN ('SUGGESTED', 'ACTIVE', 'DISMISSED', 'CANCELLED')),
    direction         VARCHAR(6)    NOT NULL DEFAULT 'DEBIT' CHECK (direction IN ('DEBIT', 'CREDIT')),
    match_key         VARCHAR(200)  NOT NULL,
    merchant_id       UUID REFERENCES merchants (id) ON DELETE SET NULL,
    category_id       UUID REFERENCES categories (id) ON DELETE SET NULL,
    account_id        UUID REFERENCES financial_accounts (id) ON DELETE SET NULL,
    frequency         VARCHAR(10)   NOT NULL CHECK (frequency IN ('WEEKLY', 'MONTHLY', 'QUARTERLY', 'YEARLY')),
    amount            NUMERIC(19,2) NOT NULL CHECK (amount >= 0),
    previous_amount   NUMERIC(19,2),
    amount_varies     BOOLEAN       NOT NULL DEFAULT FALSE,
    last_paid_on      DATE,
    next_due          DATE,
    occurrences       INT           NOT NULL DEFAULT 0,
    reminder_days     INT           NOT NULL DEFAULT 3 CHECK (reminder_days BETWEEN 0 AND 30),
    reviewed_at       TIMESTAMPTZ,
    notes             VARCHAR(500),
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version           BIGINT        NOT NULL DEFAULT 0
);
CREATE INDEX ix_recurring_user ON recurring_payments (user_id, status);
CREATE UNIQUE INDEX ux_recurring_key ON recurring_payments (user_id, match_key, direction);

-- Credit-card terms. Outstanding, cycles and utilisation are computed from the card account's transactions.
CREATE TABLE credit_card_details (
    account_id          UUID PRIMARY KEY REFERENCES financial_accounts (id) ON DELETE CASCADE,
    user_id             UUID          NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    credit_limit        NUMERIC(19,2) NOT NULL CHECK (credit_limit > 0),
    statement_day       INT           NOT NULL CHECK (statement_day BETWEEN 1 AND 28),
    due_days_after      INT           NOT NULL DEFAULT 20 CHECK (due_days_after BETWEEN 10 AND 30),
    min_due_pct         NUMERIC(5,2)  NOT NULL DEFAULT 5.00 CHECK (min_due_pct BETWEEN 0 AND 100),
    min_due_floor       NUMERIC(19,2) NOT NULL DEFAULT 200.00,
    reward_points       NUMERIC(19,2),
    reward_rate         NUMERIC(8,3),
    reward_point_value  NUMERIC(8,4),
    annual_fee          NUMERIC(19,2),
    notes               VARCHAR(500),
    updated_at          TIMESTAMPTZ   NOT NULL DEFAULT now()
);

-- Goals and what funds them.
CREATE TABLE financial_goals (
    id                   UUID PRIMARY KEY,
    user_id              UUID          NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    name                 VARCHAR(100)  NOT NULL,
    kind                 VARCHAR(16)   NOT NULL CHECK (kind IN ('EMERGENCY_FUND', 'HOUSE', 'CAR', 'TRAVEL', 'EDUCATION', 'RETIREMENT',
                                                            'WEDDING', 'GADGET', 'DEBT_PAYOFF', 'OTHER')),
    target_amount        NUMERIC(19,2) NOT NULL CHECK (target_amount > 0),
    target_date          DATE,
    starting_amount      NUMERIC(19,2) NOT NULL DEFAULT 0 CHECK (starting_amount >= 0),
    expected_return_pct  NUMERIC(6,3)  NOT NULL DEFAULT 0,
    priority             INT           NOT NULL DEFAULT 2 CHECK (priority BETWEEN 1 AND 3),
    status               VARCHAR(10)   NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'PAUSED', 'ACHIEVED', 'ARCHIVED')),
    notes                VARCHAR(500),
    created_at           TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version              BIGINT        NOT NULL DEFAULT 0
);
CREATE INDEX ix_goals_user ON financial_goals (user_id);

CREATE TABLE goal_contributions (
    id              UUID PRIMARY KEY,
    goal_id         UUID          NOT NULL REFERENCES financial_goals (id) ON DELETE CASCADE,
    user_id         UUID          NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    amount          NUMERIC(19,2) NOT NULL CHECK (amount <> 0),
    contributed_on  DATE          NOT NULL,
    transaction_id  UUID REFERENCES transactions (id) ON DELETE SET NULL,
    note            VARCHAR(200),
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX ix_goal_contrib ON goal_contributions (goal_id, contributed_on);

-- A holding (e.g. a mutual fund) can fund a goal; share_pct of its current value counts toward it.
CREATE TABLE goal_holdings (
    goal_id     UUID         NOT NULL REFERENCES financial_goals (id) ON DELETE CASCADE,
    holding_id  UUID         NOT NULL REFERENCES holdings (id) ON DELETE CASCADE,
    share_pct   NUMERIC(5,2) NOT NULL DEFAULT 100 CHECK (share_pct > 0 AND share_pct <= 100),
    PRIMARY KEY (goal_id, holding_id)
);

-- Notifications (in-app inbox; browser and email are delivery channels of the same record).
CREATE TABLE notifications (
    id          UUID PRIMARY KEY,
    user_id     UUID          NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    type        VARCHAR(30)   NOT NULL,
    severity    VARCHAR(8)    NOT NULL CHECK (severity IN ('INFO', 'WARNING', 'CRITICAL')),
    title       VARCHAR(160)  NOT NULL,
    body        VARCHAR(1000) NOT NULL,
    link        VARCHAR(200),
    dedupe_key  VARCHAR(200)  NOT NULL,
    emailed     BOOLEAN       NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    read_at     TIMESTAMPTZ,
    UNIQUE (user_id, dedupe_key)
);
CREATE INDEX ix_notifications_user ON notifications (user_id, created_at DESC);

CREATE TABLE notification_preferences (
    user_id              UUID PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    email_enabled        BOOLEAN       NOT NULL DEFAULT FALSE,
    browser_enabled      BOOLEAN       NOT NULL DEFAULT TRUE,
    budget_alerts        BOOLEAN       NOT NULL DEFAULT TRUE,
    bill_reminders       BOOLEAN       NOT NULL DEFAULT TRUE,
    subscription_alerts  BOOLEAN       NOT NULL DEFAULT TRUE,
    card_reminders       BOOLEAN       NOT NULL DEFAULT TRUE,
    unusual_spending     BOOLEAN       NOT NULL DEFAULT TRUE,
    goal_updates         BOOLEAN       NOT NULL DEFAULT TRUE,
    review_reminders     BOOLEAN       NOT NULL DEFAULT TRUE,
    large_txn_threshold  NUMERIC(19,2) NOT NULL DEFAULT 10000,
    updated_at           TIMESTAMPTZ   NOT NULL DEFAULT now()
);

-- Optional TOTP two-factor sign-in. The secret is AES-GCM encrypted with a key from the environment.
ALTER TABLE users ADD COLUMN mfa_enabled BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE users ADD COLUMN mfa_secret_enc VARCHAR(255);
ALTER TABLE users ADD COLUMN mfa_recovery_hashes VARCHAR(1000);
ALTER TABLE transaction_sources ADD COLUMN sync_id UUID REFERENCES sync_history (id) ON DELETE SET NULL;
CREATE INDEX ix_sources_sync ON transaction_sources (sync_id) WHERE sync_id IS NOT NULL;
