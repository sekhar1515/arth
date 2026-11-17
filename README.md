# Arth: personal finance for India

Arth collects your transactions from bank SMS/e-mail alerts and statements. It asks you to categorise each new one, learns from your answers, and shows where your money goes. PhonePe, Google Pay and Paytm spends are covered through your bank's alerts. No consumer UPI-app API exists; see `docs/FEASIBILITY.md`.

- `backend/`: Java 21, Spring Boot 3.5, Spring Security, JPA, Flyway, PostgreSQL
- `frontend/`: Next.js 15, React 19, TypeScript, Tailwind 4, TanStack Query, Recharts (PWA)
- `ARCHITECTURE.md`: design decisions, schema, API
- `PROJECT_STATUS.md`: what's done, what's verified, what's next

## Run it

```bash
cp .env.example .env
# put a secret in .env:  JWT_SECRET=$(openssl rand -base64 48)
docker compose up --build
```

- App: http://localhost:3000. With `DEMO_ENABLED=true` the login page offers a clearly labelled demo account with synthetic data.
- API docs: http://localhost:8080/swagger-ui.html

Set `DEMO_ENABLED=false` before using real data.

## Deploy it for other people

- **Free:** Vercel (web) + Render (API) + Neon (database). See [`deploy/FREE_HOSTING.md`](deploy/FREE_HOSTING.md). `render.yaml` and a keep-awake workflow are included.
- **Your own server:** see [`deploy/DEPLOY.md`](deploy/DEPLOY.md): one 2 GB VPS, a domain, and `docker compose -f docker-compose.prod.yml up -d --build`. You get automatic HTTPS, a private database and nightly backups. Choose who can sign up with `REGISTRATION_MODE` (open, invite or closed).

## Develop without Docker for the apps

```bash
docker compose up -d db
cd backend && JWT_SECRET=$(openssl rand -base64 48) COOKIE_SECURE=false DEMO_ENABLED=true mvn spring-boot:run
cd frontend && npm install && npm run dev        # proxies /api to http://localhost:8080
```

## Tests

```bash
cd backend && mvn verify          # unit + Spring integration tests (Testcontainers, needs Docker)
cd backend && TEST_DB_URL=jdbc:postgresql://localhost:5432/arth_test mvn verify   # same, against an existing PostgreSQL
cd frontend && npm test           # Vitest component tests
cd frontend && npm run lint       # type check
```

## Getting transactions in
1. **Accounts:** add each bank account and card with its last 4 digits (alerts are matched on them).
2. **Statements:** Integrations → Bank statement (CSV). A sample is in `docs/samples/`.
   - Keeping your own sheet? `Date, Description, Amount, Type` is enough. Write what each payment was for in Description ("maid salary", "petrol for bike") and Arth reads it as your note.
   - Or add a **Note** column next to the bank's narration, saying what each payment was for ("sabzi", "rent nov", "petrol"). Arth files payments from it and learns from your confirmations.
   - Add a **Category** column to file rows directly. Template: `frontend/public/templates/arth-transactions-template.csv`.
   - Annotated an already-imported statement? Import it again: rows get updated, not duplicated.
3. **Real-time alerts:** Integrations → connect *Bank SMS alerts* or *Bank e-mail alerts* and follow `docs/integrations/gmail-apps-script.md`.

## Net worth
Net Worth → add mutual funds (search by name; NAVs refresh nightly from AMFI), stocks, ETFs and SGBs (search by symbol; closing prices refresh every trading evening from NSE/BSE), FDs, PPF/EPF/NPS, gold, property and loans. You can also import a holdings CSV from Zerodha, Groww and similar apps; re-import monthly to keep it current. Bank, cash and card balances come from your accounts automatically.

## Planning and insight
- **Budgets:** weekly, monthly or yearly (April–March), overall or per category, optional rollover, alerts at 50/75/90/100%, forecast and suggested amount.
- **Subscriptions & bills:** Arth scans your history nightly for repeating payments and asks you to confirm them. Add rent, EMIs or anything else by hand. Price changes, charges after cancelling and due dates become notifications.
- **Credit cards:** add the limit and statement day once (Credit Cards page) to see statement amount, due date, minimum due and utilisation.
- **Goals:** target, date, contributions or a linked investment, required monthly amount and projected date.
- **Analytics, assistant and reports:** monthly report with explained insights; an on-server assistant that answers questions like "how much did I spend on food last month?" and shows its working; PDF, Excel and CSV reports.

## Fixing mistakes
Everything you add can be deleted: transactions (one, many, or a whole import via *Undo* in Integrations), accounts, categories, investments, loans, budgets, goals and their entries, recurring items, rules and notifications. Deleted imported rows stay deleted when you re-import; Settings → Your data lets them come back.

## Notifications by e-mail (optional)
In-app notifications always work. For e-mail, set `SPRING_MAIL_HOST`, `SPRING_MAIL_PORT`, `SPRING_MAIL_USERNAME`, `SPRING_MAIL_PASSWORD` and `MAIL_FROM` in `.env` (any SMTP provider), then turn e-mail on in Settings.

## Security
Optional two-step sign-in (any authenticator app) in Settings; set `MFA_ENCRYPTION_KEY` in `.env` first. Settings also has password change, a full JSON export and account deletion.

Arth never asks for or stores UPI PINs, bank passwords, OTPs or card numbers.
