-- When each person last pressed "Refresh prices" (allowed once before market open and once after close, IST).
ALTER TABLE users ADD COLUMN last_price_refresh_at TIMESTAMPTZ;
