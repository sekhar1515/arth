# Project status: milestone 4 (verified end to end, deployable)

_Last updated: 17 November 2026_

## Verification status (read this first)

| Area | How it was verified | Result |
|---|---|---|
| Backend compile | `mvn verify` on JDK 27 targeting Java 21, Spring Boot 3.5.7 | ✅ |
| Backend unit tests | 86 JUnit tests (parsers, notes, dedup, valuation, AMFI old and new layout, bhavcopy, budgets, recurring, cards, goals, insights, assistant, writers, TOTP, sign-up policy) | ✅ 86/86 |
| Spring integration tests | 49 tests booting the full app against PostgreSQL 16 (`TEST_DB_URL`, or Testcontainers when Docker is on PATH) | ✅ 49/49 |
| Flyway `V1` to `V6` | Fresh database, **and** a copy of the existing local Docker database (V1, 210 transactions) upgraded in place | ✅ |
| Frontend | `tsc`, `next build`, 22 Vitest tests | ✅ |
| Live data sources | AMFI NAVAll (14,461 schemes) and NSE + BSE bhavcopies (8,930 securities) downloaded and applied to holdings on 17 Nov 2026 | ✅ |
| CSV import with your own descriptions | 16-row personal sheet: 16/16 correct suggestions; a taught word carries over to the next month | ✅ |
| Production stack | Both images build from `docker-compose.prod.yml`; db and API start healthy with production settings (migrations, JSON logs, API docs off, sign-up mode). The web, Caddy and backup containers hung at "starting" in **this Mac's Docker Desktop**, even with no file mounts, so HTTPS end to end is **not yet verified** | ⚠️ verify on the server |

`mvn verify` now runs the integration tests too (the Failsafe plugin was missing, so they were silently skipped before).

## Completed (milestone 4c: Excel files)
- **Bank statements and holdings can be Excel**: `.xlsx`, legacy `.xls`, and the HTML "xls" files some Indian banks send. Excel dates become real dates and numbers stay exact, whatever the sheet's display format.
- Statements: the first sheet with a transaction table is used (summary sheets are skipped). Holdings: every sheet with a holdings table is imported (e.g. Zerodha Console's Equity and Mutual Funds sheets) and the results added together.
- Clear messages for password-protected files ("save a copy without the password"), unreadable files and files over 5 MB.
- Samples: `docs/samples/personal-sheet-sample.xlsx` and `docs/samples/holdings-sample.xlsx`.

## Completed (milestone 4b: free hosting)
- **Free deployment path:** Vercel + Render + Neon (`render.yaml`, `.github/workflows/keep-awake.yml`, `deploy/FREE_HOSTING.md`).
- `DATABASE_URL` in the `postgresql://user:pass@host/db?sslmode=require` form that hosted providers give is accepted directly.
- **Fits Render's 512 MB:** with the blueprint's JVM settings, peak memory was about 390 MB during repeated AMFI + NSE/BSE refreshes and a 3,000-row import (measured locally with the same limits).
- **Lets Neon sleep:** pool size, idle connections, idle timeout and the health check's database probe are configurable. The free settings close idle connections and keep uptime pings off the database.
- **CSV import 6× faster:** the import cleared nothing from Hibernate's session, so every query re-checked every earlier row. Flushing every 100 rows took 3,000 rows from 109 s to 17 s.

## Completed (milestone 4)
- **First real build and run.** The backend had never been compiled against Spring. It compiled cleanly; one schema mismatch (SMALLINT vs INT in V4) was fixed, and then all 45 existing integration tests passed.
- **Your descriptions are read as notes.** In a personal sheet with only `Date, Description, Amount, Type`, a description you wrote ("maid salary", "petrol for bike", "medicines for amma") is treated as your note; bank narrations (`UPI/…`, `NEFT-…`, `POS …`) are not. Previously 7 of 16 such rows got no suggestion; now 16 of 16 do.
- **Learning carries across months.** Notes are keyed by their core words: months, years, amounts and filler words are removed, so teaching "chitti payment nov" → Investments also files "Chitti payment Dec 2026". A Java Flyway migration (V5) recomputes existing keys.
- **Fallback on description words.** With no note and an unknown payee, words in the description itself still produce a lower-confidence suggestion.
- **Automatic stock, ETF, SGB and bond prices.** Daily closing prices come from the NSE and BSE public bhavcopy files (no key or account needed), fetched at 7:30 pm and 10:30 pm IST on weekdays and with "Refresh prices". Holdings match by ISIN or symbol (`INFY`, `INFY.NS`, `NSE:INFY`); NSE wins over BSE; a price you typed after the close is kept. Stock search in the Add-investment dialog fills in the symbol, ISIN and price. If the server can't reach the exchanges, you can upload a bhavcopy yourself.
- **Mutual fund NAVs fixed.** AMFI added Plan and Option columns to NAVAll.txt, so every NAV was being read as empty. The parser now reads the NAV and date from the last columns and adds plan and option to the fund name.
- **Ready for the internet:** production Compose file with Caddy (automatic HTTPS), private database and API, API docs off, nightly backups with 14-day retention, sign-up modes (open / invite code / closed) and a cap of 5 sign-ups per network per hour, GitHub Actions CI, and a step-by-step guide (`deploy/DEPLOY.md`).

## Completed (milestone 1)
- **Auth:** registration and login with BCrypt(12), JWT in an httpOnly SameSite=Strict cookie, rate-limited login, audit log.
- **Accounts:** bank, credit card, cash and wallet. Balances computed from the opening balance plus posted, non-duplicate transactions. Archive and restore.
- **Categories:** 31 India-specific defaults with subcategories, copied per user. Create, rename, recolour, nest, archive and merge.
- **Transactions:** search, 10 filters, paging, detail with sources and history, edit (with optimistic locking), tags, notes, manual entry, CSV export with formula-injection guard.
- **Categorisation engine:**
  - Suggestions in precedence order: merchant rule → description rule → your own history → built-in dictionary (~150 Indian merchants).
  - Four modes; the default is ALWAYS_ASK.
  - "Remember" scopes: only this one, merchant, description, apply to history.
  - Bulk confirm, splits (exact-sum validation), mark as transfer (pairs with an existing counterpart or creates one), refunds, exclude, full categorisation history.
- **Needs Review inbox:**
  - Approval slip with keyboard shortcuts.
  - Duplicate resolution (merge / keep both).
  - Transfer hint for credit card bill payments.
  - Filters, bulk accept.
- **Deduplication:** an exact reference match merges sources, including zero-padded statement references. A fuzzy match (same account, amount and direction, within 20 min or ±1 day) is flagged SUSPECTED and excluded from totals until you resolve it. CSV re-imports are idempotent.
- **Integrations framework:** provider catalogue with honest availability. CSV statement import auto-detects HDFC/ICICI/SBI/Axis-style layouts. The SMS and e-mail ingest webhook uses per-integration tokens (stored as SHA-256), rejects OTPs, and maps alerts to accounts by their last digits.
- **Dashboard:**
  - Money-flow summary, balance and outstanding totals, net worth (accounts only), savings rate.
  - Category breakdown with a separate pending bucket.
  - Generated insights, spend by payment app, 6-month in/out, daily trend, top merchants.
- **PWA:** manifest, icons, shell-only service worker (financial data is never cached), light/dark mode.
- **Demo:** an `is_demo` user with about 3 months of synthetic data that flows through the real pipeline, with a "Demo data" banner on every page.

## Completed (milestone 2)
- **Your notes drive categories.** CSV import reads a `Note`/`Purpose` column and a `Category` column. It also reads the note you type in PhonePe/GPay when the bank keeps it in the narration (SBI, HDFC and ICICI layouts).
  - Suggestion order: your note rules → your past choices for the same note → built-in note dictionary (English + Hinglish: sabzi, kiraya, doodh, dawai, bijli, maid…) → payee rules → payee history → merchant dictionary.
  - Notes about lending, card bills and self transfers become hints instead of spending.
  - New "Always when my note says …" rule.
  - Re-importing a statement after adding notes updates the existing rows instead of skipping them. It never overrides a category you've confirmed.
  - Downloadable sample sheet (`frontend/public/templates/`).
- **Net worth:**
  - Holdings: mutual funds, stocks, ETFs, FDs, RDs, PPF, EPF, NPS, bonds, gold, SGB, property, vehicles, crypto, other.
  - Liabilities: home, car, personal, education and gold loans, loans against securities, money borrowed from people, other. Credit card dues come from card accounts.
  - Three valuation methods: market price (units × NAV/price), manual value, or fixed-rate compounding (FD; flagged as an estimate).
  - Mutual fund NAVs: AMFI's public daily file is loaded nightly (23:15 IST) or on demand; fund search; ISIN-to-scheme linking.
  - Broker/MF holdings CSV import (Zerodha Kite/Console, Groww stocks and MF, any similar export), with upserts on re-import.
  - Allocation (equity/debt/hybrid/gold/real estate/cash), daily snapshots (23:45 IST and after every change), trend chart, 30-day and year-to-date change.
- **Investments count as saved, not spent.** A category flag, set on for Investments by default and toggleable per category. The dashboard shows spent vs invested, and the savings rate counts investments as saved.
- The dashboard's net worth now includes investments, property and loans.

## Completed (milestone 3)
- **Delete anything you added by mistake:**
  - Any transaction, manual or imported, from its detail view; or tick several on the Transactions page and delete them together.
  - Deleted imported rows are remembered, so importing the same statement again skips them (Settings → Your data can let them back).
  - **Undo import** in Integrations removes everything one CSV import added, without remembering them, so you can import the right file into the right account.
  - Accounts (with an explicit "also delete its transactions"), categories (move their transactions elsewhere or send them back to Needs Review), investments, loans, budgets, goals and goal entries, recurring items, rules, notifications.
  - Every delete asks for confirmation; deleting one side of a transfer puts the other side back into review.
- **Budgets:** weekly, monthly or yearly (Indian FY), overall or per category (subcategories included), rollover, alert thresholds (default 50/75/90/100, each sent once per period), month-end forecast, daily allowance, six-period history, suggested amount from the last three periods.
- **Subscriptions & bills:** nightly detection of weekly/monthly/quarterly/yearly repeats, shown as suggestions you confirm and classify (subscription, bill, EMI, rent, insurance, SIP, income, other). Manual items, next due date, reminders N days before, overdue warnings, price-change and "charged after you cancelled" alerts, forgotten-subscription nudges after 6 months, monthly and annual cost.
- **Credit cards:** limit, statement day, due days, minimum due %, rewards and fee per card; current cycle, last statement amount, paid since, remaining and minimum due, due state (paid, part paid, due, overdue), utilisation with a 30% warning, payment history, reward estimate. Bill payments stay transfers, so no double counting.
- **Goals:** target and date, already-saved amount, contributions and withdrawals, linked investments (share %), expected return, required monthly amount (annuity maths), projection from your recent pace, on track / behind, milestones at 25/50/75/100%, emergency-fund suggestion of six months' spending.
- **Analytics:** monthly report with income, spending, invested, savings rate, six-month trend, categories against last month and the 3-month average, top merchants, and insights (category spikes, merchant increases, unusual transactions, lifestyle inflation, overspending, savings-rate change, top category), each with its calculation.
- **Assistant:** on-server rules (no outside AI) for spending totals by period, category or merchant, income, top categories and merchants, month comparison, savings rate, investments, net worth, budgets, subscriptions, upcoming bills, card dues, goals, largest expenses, items to review and "can I afford ₹X". Facts are tagged confirmed / estimate / pending with the working shown; anything else gets a list of what it can answer.
- **Reports:** monthly, annual, categories, income, cash flow, transactions, budgets, subscriptions, credit cards and net worth as PDF, Excel or CSV.
- **Notifications:** inbox with unread badge, mark read, delete, clear; hourly checks for budgets, bills, cards, goals, large payments and the review backlog; per-topic preferences; browser pop-ups while Arth is open; e-mail when SMTP is configured.
- **Security and privacy:** optional TOTP two-step sign-in with recovery codes (secret AES-GCM encrypted), password change, full JSON export, account deletion with password and typed confirmation.
- The demo account now includes budgets, confirmed subscriptions, card details and two goals.

## Pending
- **Phase 5:** PDF statements, PhonePe statement import, Gmail OAuth, scheduler-driven sync.
- **Net worth, next:** CAMS/KFintech CAS PDF import, per-purchase lots for XIRR and capital gains, linking SIP debits to fund units.
- **Phase 7:** at-rest encryption of raw alert payloads, Redis-backed rate limiting, monitoring, backups, Playwright end-to-end tests, web push (background notifications when no tab is open).

## Known limitations
- Which UPI app paid is usually unknown from bank alerts. You can set it per transaction; PhonePe statement import is planned.
- Balances, card statements and dues are calculated from imported transactions, not fetched from banks. Missing transactions change them.
- The SMS and e-mail webhooks need Arth reachable from your phone or Google (for example through a tunnel).
- Login and sign-up rate limiting is in-process, so it is per backend instance (fine for one server).
- Bank narrations often cut UPI notes off around 20–30 characters; a Note column in your own sheet is the reliable route.
- Stock prices are end-of-day closes, not live. They update on trading evenings.
- FD values are estimates; PPF, EPF and NPS are values you enter.
- Recurring detection needs about three months of history for monthly payments, and two years for yearly ones.
- Browser notifications only appear while an Arth tab is open; there is no push service yet.
- PDF reports write ₹ as "Rs" (built-in PDF fonts lack the rupee sign). Excel and CSV keep ₹.
- The assistant understands a fixed set of question types in English; it says so instead of guessing.

## Integration status
See `docs/FEASIBILITY.md`. Working now: manual entry, CSV statements, SMS forwarder webhook, Gmail Apps Script webhook. Planned: Gmail API, PhonePe statement. Unavailable: Account Aggregator (needs a regulated FIU), PhonePe API (no consumer API exists).

## Test results (17 November 2026)
- Backend: `TEST_DB_URL=jdbc:postgresql://localhost:5432/arth_test mvn verify`: 93 unit + 50 integration, all passing.
- Unknown URLs, wrong methods and missing parameters now return 404/405/400 instead of 500 (and no longer log stack traces).
- Frontend: `npm run lint`, `npm test` (22 passing) and `npm run build`.

## Next steps
1. Upgrade your local Docker stack: `docker compose up -d --build` (your data migrates V1 → V6 automatically; this was tested on a copy).
2. Deploy for free: follow `deploy/FREE_HOSTING.md` (Vercel + Render + Neon). Or use a VPS with `deploy/DEPLOY.md`.
3. Next features: CAS PDF import (all funds from CAMS/KFintech in one file), XIRR and capital gains from purchase lots, PhonePe statement import.
