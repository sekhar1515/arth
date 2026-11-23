-- NAV history for funds people hold. AMFI publishes NAVs only on working days, so "the first NAV on or after a
-- SIP date" handles weekends and market holidays without a holiday calendar.
CREATE TABLE mf_nav_history (
    scheme_code  VARCHAR(12)   NOT NULL,
    nav_date     DATE          NOT NULL,
    nav          NUMERIC(19,6) NOT NULL,
    PRIMARY KEY (scheme_code, nav_date)
);

INSERT INTO mf_nav_history (scheme_code, nav_date, nav)
SELECT scheme_code, nav_date, nav FROM mf_schemes
WHERE nav IS NOT NULL AND nav_date IS NOT NULL AND scheme_code IN (SELECT scheme_code FROM holdings WHERE scheme_code IS NOT NULL)
ON CONFLICT DO NOTHING;
INSERT INTO mf_nav_history (scheme_code, nav_date, nav)
SELECT scheme_code, prev_nav_date, prev_nav FROM mf_schemes
WHERE prev_nav IS NOT NULL AND prev_nav_date IS NOT NULL AND scheme_code IN (SELECT scheme_code FROM holdings WHERE scheme_code IS NOT NULL)
ON CONFLICT DO NOTHING;

-- installments: the date units were actually allotted (first NAV on or after the due date)
ALTER TABLE investment_installments ADD COLUMN allotted_on DATE;
UPDATE investment_installments SET allotted_on = nav_date WHERE nav_date IS NOT NULL;
UPDATE holding_lots l SET trade_date = i.nav_date
FROM investment_installments i WHERE l.installment_id = i.id AND i.nav_date IS NOT NULL AND i.nav_date > l.trade_date;
