-- Net worth: investments and other assets, loans, daily snapshots, mutual fund NAV cache.

-- Money moved into investments counts as saved, not spent.
ALTER TABLE categories ADD COLUMN is_investment BOOLEAN NOT NULL DEFAULT FALSE;
UPDATE categories SET is_investment = TRUE
WHERE (parent_id IS NULL AND name = 'Investments' AND system_default)
   OR parent_id IN (SELECT id FROM categories WHERE parent_id IS NULL AND name = 'Investments' AND system_default);

-- New default categories for existing users
INSERT INTO categories (id, user_id, parent_id, name, kind, group_name, icon, color, system_default, sort_order)
SELECT gen_random_uuid(), u.id, NULL, 'Household Help', 'EXPENSE', 'Essential', 'users', '#B45309', TRUE, 8
FROM users u
WHERE NOT EXISTS (SELECT 1 FROM categories c WHERE c.user_id = u.id AND c.parent_id IS NULL AND lower(c.name) = 'household help');

INSERT INTO categories (id, user_id, parent_id, name, kind, group_name, icon, color, system_default, sort_order, is_investment)
SELECT gen_random_uuid(), p.user_id, p.id, 'Deposits & Schemes', 'EXPENSE', p.group_name, p.icon, p.color, TRUE, 2, TRUE
FROM categories p
WHERE p.parent_id IS NULL AND p.name = 'Investments' AND p.system_default
  AND NOT EXISTS (SELECT 1 FROM categories c WHERE c.parent_id = p.id AND lower(c.name) = 'deposits & schemes');

CREATE TABLE holdings (
    id                   UUID PRIMARY KEY,
    user_id              UUID          NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    asset_class          VARCHAR(20)   NOT NULL CHECK (asset_class IN ('MUTUAL_FUND', 'STOCK', 'ETF', 'FIXED_DEPOSIT', 'RECURRING_DEPOSIT',
                                                                     'PPF', 'EPF', 'NPS', 'BOND', 'GOLD', 'SGB', 'REAL_ESTATE', 'CRYPTO',
                                                                     'VEHICLE', 'OTHER')),
    name                 VARCHAR(200)  NOT NULL,
    symbol               VARCHAR(40),
    isin                 VARCHAR(12),
    scheme_code          VARCHAR(12),
    folio                VARCHAR(40),
    platform             VARCHAR(60),
    allocation           VARCHAR(12)   NOT NULL CHECK (allocation IN ('EQUITY', 'DEBT', 'HYBRID', 'GOLD', 'REAL_ESTATE', 'CASH', 'OTHER')),
    valuation_method     VARCHAR(12)   NOT NULL CHECK (valuation_method IN ('MARKET_PRICE', 'MANUAL', 'FIXED_RATE')),
    units                NUMERIC(20,6) CHECK (units IS NULL OR units >= 0),
    invested_amount      NUMERIC(19,2) CHECK (invested_amount IS NULL OR invested_amount >= 0),
    current_price        NUMERIC(19,6) CHECK (current_price IS NULL OR current_price >= 0),
    manual_value         NUMERIC(19,2) CHECK (manual_value IS NULL OR manual_value >= 0),
    interest_rate        NUMERIC(6,3),
    compounding_per_year INT CHECK (compounding_per_year IS NULL OR compounding_per_year IN (1, 2, 4, 12)),
    start_date           DATE,
    maturity_date        DATE,
    price_as_of          TIMESTAMPTZ,
    price_source         VARCHAR(12) CHECK (price_source IS NULL OR price_source IN ('AMFI', 'BROKER_CSV', 'MANUAL', 'CALCULATED')),
    notes                VARCHAR(500),
    archived             BOOLEAN       NOT NULL DEFAULT FALSE,
    created_at           TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version              BIGINT        NOT NULL DEFAULT 0,
    CHECK (valuation_method <> 'MARKET_PRICE' OR units IS NOT NULL),
    CHECK (valuation_method <> 'MANUAL' OR manual_value IS NOT NULL),
    CHECK (valuation_method <> 'FIXED_RATE' OR (invested_amount IS NOT NULL AND interest_rate IS NOT NULL AND start_date IS NOT NULL))
);
CREATE INDEX ix_holdings_user ON holdings (user_id);
CREATE INDEX ix_holdings_scheme ON holdings (scheme_code) WHERE scheme_code IS NOT NULL;
CREATE INDEX ix_holdings_isin ON holdings (isin) WHERE isin IS NOT NULL;
CREATE UNIQUE INDEX ux_holdings_identity ON holdings (user_id, isin, COALESCE(folio, ''), COALESCE(platform, ''))
    WHERE isin IS NOT NULL AND NOT archived;

CREATE TABLE liabilities (
    id             UUID PRIMARY KEY,
    user_id        UUID          NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    type           VARCHAR(25)   NOT NULL CHECK (type IN ('HOME_LOAN', 'CAR_LOAN', 'PERSONAL_LOAN', 'EDUCATION_LOAN', 'GOLD_LOAN',
                                                          'LOAN_AGAINST_SECURITIES', 'BORROWED_FROM_PERSON', 'OTHER')),
    name           VARCHAR(150)  NOT NULL,
    lender         VARCHAR(100),
    principal      NUMERIC(19,2) CHECK (principal IS NULL OR principal >= 0),
    outstanding    NUMERIC(19,2) NOT NULL CHECK (outstanding >= 0),
    interest_rate  NUMERIC(6,3),
    emi            NUMERIC(19,2) CHECK (emi IS NULL OR emi >= 0),
    start_date     DATE,
    end_date       DATE,
    as_of          DATE          NOT NULL,
    notes          VARCHAR(500),
    archived       BOOLEAN       NOT NULL DEFAULT FALSE,
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version        BIGINT        NOT NULL DEFAULT 0
);
CREATE INDEX ix_liabilities_user ON liabilities (user_id);

CREATE TABLE net_worth_snapshots (
    id             UUID PRIMARY KEY,
    user_id        UUID          NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    snapshot_date  DATE          NOT NULL,
    assets         NUMERIC(19,2) NOT NULL,
    liabilities    NUMERIC(19,2) NOT NULL,
    net_worth      NUMERIC(19,2) NOT NULL,
    breakdown      TEXT,
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    UNIQUE (user_id, snapshot_date)
);

CREATE TABLE mf_schemes (
    scheme_code    VARCHAR(12) PRIMARY KEY,
    isin_growth    VARCHAR(12),
    isin_reinvest  VARCHAR(12),
    name           VARCHAR(300) NOT NULL,
    amc            VARCHAR(150),
    category       VARCHAR(200),
    nav            NUMERIC(19,6),
    nav_date       DATE,
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_mf_isin_growth ON mf_schemes (isin_growth) WHERE isin_growth IS NOT NULL;
CREATE INDEX ix_mf_isin_reinvest ON mf_schemes (isin_reinvest) WHERE isin_reinvest IS NOT NULL;
CREATE INDEX ix_mf_name ON mf_schemes (lower(name));
