const inr0 = new Intl.NumberFormat("en-IN", { style: "currency", currency: "INR", maximumFractionDigits: 0 });
const inr2 = new Intl.NumberFormat("en-IN", { style: "currency", currency: "INR", minimumFractionDigits: 2, maximumFractionDigits: 2 });
const compact = new Intl.NumberFormat("en-IN", { notation: "compact", maximumFractionDigits: 1 });

/** ₹24,500 — Indian digit grouping, no paise unless asked. */
export function inr(amount: number | null | undefined, opts: { paise?: boolean } = {}): string {
  if (amount === null || amount === undefined || Number.isNaN(amount)) return "—";
  return (opts.paise ? inr2 : inr0).format(amount);
}

/** ₹1.2L-style compact amounts for chart axes. */
export function inrCompact(amount: number): string {
  return "₹" + compact.format(amount);
}

/** Signed amount as seen from the account: money out is negative. */
export function signed(amount: number, direction: "DEBIT" | "CREDIT"): string {
  return (direction === "DEBIT" ? "−" : "+") + inr(amount, { paise: amount % 1 !== 0 });
}

const IST = "Asia/Kolkata";

export function formatDate(iso: string, opts: Intl.DateTimeFormatOptions = { day: "numeric", month: "short", year: "numeric" }): string {
  return new Intl.DateTimeFormat("en-IN", { timeZone: IST, ...opts }).format(new Date(iso));
}

export function formatTime(iso: string): string {
  return new Intl.DateTimeFormat("en-IN", { timeZone: IST, hour: "numeric", minute: "2-digit" }).format(new Date(iso));
}

export function formatDateTime(iso: string, timeKnown = true): string {
  return timeKnown ? `${formatDate(iso)}, ${formatTime(iso)}` : formatDate(iso);
}

/** YYYY-MM-DD for the given date in IST. */
export function isoDay(d: Date): string {
  const parts = new Intl.DateTimeFormat("en-CA", { timeZone: IST, year: "numeric", month: "2-digit", day: "2-digit" }).format(d);
  return parts;
}

export function monthLabel(yyyyMm: string): string {
  const [y, m] = yyyyMm.split("-").map(Number);
  return new Intl.DateTimeFormat("en-IN", { month: "short", year: "2-digit", timeZone: "UTC" }).format(new Date(Date.UTC(y, m - 1, 1)));
}

export function relativeTime(iso: string | null): string {
  if (!iso) return "never";
  const diff = (Date.now() - new Date(iso).getTime()) / 1000;
  if (diff < 60) return "just now";
  if (diff < 3600) return `${Math.floor(diff / 60)} min ago`;
  if (diff < 86400) return `${Math.floor(diff / 3600)} h ago`;
  return formatDate(iso);
}

export const METHOD_LABEL: Record<string, string> = {
  UPI: "UPI", IMPS: "IMPS", NEFT: "NEFT", RTGS: "RTGS", CARD: "Card", ATM: "ATM", CASH: "Cash", CHEQUE: "Cheque", NACH: "Auto-debit", OTHER: "Other",
};

export const SOURCE_LABEL: Record<string, string> = {
  MANUAL: "Added manually", CSV_IMPORT: "Bank statement", SMS: "Bank SMS", EMAIL: "Bank e-mail", DEMO: "Demo data",
  GMAIL: "Gmail", ACCOUNT_AGGREGATOR: "Account Aggregator", PHONEPE_STATEMENT: "PhonePe statement",
};

export function merchantName(t: { merchant: { name: string } | null; counterparty: string | null; description: string | null; kind: string; direction: string }): string {
  if (t.merchant) return t.merchant.name;
  if (t.counterparty) return t.counterparty;
  if (t.kind === "TRANSFER") return t.direction === "DEBIT" ? "Transfer out" : "Transfer in";
  return t.description?.slice(0, 40) || "Unknown";
}

export function initials(name: string): string {
  const words = name.replace(/[^A-Za-z0-9 ]/g, " ").trim().split(/\s+/).filter(Boolean);
  if (words.length === 0) return "?";
  if (words.length === 1) return words[0].slice(0, 2).toUpperCase();
  return (words[0][0] + words[1][0]).toUpperCase();
}

/** UPI apps a payment can be tagged with ("Paid with" / "Payment app"). */
export const UPI_APPS = ["PhonePe", "Google Pay", "Paytm", "super.money", "CRED", "BHIM", "Amazon Pay", "WhatsApp Pay"] as const;
