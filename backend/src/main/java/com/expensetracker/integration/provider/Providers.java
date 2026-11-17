package com.expensetracker.integration.provider;

import com.expensetracker.integration.provider.ProviderDescriptor.Availability;
import com.expensetracker.integration.provider.ProviderDescriptor.ConnectionMode;
import com.expensetracker.transaction.SourceType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The integration catalogue. Unavailable providers are registered on purpose so the UI can explain why they
 * cannot be connected, instead of pretending they work.
 */
@Configuration
public class Providers {

    public static final String MANUAL = "MANUAL";
    public static final String CSV_STATEMENT = "CSV_STATEMENT";
    public static final String SMS_FORWARDER = "SMS_FORWARDER";
    public static final String EMAIL_ALERTS = "EMAIL_ALERTS";
    public static final String GMAIL_API = "GMAIL_API";
    public static final String PHONEPE_STATEMENT = "PHONEPE_STATEMENT";
    public static final String ACCOUNT_AGGREGATOR = "ACCOUNT_AGGREGATOR";
    public static final String PHONEPE_API = "PHONEPE_API";

    record Simple(ProviderDescriptor descriptor, SourceType sourceType) implements IntegrationProvider {
    }

    @Bean
    IntegrationProvider manualProvider() {
        return new Simple(new ProviderDescriptor(MANUAL, "Manual entry",
                "Add cash payments or anything no automatic source covers.",
                Availability.AVAILABLE, ConnectionMode.BUILT_IN, false, false, false,
                "Whatever you enter", "Instant", null, null), SourceType.MANUAL);
    }

    @Bean
    IntegrationProvider csvStatementProvider() {
        return new Simple(new ProviderDescriptor(CSV_STATEMENT, "Bank statement (CSV)",
                "Import the statement CSV your bank's net-banking site exports. Re-importing the same file never creates duplicates.",
                Availability.AVAILABLE, ConnectionMode.FILE_UPLOAD, false, false, false,
                "Every debit and credit with narration, reference and running balance", "When you upload (monthly works well)",
                "XLS and PDF statements are planned. Export as CSV for now.",
                "Net banking → Account statement → choose period → download as CSV/Delimited"), SourceType.CSV_IMPORT);
    }

    @Bean
    IntegrationProvider smsForwarderProvider() {
        return new Simple(new ProviderDescriptor(SMS_FORWARDER, "Bank SMS alerts (Android forwarder)",
                "Forward bank and card transaction SMS from your Android phone to Arth in real time. This covers PhonePe, Google Pay "
                        + "and Paytm spends, because your bank alerts on every UPI debit.",
                Availability.AVAILABLE, ConnectionMode.WEBHOOK_TOKEN, false, false, true,
                "Amount, debit/credit, account last digits, merchant/VPA, UPI reference, date/time", "Real time",
                "Needs an SMS-forwarding app on your phone and a URL your phone can reach (e.g. via Cloudflare Tunnel or Tailscale). "
                        + "OTP messages are rejected and never stored.",
                "Install an SMS forwarder (e.g. an open-source 'SMS to URL' forwarder, Tasker or MacroDroid). Filter senders like "
                        + "*-HDFCBK, *-ICICIT, *-SBIUPI and POST {\"text\": \"<message>\"} with header X-Ingest-Token."), SourceType.SMS);
    }

    @Bean
    IntegrationProvider emailAlertsProvider() {
        return new Simple(new ProviderDescriptor(EMAIL_ALERTS, "Bank e-mail alerts (Gmail Apps Script)",
                "A small Google Apps Script in your own Gmail account forwards bank alert e-mails to Arth. Runs under your own "
                        + "account, so no third-party Google verification is needed.",
                Availability.AVAILABLE, ConnectionMode.WEBHOOK_TOKEN, false, false, true,
                "Same as SMS alerts, from e-mail bodies", "Every 5–15 minutes (script trigger)",
                "Arth needs a publicly reachable URL for the script to call.",
                "See docs/integrations/gmail-apps-script.md for a ready-to-paste script."), SourceType.EMAIL);
    }

    @Bean
    IntegrationProvider gmailApiProvider() {
        return new Simple(new ProviderDescriptor(GMAIL_API, "Gmail API (OAuth)",
                "Direct Gmail connection. Planned for Phase 5.",
                Availability.PLANNED, ConnectionMode.NONE, false, false, true,
                "Bank alert e-mails", "Near real time (Pub/Sub) or polling",
                "gmail.readonly is a restricted scope: a public app needs Google verification plus an annual CASA security assessment. "
                        + "A personal 'Testing' app works without them, but refresh tokens expire every 7 days.",
                null), SourceType.GMAIL);
    }

    @Bean
    IntegrationProvider phonePeStatementProvider() {
        return new Simple(new ProviderDescriptor(PHONEPE_STATEMENT, "PhonePe statement import",
                "Import the statement exported from the PhonePe app to tag which bank transactions were made through PhonePe. Planned for Phase 5.",
                Availability.PLANNED, ConnectionMode.FILE_UPLOAD, false, false, false,
                "PhonePe payment history (payee, amount, UTR)", "When you upload",
                "Export availability depends on your PhonePe app version.", null), SourceType.PHONEPE_STATEMENT);
    }

    @Bean
    IntegrationProvider accountAggregatorProvider() {
        return new Simple(new ProviderDescriptor(ACCOUNT_AGGREGATOR, "Account Aggregator (RBI AA network)",
                "Consent-based bank data via Setu, Finvu, OneMoney, Anumati and others.",
                Availability.UNAVAILABLE, ConnectionMode.NONE, true, true, true,
                "Bank transactions, balances, deposits, mutual funds, insurance", "Periodic, per consent",
                "Only a Financial Information User regulated by RBI, SEBI, IRDAI or PFRDA can receive AA data. A personal app "
                        + "cannot, unless it partners with a regulated FIU. The adapter boundary is ready for that.",
                null), SourceType.ACCOUNT_AGGREGATOR);
    }

    @Bean
    IntegrationProvider phonePeApiProvider() {
        return new Simple(new ProviderDescriptor(PHONEPE_API, "PhonePe API",
                "Direct access to your PhonePe consumer history.",
                Availability.UNAVAILABLE, ConnectionMode.NONE, true, false, true,
                "-", "-",
                "PhonePe has no public API for an individual's transaction history. Its developer APIs are merchant/payment-gateway "
                        + "APIs that list payments a merchant received, which are not usable here. Use bank SMS/e-mail alerts and statements instead.",
                null), null);
    }
}
