# Putting Arth on the internet

Arth runs as four containers on one server: Caddy (HTTPS), the web app, the API and PostgreSQL, plus a nightly
backup job. Only Caddy is reachable from outside. One small Linux VPS is enough for you plus a few hundred users.

## What you need

| | Recommendation | Rough cost |
|---|---|---|
| Server | Ubuntu 24.04, **2 GB RAM**, 1–2 vCPU, 25 GB disk, in Mumbai or Bangalore (DigitalOcean BLR1, AWS Lightsail ap-south-1, Hetzner, Vultr Mumbai…) | ₹500–1,000 a month |
| Domain | Any registrar (e.g. `arth.yourname.in`) | ₹100–800 a year |

1 GB servers are too small: the Java API and the Next.js build need about 1.5 GB between them.

## 1. Server set-up (once)

SSH in and install Docker:

```bash
curl -fsSL https://get.docker.com | sh
```

```bash
sudo usermod -aG docker $USER && newgrp docker
```

Open only SSH and web ports:

```bash
sudo ufw allow OpenSSH && sudo ufw allow 80 && sudo ufw allow 443 && sudo ufw --force enable
```

## 2. Point your domain at the server

At your registrar, add an **A record**: `arth` (or `@`) → the server's public IPv4 address. Wait until
`ping arth.yourname.in` shows that IP. Caddy can't get a certificate before DNS points at the server.

## 3. Copy the code to the server

From your Mac, in the project folder:

```bash
rsync -av --exclude node_modules --exclude .next --exclude target --exclude .env --exclude backups ./ user@SERVER_IP:~/arth/
```

(Or push the project to a private GitHub repository and `git clone` it on the server.)

## 4. Configure

On the server:

```bash
cd ~/arth && cp .env.production.example .env
```

Generate the secrets and paste them into `.env` along with your `DOMAIN` and `ACME_EMAIL`:

```bash
echo "JWT_SECRET=$(openssl rand -base64 48 | tr -d '\n')"; echo "MFA_ENCRYPTION_KEY=$(openssl rand -base64 32)"; echo "DB_PASSWORD=$(openssl rand -hex 24)"
```

```bash
nano .env
```

Keep a copy of `.env` somewhere safe (a password manager). Losing `MFA_ENCRYPTION_KEY` switches off everyone's
two-step sign-in; losing `DB_PASSWORD` locks you out of the database volume's contents.

**Who can sign up** (`REGISTRATION_MODE`):
- `open`: anyone with the link (capped at 5 new accounts per network per hour).
- `invite`: people need `REGISTRATION_INVITE_CODE`. Good for family and friends.
- `closed`: no new accounts. Create yours first, then switch to `closed` and restart.

## 5. Start

```bash
docker compose -f docker-compose.prod.yml up -d --build
```

The first build takes 5–10 minutes. Then open `https://your-domain`, register, and you're live. Check status with:

```bash
docker compose -f docker-compose.prod.yml ps
```

```bash
docker compose -f docker-compose.prod.yml logs -f backend
```

## Updating to a new version

Copy the new code over (step 3), then:

```bash
docker compose -f docker-compose.prod.yml up -d --build
```

Database migrations run automatically on start. Take a backup first (below) if the update is large.

## Backups

The `backup` container writes a compressed dump to `~/arth/backups/` every 24 hours and keeps 14 days.
**Also copy them off the server**, because a backup on the same disk doesn't survive losing the server. From your Mac:

```bash
rsync -av user@SERVER_IP:~/arth/backups/ ~/arth-backups/
```

Make one right now:

```bash
docker compose -f docker-compose.prod.yml exec backup sh -c 'pg_dump -h db -U arth -d arth -Fc -f /backups/manual-$(date +%F).dump'
```

Restore a dump (this **replaces** the current data):

```bash
docker compose -f docker-compose.prod.yml exec -T db pg_restore -U arth -d arth --clean --if-exists < backups/arth-YYYY-MM-DD_HHMM.dump
```

## Moving your existing local data to the server

Your Mac's Docker Compose stack keeps its data in the `arth_arth-db` volume. To carry it over:

```bash
docker compose exec -T db pg_dump -U arth -d arth -Fc > arth-local.dump
```

```bash
scp arth-local.dump user@SERVER_IP:~/arth/backups/
```

Start the production stack once (so the database exists), then on the server:

```bash
docker compose -f docker-compose.prod.yml stop backend && docker compose -f docker-compose.prod.yml exec -T db pg_restore -U arth -d arth --clean --if-exists < backups/arth-local.dump && docker compose -f docker-compose.prod.yml start backend
```

The backend upgrades the restored database to the latest schema when it starts. Your password and data come with it.
Delete the demo user afterwards if your local stack had demo mode on.

## Trying the production stack on your Mac

```bash
DOMAIN=localhost HTTP_PORT=8180 HTTPS_PORT=8543 docker compose -f docker-compose.prod.yml --env-file .env.production.local up -d --build
```

Then open https://localhost:8543 and accept the self-signed certificate warning.

## Security checklist

- [x] HTTPS only, HSTS, secure SameSite=Strict session cookie
- [x] Database and API not exposed to the internet; API docs switched off
- [x] Demo mode off; sign-up mode chosen deliberately
- [x] Nightly backups; copy them off the server
- [ ] Turn on your VPS provider's automatic snapshots too (cheap extra safety)
- [ ] Turn on two-step sign-in for your own account (Settings → Security)
- [ ] Keep the server patched: `sudo apt update && sudo apt upgrade` monthly

## If something goes wrong

| Symptom | Fix |
|---|---|
| Browser says the certificate is invalid | DNS isn't pointing at the server yet, or ports 80/443 are blocked. `docker compose -f docker-compose.prod.yml logs caddy` |
| 502 Bad Gateway | The API is still starting (about 30 s) or crashed: `logs backend` |
| Build killed / exits 137 | Out of memory: use a 2 GB server, or add swap: `sudo fallocate -l 2G /swapfile && sudo chmod 600 /swapfile && sudo mkswap /swapfile && sudo swapon /swapfile` |
| Stock prices never update | The server can't reach nseindia.com / bseindia.com. Download the bhavcopy yourself and upload it under Net Worth |
