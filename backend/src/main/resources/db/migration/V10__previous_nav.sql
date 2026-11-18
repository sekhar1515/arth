-- The NAV before the latest one, so Arth can show how much a fund moved on its latest day.
ALTER TABLE mf_schemes ADD COLUMN prev_nav NUMERIC(19,6);
ALTER TABLE mf_schemes ADD COLUMN prev_nav_date DATE;
