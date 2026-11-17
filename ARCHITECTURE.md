# Arth — Architecture

Arth is a personal finance platform for the Indian ecosystem. It collects transactions from bank alerts and statements, asks you to categorise each new one, learns from your answers, and reports where your money goes.

## 1. System overview

```
 ┌──────────────┐  SMS forwarder / Apps Script   ┌──────────────────────────────────────────┐
 │ Bank alerts  │ ─────── POST /api/ingest ─────▶│                                          │
 └──────────────┘    (X-Ingest-Token)            │   Spring Boot modular monolith (Java 21) │
 ┌──────────────┐                                │                                          │
 │ Statements   │ ─── POST /api/imports/csv ────▶│  integration → ingestion pipeline        │
 └──────────────┘                                │     parse → normalise → dedup → suggest  │
 ┌──────────────┐  same-origin /api (rewrites)   │  transaction · categorisation · merchant │
 │ Next.js PWA  │ ◀────────── JSON ─────────────▶│  account · analytics · auth · audit      │
 └──────────────┘   httpOnly SameSite cookie     └───────────────┬──────────────────────────┘
                                                                 │ JPA / Flyway
                                                          ┌──────▼──────┐
                                                          │ PostgreSQL  │
                                                          └─────────────┘
```

- **Modular monolith.** Each package under `com.expensetracker` is a domain module with its own entities, repositories, services and controllers. A module talks to another only through that module's service classes, never its repositories. That keeps later extraction possible.
- **One ingestion pipeline.** Every source (manual, CSV, SMS, email, demo, future AA) produces a `RawTransaction` and calls `IngestionService.ingest()`. The core engine never knows which provider sent the data. New providers implement `IntegrationProvider` and register as Spring beans.
- **Redis is not used yet.** Rate limiting and caches are in-process for a single-user deployment. Redis gets added when there's more than one backend replica (ADR-6).

## 2. Architectural decisions (ADRs)

| # | Decision | Why |
|---|---|---|
| 1 | Categories are **one self-referencing table** (`categories.parent_id`), not separate category/subcategory tables | Supports arbitrary nesting, merging and archiving with one code path. "Subcategory" = a category with a parent |
| 2 | Money is `NUMERIC(19,2)` in Postgres and `BigDecimal` in Java. Amounts are stored **positive**, with an explicit `direction` (DEBIT/CREDIT) | Prevents sign bugs. No float types anywhere |
| 3 | A transaction has a **direction** (what happened to the account) and a **kind** (EXPENSE, INCOME, TRANSFER, REFUND, EXCLUDED), kept separately | Transfers and refunds keep moving balances without counting as income or expense |
| 4 | An **internal transfer is two transaction rows** sharing a `transfer_group_id` | Each account's balance is just the sum of its own rows; reconciliation stays simple |
| 5 | Dedup: an **exact reference-number match merges** (the new source is attached to the existing row). A **fuzzy match** (same account, amount and direction, within the time tolerance) is inserted as `SUSPECTED` and **excluded from totals** until you resolve it | Never double-counts, and never silently drops a real transaction |
| 6 | Auth uses a JWT in an **httpOnly, SameSite=Strict cookie**. Next.js proxies `/api`, so the browser is same-origin | No tokens in JS-readable storage; CSRF is blocked by SameSite |
| 7 | **Default categorisation mode is ALWAYS_ASK.** The suggester runs anyway so every review card has a suggestion | The brief requires you to approve categories by default |
| 8 | Suggestion precedence: your merchant rule → your description rule → your own history for that merchant → built-in Indian merchant dictionary | Your rules always beat generic predictions |
| 9 | Uncategorised transactions **count in cash-flow totals** but **not in category analytics**. They're shown as a "Pending categorisation" bucket | Reporting requirement in the brief |
| 10 | Default categories are **copied per user** at registration | You can rename, recolour, archive or merge them freely |
| 11a | **Notes outrank payees** in suggestions (note rule 0.98 → note history → note dictionary 0.92 → merchant rule 0.99 → …). Every transaction stores a normalised `note_key` | One payee (a person, a kirana) gets paid for different things; what you wrote is the strongest signal. The key makes learning a simple indexed lookup |
| 11b | Re-importing a statement *annotates*: a row seen before that now carries a note or category updates the existing transaction | Lets you download, annotate and re-upload without creating duplicates |
| 11c | Investment categories (`categories.is_investment`) are **saved, not spent** | A SIP isn't consumption. The savings rate and spending breakdown would be wrong otherwise |
| 11d | Holdings use one table with a `valuation_method` (MARKET_PRICE / MANUAL / FIXED_RATE). Each value carries its source (AMFI, broker export, manual, estimated) | Covers every Indian asset class without a table per type. The UI can always say where a number came from |
| 11e | Mutual fund NAVs come from AMFI's public NAVAll.txt into an `mf_schemes` cache. Net-worth snapshots are kept one per day | Official, free, no API key. Snapshots make the trend line cheap |
| 11 | The assistant is a **deterministic, on-server intent parser** (`assistant/IntentParser`) that answers from the same services as the UI. Every answer lists its facts tagged CONFIRMED / ESTIMATE / PENDING and the calculation steps. Unknown questions get HELP, never a guess. No external LLM is called; `external_ai_consent` stays as a recorded opt-in for any future one | Privacy requirement, and "never invent numbers" |
| 12 | **Anything can be deleted.** Deleting an imported transaction writes a tombstone (`deleted_imports`: user, source type, external id) so re-importing the same statement skips it; "Undo import" (by `transaction_sources.sync_id`) deletes only rows that came solely from that import and leaves no tombstones. Category delete either moves everything to another category or sends affected transactions (and any split that touched it) back to review. Account delete requires explicit `withTransactions=true` when it has history | Mistakes must be fixable without leaving ghosts that come back on the next import |
| 13 | Budgets, recurring detection, card cycles, goals, insights, report writers and TOTP are **pure Java classes** with no Spring dependency; services only gather data | They are unit-tested without a database and their numbers can be explained |
| 14 | Budget periods: weeks start Monday, years are the **Indian financial year (Apr–Mar)**. Rollover carries one period's leftover or overspend. Category budgets count confirmed lines of the category and its children; an overall budget also counts uncategorised spending and excludes investments | Matches how people in India think about money and keeps budgets consistent with the dashboard |
| 15 | Recurring payments are **detected as suggestions only** (3 regular gaps for weekly/monthly/quarterly, 2 for yearly; ≥75% of gaps in band). The user confirms and sets the kind. Steady amounts with one step change = price change; scattered amounts = bill with varying amount | "Not every repeat is a subscription" |
| 16 | Credit-card statement, minimum due and utilisation are **computed from the card account's transactions** plus terms the user enters (limit, statement day, due days, min %) | No card data access exists; transparent maths the user can check against the bank statement |
| 17 | Notifications are rows with a per-user **dedupe key** (`INSERT … ON CONFLICT DO NOTHING`), produced by an hourly idempotent job. E-mail is sent only if SMTP is configured and the user opted in; browser pop-ups use the Notification API while a tab is open (no push server) | Reminders never repeat; nothing pretends to be a push service |
| 18 | Reports are a neutral `ReportTable` written by **dependency-free CSV, XLSX (OOXML) and PDF writers** | Fewer libraries to trust and patch; PDF uses built-in Helvetica, so ₹ is written "Rs" |
| 19 | Optional TOTP two-step sign-in (RFC 6238, ±1 step). The seed is AES-256-GCM encrypted with `MFA_ENCRYPTION_KEY`; 10 one-time recovery codes are stored only as SHA-256 hashes | Standard, works with any authenticator app, nothing reusable stored in clear |

## 3. Database design (Flyway `V1__core_schema.sql`)

```
users ─┬─< financial_accounts ─< transactions >─ categories (self-ref parent_id)
       ├─ user_preferences            │  │  └─< transaction_splits >─ categories
       ├─< categories                 │  ├─< transaction_sources
       ├─< merchants ─────────────────┘  ├─< categorisation_history
       ├─< merchant_rules >─ categories  └─< transaction_tags >─ tags
       ├─< account_integrations ─< integration_consents
       │                        └─< sync_history
       └─< audit_log
```

Key constraints:
- Every user-owned table has `user_id`. Every repository query filters by the authenticated user ID (user-level isolation, tested).
- `transactions.amount > 0` is a CHECK constraint, and so are the enumerated `direction`, `kind`, `status`, `categorisation_status` and `duplicate_status` values.
- Unique `(user_id, source_type, external_id)` on `transaction_sources` makes re-imports idempotent.
- Indexes: `(user_id, txn_at DESC)`, `(user_id, categorisation_status)`, `(user_id, reference_number)`, `(account_id, amount, txn_at)` (fuzzy dedup), `(user_id, merchant_id)`.
- Phase 6 tables (budgets, budget_allocations, recurring_payments, subscriptions, credit_cards, credit_card_bills, financial_goals, assets, liabilities, notifications) arrive in later migrations, so the schema matches what is actually implemented.

## 4. API design (all under `/api`, JSON, OpenAPI at `/swagger-ui.html`)

| Area | Endpoints |
|---|---|
| Auth | `POST /auth/register`, `POST /auth/login`, `POST /auth/logout`, `GET /auth/me` |
| Preferences | `GET/PUT /preferences` (categorisation mode, theme) |
| Accounts | `GET/POST /accounts`, `PUT /accounts/{id}`, `POST /accounts/{id}/archive` (balances are computed) |
| Categories | `GET /categories` (tree), `POST /categories`, `PUT /categories/{id}`, `POST /categories/{id}/archive`, `POST /categories/{id}/merge-into/{targetId}` |
| Transactions | `GET /transactions` (search, filters, paging, sort), `GET /transactions/{id}`, `POST /transactions` (manual), `PUT /transactions/{id}`, `GET /transactions/export.csv`, `GET /transactions/{id}/history` |
| Categorisation | `POST /transactions/{id}/categorise` `{categoryId, remember, note, business}` · `PUT /transactions/{id}/splits` · `POST /transactions/{id}/mark-transfer` · `POST /transactions/{id}/exclude` · `POST /transactions/{id}/mark-refund` |
| Review | `GET /review` (filters), `GET /review/count`, `POST /review/bulk-confirm` · `POST /review/duplicates/{id}/resolve` `{action: KEEP_BOTH \| MERGE}` |
| Rules | `GET /rules`, `DELETE /rules/{id}` |
| Dashboard | `GET /dashboard/summary?from&to` · `GET /dashboard/trends?months=6` · `GET /dashboard/daily?from&to` |
| Net worth | `GET /networth` · `GET /networth/history` · `GET/POST /networth/holdings`, `PUT/DELETE /networth/holdings/{id}`, `POST /networth/holdings/{id}/price`, `POST /networth/holdings/{id}/archive`, `POST /networth/holdings/import` (multipart) · `GET/POST /networth/liabilities`, `PUT/DELETE /networth/liabilities/{id}` · `GET /networth/mf-schemes?q=` · `POST /networth/prices/refresh` |
| Integrations | `GET /integrations` (catalogue + status), `POST /integrations/{provider}/connect` (returns the ingest token once), `POST /integrations/{id}/disconnect`, `POST /integrations/{id}/rotate-token`, `GET /integrations/{id}/history` |
| Imports | `POST /imports/csv?accountId=` (multipart) → `{imported, merged, suspected, skipped, errors[]}` |
| Ingest | `POST /ingest/{SMS\|EMAIL}` header `X-Ingest-Token`, body `{text, receivedAt, sender}` |
| Deletion | `DELETE /transactions/{id}` · `POST /transactions/bulk-delete {ids}` · `POST /imports/{syncId}/undo` · `DELETE /accounts/{id}?withTransactions=` · `DELETE /categories/{id}?moveTo=` · holdings, loans, budgets, goals, goal entries, recurring items, rules and notifications all have `DELETE` |
| Budgets | `GET /budgets` · `GET /budgets/suggest?categoryId&period` · `POST /budgets` · `PUT/DELETE /budgets/{id}` · `POST /budgets/{id}/archive\|restore` |
| Recurring | `GET /recurring` · `POST /recurring/detect` · `POST /recurring` · `PUT/DELETE /recurring/{id}` · `POST /recurring/{id}/confirm\|dismiss\|cancel\|reviewed\|paid` |
| Credit cards | `GET /credit-cards` · `GET/PUT /credit-cards/{accountId}` · `DELETE /credit-cards/{accountId}/details` |
| Goals | `GET/POST /goals` · `PUT/DELETE /goals/{id}` · `POST /goals/{id}/contributions`, `DELETE …/contributions/{cid}` · `POST /goals/{id}/holdings`, `DELETE …/holdings/{hid}` · `GET /goals/emergency-fund-suggestion` |
| Analytics | `GET /analytics/report?month=YYYY-MM` |
| Assistant | `POST /assistant/ask {question}` · `GET /assistant/examples` |
| Reports | `GET /reports/download?type=MONTHLY\|ANNUAL\|CATEGORIES\|INCOME\|CASH_FLOW\|TRANSACTIONS\|BUDGETS\|SUBSCRIPTIONS\|CREDIT_CARDS\|NET_WORTH&format=PDF\|XLSX\|CSV&from&to` |
| Notifications | `GET /notifications?unread` · `GET /notifications/unread-count` · `POST /notifications/{id}/read`, `/read-all`, `/check` · `DELETE /notifications/{id}`, `/read` · `GET/PUT /notifications/preferences` |
| Security & privacy | `POST /auth/password` · `GET /auth/mfa` · `POST /auth/mfa/setup\|enable\|disable` · login accepts `code` and answers `MFA_REQUIRED` · `GET /privacy/export` (JSON) · `GET/DELETE /privacy/deleted-imports` · `POST /privacy/delete-account {password, confirmation:"DELETE"}` |

Errors use one envelope: `{error: {code, message, details[]}, timestamp, path}` (from `GlobalExceptionHandler`).

## 5. Frontend architecture

```
frontend/src
  app/(auth)/login, register        app/(app)/dashboard, transactions, review, accounts,
                                         categories, integrations, settings, networth, budgets,
                                         subscriptions, credit-cards, goals, analytics, assistant,
                                         reports, notifications
  components/ui/*        shadcn-style primitives (button, card, dialog, select, badge, input…)
  components/finance/*   DashboardSummaryCard, IncomeExpenseChart, CategoryBreakdown,
                         TransactionTable, TransactionCard, TransactionApprovalCard,
                         CategorySelector, AccountCard, FinancialInsightCard, DateRangePicker
  hooks/*                TanStack Query hooks per resource (useTransactions, useReview…)
  lib/api.ts             typed fetch client · lib/format.ts INR formatting (en-IN grouping)
  types/*                DTO types mirroring the backend
```

- Mobile first. The review inbox shows stacked approval cards on phones and a table with bulk select on desktop.
- Theme: light and dark via the `class` strategy, persisted in user preferences.
- PWA: manifest, icons, and a service worker that caches the app shell only. Financial API responses are **never cached** offline.
- Loading, empty and error states on every query-driven view.
- When the logged-in user is the demo user, a "Demo data" banner shows on every page.

## 6. Roadmap

| Phase | Scope | Status |
|---|---|---|
| 1 Feasibility | `docs/FEASIBILITY.md` | ✅ |
| 2 Architecture | this document | ✅ |
| 3 Core app | auth, accounts, transactions, categories, dashboard, search, manual entry | ✅ milestone 1 |
| 4 Categorisation | suggestions, approval workflow, Needs Review, merchant rules, learning, splits, modes | ✅ milestone 1 |
| 5 Integrations | ✅ CSV + SMS/email webhook · ⏳ XLS/PDF statements, PhonePe statement, Gmail OAuth, scheduler-driven sync | partial |
| 5b Notes | note-driven categorisation, Note/Category CSV columns, annotate-on-reimport | ✅ milestone 2 |
| 6a Net worth | holdings, liabilities, AMFI NAVs, broker CSV import, allocation, snapshots | ✅ milestone 2 |
| 6 Advanced | budgets, recurring/subscriptions, credit cards, goals, analytics, assistant, reports, notifications | ✅ milestone 3 |
| 7 Security & ops | ✅ TOTP MFA, data export, account deletion, deletion everywhere · ⏳ Redis rate limiting, encrypted raw payloads, monitoring, backups, Playwright E2E | partial |
| 7 Production | security review, perf, monitoring, backups | ⏳ |

## Decisions added in milestone 4

- **Exchange prices from bhavcopies, not a quote API.** NSE and BSE publish a free end-of-day file in the same UDiFF layout. One download a day prices every listed holding (stocks, ETFs, SGBs, bonds) for every user. No API keys, scraping or terms-of-service risk. Cached in `security_prices` (keyed by ISIN; NSE replaces BSE on the same day). Live intraday prices are deliberately out of scope.
- **Price precedence:** exchange close or AMFI NAV, unless the holding has a newer price you typed or imported, which is kept until a newer close arrives.
- **Hand-written descriptions become notes** (`CsvStatementParser.looksHandWritten`): short, word-like text with no channel codes, slashes, VPAs or long numbers. Bank narrations stay narrations.
- **Note core keys** (`NoteDictionary.coreKey`) drop months, years, amounts and filler words, so learning generalises across months. Rule matching and the built-in dictionary still read the whole note, because filler words matter for hints like "paid back" and "to self".
- **Java Flyway migrations** (`src/main/java/db/migration`) are used when a data fix must reuse application logic, as V5 does for note keys.
- **Sign-up policy** (`RegistrationPolicy`): open / invite / closed, plus a per-address hourly cap. Client addresses come from `X-Forwarded-For`, which Caddy overwrites at the edge, so clients can't spoof it.
- **Production topology:** Caddy (TLS, HSTS) → Next.js (pages and the `/api` proxy) → Spring Boot → PostgreSQL. Only Caddy publishes ports. The browser only ever talks to one origin, so the session cookie stays SameSite=Strict.
