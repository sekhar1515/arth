"use client";

import { Download, FileSpreadsheet, FileText, Sheet } from "lucide-react";
import { useState } from "react";
import { PageHeader } from "@/components/layout/app-shell";
import { Button } from "@/components/ui/button";
import { Field, Input } from "@/components/ui/input";
import { qs } from "@/lib/api";
import { isoDay } from "@/lib/format";
import { cn } from "@/lib/utils";

type Format = "PDF" | "XLSX" | "CSV";
type Range = "month" | "range" | "none";
const REPORTS: { type: string; title: string; body: string; range: Range }[] = [
  { type: "MONTHLY", title: "Monthly report", body: "Income, spending, categories against last month, top merchants, six-month trend and insights.", range: "month" },
  { type: "ANNUAL", title: "Annual summary", body: "Month by month totals and the year's categories.", range: "range" },
  { type: "CATEGORIES", title: "Spending by category", body: "Every category with its share of spending, plus investments.", range: "range" },
  { type: "INCOME", title: "Income", body: "Income by source.", range: "range" },
  { type: "CASH_FLOW", title: "Cash flow", body: "Money in, money out, refunds, investments and transfers by month.", range: "range" },
  { type: "TRANSACTIONS", title: "Transactions", body: "Every transaction in the range with its category and note.", range: "range" },
  { type: "BUDGETS", title: "Budgets", body: "Each budget's limit, spending, forecast and status for the current period.", range: "none" },
  { type: "SUBSCRIPTIONS", title: "Subscriptions & bills", body: "Confirmed recurring payments with monthly and yearly cost.", range: "none" },
  { type: "CREDIT_CARDS", title: "Credit cards", body: "Outstanding, statement, dues and payment history per card.", range: "none" },
  { type: "NET_WORTH", title: "Net worth", body: "Assets, loans and net worth history.", range: "none" },
];
const FORMATS: { value: Format; label: string; icon: typeof FileText }[] = [
  { value: "PDF", label: "PDF", icon: FileText }, { value: "XLSX", label: "Excel", icon: FileSpreadsheet }, { value: "CSV", label: "CSV", icon: Sheet },
];

function fyStart(d: Date) {
  const y = d.getMonth() >= 3 ? d.getFullYear() : d.getFullYear() - 1;
  return `${y}-04-01`;
}

export default function ReportsPage() {
  const today = isoDay(new Date());
  const [format, setFormat] = useState<Format>("PDF");
  const [month, setMonth] = useState(today.slice(0, 7));
  const [from, setFrom] = useState(fyStart(new Date()));
  const [to, setTo] = useState(today);

  function href(type: string, range: Range) {
    const p: Record<string, string> = { type, format };
    if (range === "month") p.from = `${month}-01`;
    if (range === "range") Object.assign(p, { from, to });
    return `/api/reports/download${qs(p)}`;
  }

  return (
    <>
      <PageHeader title="Reports" subtitle="Download your numbers to keep, print or share with an accountant. Each file is built from your data when you click." />
      <div className="mb-6 grid gap-4 rounded-3xl border border-line bg-surface p-5 sm:grid-cols-[auto_1fr_1fr_1fr] sm:items-end">
        <div>
          <p className="mb-1.5 text-[13px] font-semibold text-ink-2">Format</p>
          <div className="flex rounded-full border border-line p-0.5" role="radiogroup" aria-label="Format">
            {FORMATS.map((f) => (
              <button key={f.value} role="radio" aria-checked={format === f.value} onClick={() => setFormat(f.value)}
                className={cn("inline-flex items-center gap-1.5 rounded-full px-3 py-1.5 text-sm font-semibold", format === f.value ? "bg-peacock text-white dark:text-[#06201f]" : "text-ink-2")}>
                <f.icon className="size-4" />{f.label}
              </button>
            ))}
          </div>
        </div>
        <Field label="Month (monthly report)" htmlFor="r-month"><Input id="r-month" type="month" value={month} max={today.slice(0, 7)} onChange={(e) => setMonth(e.target.value)} /></Field>
        <Field label="From" htmlFor="r-from"><Input id="r-from" type="date" value={from} onChange={(e) => setFrom(e.target.value)} /></Field>
        <Field label="To" htmlFor="r-to"><Input id="r-to" type="date" value={to} onChange={(e) => setTo(e.target.value)} /></Field>
      </div>
      <ul className="grid gap-3 sm:grid-cols-2">
        {REPORTS.map((r) => (
          <li key={r.type} className="flex items-start gap-4 rounded-3xl border border-line bg-surface p-5">
            <div className="min-w-0 flex-1">
              <h2 className="font-display text-base font-semibold">{r.title}</h2>
              <p className="mt-0.5 text-sm text-ink-2">{r.body}</p>
              <p className="mt-1 text-xs text-ink-3">{r.range === "month" ? `For ${month}` : r.range === "range" ? `${from} to ${to}` : "As of today"}</p>
            </div>
            <Button variant="outline" size="sm" asChild><a href={href(r.type, r.range)} download><Download /> {format === "XLSX" ? "Excel" : format}</a></Button>
          </li>
        ))}
      </ul>
      <p className="mt-5 text-xs text-ink-3">PDFs use a standard font that can&apos;t show the ₹ sign, so amounts there read &ldquo;Rs&rdquo;. Excel and CSV keep ₹ and store amounts as numbers.</p>
    </>
  );
}
