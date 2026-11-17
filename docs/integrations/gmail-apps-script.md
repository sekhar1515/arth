# Bank e-mail alerts → Arth (Google Apps Script)

This runs inside **your own** Google account, so it needs no third-party Google verification. It forwards transaction alert e-mails to Arth's ingest webhook.

1. In Arth, open **Integrations → Bank e-mail alerts → Connect**. Copy the token.
2. Go to <https://script.google.com>, create a project, and paste the script below.
3. Set `ARTH_URL` to your Arth address (it must be reachable from the internet, e.g. through Cloudflare Tunnel) and `TOKEN` to the token.
4. Run `forwardAlerts` once and approve the permissions. Then add a time-driven trigger (every 10 minutes).

```javascript
const ARTH_URL = "https://arth.example.com/api/ingest/email";
const TOKEN = "arth_...";
// Adjust to your banks' alert senders.
const QUERY = 'from:(alerts@hdfcbank.net OR credit_cards@icicibank.com OR alerts@axisbank.com OR cbssbi.cas@alerts.sbi.co.in) newer_than:2d -label:arth-sent';

function forwardAlerts() {
  const label = GmailApp.getUserLabelByName("arth-sent") || GmailApp.createLabel("arth-sent");
  for (const thread of GmailApp.search(QUERY, 0, 50)) {
    for (const msg of thread.getMessages()) {
      const text = msg.getPlainBody().replace(/\s+/g, " ").slice(0, 4000);
      const res = UrlFetchApp.fetch(ARTH_URL, {
        method: "post",
        contentType: "application/json",
        headers: { "X-Ingest-Token": TOKEN },
        payload: JSON.stringify({ text, receivedAt: msg.getDate().toISOString(), sender: msg.getFrom() }),
        muteHttpExceptions: true,
      });
      if (res.getResponseCode() >= 500) return; // try again on the next run
    }
    thread.addLabel(label);
  }
}
```

Arth ignores anything that looks like an OTP and de-duplicates repeated messages, so re-sending is safe.

# Bank SMS alerts → Arth (Android)

Use any SMS forwarder you trust that can POST to a URL (for example an open-source "SMS to URL forwarder", Tasker or MacroDroid):

- Filter by sender: your banks' sender IDs, e.g. `*HDFCBK`, `*ICICIT`, `*SBIUPI`, `*AXISBK`.
- POST to `https://<your-arth>/api/ingest/sms` with header `X-Ingest-Token: <token>` and body `{"text": "<SMS body>"}`.
