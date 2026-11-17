# Integration Feasibility Report — India (November 2026)

This report covers which ways of collecting transactions automatically are actually available to a **personal finance app run by one person (or a small unregulated company)** in India. It also says what Arth builds for each one.

**Summary:** No legitimate API gives an individual developer their own PhonePe or Google Pay history. The Account Aggregator network is only open to regulated entities. The routes that work today all capture the **bank-side record** of each payment, because every UPI payment from any app debits a bank account and the bank sends an alert. Those routes are bank SMS alerts, bank email alerts and statement files.

## Feasibility matrix

| Source | Available? | Commercial partnership | Regulatory approval | User consent | Fit for personal app | Data | Sync frequency | Arth status |
|---|---|---|---|---|---|---|---|---|
| **PhonePe official APIs** | Merchant/PG APIs only. Their "transaction list" APIs return payments *received by a merchant*, not a consumer's history | Merchant onboarding | — | — | **No.** The brief rules out using merchant APIs as a substitute | — | — | Adapter registered as **Unavailable**, with the reason shown in the UI |
| **Google Pay / Paytm / BHIM consumer history** | No public consumer-history API | — | — | — | No | — | — | Covered indirectly through bank alerts |
| **Account Aggregator (Setu, Finvu, OneMoney, Anumati…)** | Yes, but only for FIUs. An FIU must be regulated by RBI, SEBI, IRDAI or PFRDA | TSP contract (e.g. Setu) | **Yes**: you must be a regulated entity | Yes, via a consent artefact on the AA app | **Not for an unregulated personal app.** Possible later if you partner with a regulated FIU | Bank transactions, balances, deposits, MF, insurance | Periodic fetch, depending on the consent | Adapter interface + **Unavailable** status; sandbox-ready later |
| **Gmail API (bank alert emails)** | Yes | No | No | OAuth | **Partly.** `gmail.readonly` is a *restricted* scope. A public app needs Google verification and an annual CASA security assessment. An app in "Testing" mode for your own account avoids that, but refresh tokens expire after 7 days | Bank or card alert email bodies | Polling or Pub/Sub push | **Phase 5.** For now, a no-verification alternative works (see below) |
| **Gmail → Apps Script → Arth webhook** | Yes | No | No | You authorise your own script | **Yes.** It runs in your own Google account, so there is no third-party verification | Alert text | Every 5–15 min (time trigger) | **Working now** via the ingest webhook (`EMAIL` source) |
| **Android SMS (Play Store app)** | READ_SMS is restricted to default SMS handlers. Finance apps are routinely refused exceptions | — | Play policy review | Runtime permission | **No** for Play distribution | Bank SMS | Real-time | — |
| **Android SMS forwarding (your own phone)** | Yes, with an SMS-forwarder or automation app you install yourself (Tasker, MacroDroid, open-source forwarders) that POSTs matching SMS to a webhook | No | No | You configure it on your device | **Yes**, for personal use | Bank or card SMS | Real-time | **Working now** via the ingest webhook (`SMS` source) |
| **PWA reading SMS** | Not possible. Browsers have no SMS-inbox API | — | — | — | No | — | — | — |
| **Bank statement import (CSV/XLS/PDF)** | Yes, every bank | No | No | You download it | **Yes** | Complete, authoritative history with balances | Manual (monthly) | **CSV working now**, with auto-detected HDFC/ICICI/SBI/Axis-style headers. XLS/PDF in Phase 5 |
| **Credit card statements** | Yes (PDF or email) | No | No | — | Yes | Card transactions, dues | Monthly | Phase 6 |
| **PhonePe / GPay in-app statements** | PhonePe lets you export a statement from its history screen (availability varies by app version) | No | No | You export it | Yes, for tagging *which app* was used | App-level history | Manual | Phase 5 importer. It reuses dedup to enrich bank records with the payment app |

## Recommended strategy (in priority order)

1. **Bank alerts via the ingest webhook** (SMS forwarder on Android, plus a Gmail Apps Script). This is near-real-time, legitimate and free, and it already covers PhonePe/GPay/Paytm spends because the bank alerts on every debit.
2. **Monthly bank statement import** as the authoritative reconciliation pass. Reference-number and fuzzy dedup merge it with the alerts, so nothing is counted twice.
3. **PhonePe statement import** (Phase 5) to tag *which UPI app* was used. Bank alerts usually don't say this. Until then, the payment provider can be set per transaction, or inferred from the VPA handle when the alert includes one (`@ybl`/`@ibl`/`@axl` → PhonePe, `@ok*` → Google Pay, `@paytm`/`@pt*` → Paytm).
4. **Account Aggregator**: only through a regulated FIU partner. The adapter boundary is already in place.
5. **Gmail API OAuth**: optional convenience once you're willing to run the app in Testing mode (weekly re-consent) or go through verification.

## Hard rules enforced in code

- Arth never asks for or stores UPI PINs, bank passwords or OTPs. The ingest webhook takes alert *text* only. SMS that look like OTP messages are rejected before parsing and never stored.
- Ingest tokens are random 256-bit values, stored only as SHA-256 hashes, and can be revoked per integration.
- Every imported row records its source (`transaction_sources`) with the raw payload, for audit and re-parsing.

## Sources
- PhonePe developer docs: Transaction List API (merchant QR): https://developer.phonepe.com/v2/reference/transaction-list-api-1
- Sahamati, FIU onboarding: https://sahamati.org.in/?p=1224
- Setu, licences for AA participation: https://setu.co/blog/licences-for-fintechs-to-go-live-on-account-aggregator
- Google, Gmail API scopes: https://developers.google.com/workspace/gmail/api/auth/scopes
- Google Play, SMS & Call Log permissions: https://support.google.com/googleplay/android-developer/answer/10208820

## Prices for net worth

| Source | Available? | Notes | Arth status |
|---|---|---|---|
| **AMFI daily NAV file** (`portal.amfiindia.com/spages/NAVAll.txt`) | Yes, public and free | Semicolon-delimited: scheme code, ISINs, name, NAV, date, grouped by category and AMC | **Working**: nightly load at 23:15 IST plus a manual refresh |
| Broker holdings export (Kite/Console, Groww, Coin…) | Yes, you download it | Quantity, average cost, last price | **Working**: CSV import with upsert |
| CAMS/KFintech CAS PDF | Yes, emailed to you (password-protected) | Full MF transaction history across AMCs | Planned |
| NSE daily bhavcopy | Public archive | End-of-day closing prices | Planned for automatic stock prices |
| Live stock quotes | No free official API | Broker APIs (e.g. Kite Connect) are paid and need your broker login | Not planned |
