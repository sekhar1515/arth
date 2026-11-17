# Free hosting: Vercel + Render + Neon

Cost: ₹0. No credit card needed for any of the three (as of November 2026).

```
Browser ──HTTPS──▶ Vercel (web app, free) ──/api──▶ Render (API, free, Singapore) ──▶ Neon (PostgreSQL, free, Singapore)
                                                          ▲
                              GitHub Actions ping every 10 min (keeps it awake)
```

| Piece | Free allowance | What it means for you |
|---|---|---|
| **Vercel** Hobby | Generous bandwidth; proxied requests time out after 120 s | Fast pages from anywhere. Hobby is for personal, non-commercial use. |
| **Render** free web service | 512 MB RAM, 0.1 CPU, 750 hours a month, sleeps after 15 min idle | Arth is tuned to fit (about 390 MB at peak). The keep-awake ping stops it sleeping. |
| **Neon** free Postgres | 1 GB storage, 100 compute-hours a month, sleeps after 5 min idle | Room for years of transactions for a few users. Arth lets it sleep when nobody is using it. |

Limits change. Check each provider's pricing page before relying on them.

## 0. Put the code on GitHub (needed by Render and Vercel)

Create an empty **private** repository on github.com (e.g. `arth`), then in the project folder:

```bash
git init && git add . && git commit -m "Arth"
```

```bash
git branch -M main && git remote add origin https://github.com/YOUR_NAME/arth.git && git push -u origin main
```

`.gitignore` already keeps `.env`, `node_modules` and build output out of the repository.

## 1. Database: Neon

1. Sign up at **neon.com** (GitHub login works).
2. **Create project** → name `arth`, Postgres 16 or newer, region **AWS Asia Pacific (Singapore)**.
3. On the project dashboard click **Connect**, turn **Connection pooling off**, and copy the connection string. It looks like
   `postgresql://neondb_owner:xxxx@ep-something-123456.ap-southeast-1.aws.neon.tech/neondb?sslmode=require`

Keep it private: it's the key to your data.

## 2. API: Render

1. Sign up at **render.com** with GitHub.
2. **New → Blueprint** → choose your `arth` repository. Render reads `render.yaml` and proposes the `arth-api` service.
3. Fill in the prompted values:
   - `DATABASE_URL`: the Neon string from step 1.
   - `PUBLIC_URL`: leave blank for now (fill in after step 3).
   - `REGISTRATION_INVITE_CODE`: only if you choose invite mode.
4. **Apply**. The first build takes 5–10 minutes. When it says *Live*, open `https://arth-api.onrender.com/actuator/health` (your URL may differ). It should show `{"status":"UP"}`.

The database tables are created automatically on first start.

## 3. Web app: Vercel

1. Sign up at **vercel.com** with GitHub.
2. **Add New → Project** → import `arth`.
3. **Root Directory**: `frontend`. The framework is detected as Next.js.
4. **Environment Variables**: add `API_ORIGIN` = your Render URL, e.g. `https://arth-api.onrender.com` (no trailing slash).
5. **Deploy**. You get `https://arth-xxxx.vercel.app`. Open it and create your account.
6. Back in Render → arth-api → Environment, set `PUBLIC_URL` to that Vercel address.

If you change `API_ORIGIN` later, redeploy on Vercel: it's read at build time.

## 4. Keep the API awake

In GitHub → your repo → **Settings → Secrets and variables → Actions → Variables → New repository variable**:
`ARTH_API_URL` = your Render URL. The `Keep API awake` workflow then pings it every 10 minutes. Check the
**Actions** tab after 10 minutes to see it working. GitHub sometimes delays scheduled runs, so after a quiet night the first page may take up to a minute.

Without the ping, Arth still works, but the first visit after 15 idle minutes waits about a minute while the API
wakes, and the evening stock-price refresh and hourly alerts only run while it's awake.

## 5. Bring your existing data (optional)

Your Mac's Docker database can be copied into Neon. In the project folder, with your local stack running:

```bash
docker compose exec -T db pg_dump -U arth -d arth --no-owner --no-acl -Fc > arth-local.dump
```

Then, **before** the first Render deploy (or after deleting Neon's tables), restore into Neon:

```bash
pg_restore --no-owner --no-acl -d "postgresql://...your Neon string..." arth-local.dump
```

Render upgrades the restored database to the latest version when it starts. If Render already created tables,
reset the database first in Neon (SQL editor: `DROP SCHEMA public CASCADE; CREATE SCHEMA public;`) and redeploy after restoring.

## Your own domain (optional)

Vercel → Project → Settings → **Domains** → add `arth.yourname.in` and create the DNS record it shows. HTTPS is automatic.
Update `PUBLIC_URL` on Render to match.

## Backups on the free plan

Neon keeps a short restore window on the free plan; don't treat it as a backup. Once a month, either:
- Arth → **Settings → Your data → Export** (full JSON), or
- from your Mac: `pg_dump "postgresql://...your Neon string..." -Fc -f arth-$(date +%F).dump`

## Good to know

- **Big imports:** a free API has a tiny CPU share. Import statements a few months at a time; a single file with thousands of rows can exceed Vercel's 120-second proxy limit. Re-importing a file is always safe: rows already there are skipped.
- **Updating:** push to `main` and both Render and Vercel redeploy automatically.
- **Outgrowing free:** move the API to Render's Starter plan (no sleep, more CPU), or the whole stack to one VPS with `docker-compose.prod.yml` (see `DEPLOY.md`).
- **E-mail notifications:** add `SPRING_MAIL_HOST`, `SPRING_MAIL_USERNAME`, `SPRING_MAIL_PASSWORD`, `MAIL_FROM` on Render (e.g. Brevo's free SMTP).

## Troubleshooting

| Symptom | Fix |
|---|---|
| Vercel shows "Couldn't reach the server" | Render is asleep or still deploying: open the health URL, wait a minute, retry. Check `API_ORIGIN` has no trailing slash and starts with `https://`. |
| Render deploy fails with "Exited with status 137" | Out of memory. Make sure `JAVA_TOOL_OPTIONS` from `render.yaml` is set. |
| Render logs: `password authentication failed` or `SSL` errors | Re-copy the Neon string; it must end with `?sslmode=require`. |
| Signed in, then immediately signed out | `COOKIE_SECURE` must be `true` and you must use the `https://` Vercel address. |
| Neon says compute hours are used up | Something kept the database awake; check `HEALTH_CHECK_DB=false` and `DB_POOL_MIN_IDLE=0` on Render. |
