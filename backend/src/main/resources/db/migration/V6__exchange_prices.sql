-- Daily closing prices for listed stocks, ETFs, SGBs and bonds from the NSE and BSE public bhavcopy files.
CREATE TABLE security_prices (
    isin        VARCHAR(12)   PRIMARY KEY,
    symbol      VARCHAR(40)   NOT NULL,
    exchange    VARCHAR(4)    NOT NULL CHECK (exchange IN ('NSE', 'BSE')),
    series      VARCHAR(4),
    name        VARCHAR(200),
    close_price NUMERIC(19,4) NOT NULL CHECK (close_price >= 0),
    prev_close  NUMERIC(19,4),
    price_date  DATE          NOT NULL,
    updated_at  TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX ix_security_prices_symbol ON security_prices (upper(symbol));
CREATE INDEX ix_security_prices_name ON security_prices (lower(name));

ALTER TABLE holdings DROP CONSTRAINT IF EXISTS holdings_price_source_check;
ALTER TABLE holdings ADD CONSTRAINT holdings_price_source_check
    CHECK (price_source IS NULL OR price_source IN ('AMFI', 'BROKER_CSV', 'MANUAL', 'CALCULATED', 'EXCHANGE'));
