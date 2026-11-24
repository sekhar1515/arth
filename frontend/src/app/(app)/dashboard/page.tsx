"use client";

import { ArrowRight, Inbox, Plus } from "lucide-react";
import Link from "next/link";
import { useEffect, useState } from "react";
import { toast } from "sonner";
import { PageHeader } from "@/components/layout/app-shell";
import { CategoryBreakdown, DailySpendChart, IncomeExpenseChart, Insights, MoneyFlow, PaymentApps } from "@/components/finance/dashboard-parts";
import { DateRangePicker, presetRange, type Range } from "@/components/finance/date-range";
import { ManualTransactionDialog } from "@/components/finance/dialogs";
import { Monogram } from "@/components/finance/approval-card";
import { Button } from "@/components/ui/button";
import { EmptyState, ErrorState, Panel, Skeleton } from "@/components/ui/states";
import { useAccounts, useCategories, useDaily, useMe, useMonthly, useSummary } from "@/hooks/queries";
import { useToday } from "@/hooks/use-today";
import { inr } from "@/lib/format";
import { cn } from "@/lib/utils";

export default function DashboardPage() {
  const [range, setRange] = useState<Range>(() => presetRange("this-month"));
  const today = useToday();
  // a new day (or month) while the app is open: "This month", "Last month" etc. move with it, so on the 1st the
  // figures start fresh for the new month. A custom range you picked stays as it is.
  useEffect(() => {
    setRange((r) => (r.preset === "custom" ? r : presetRange(r.preset)));
  }, [today]);
  const [adding, setAdding] = useState(false);
  const accounts = useAccounts();
  const categories = useCategories();
  const me = useMe();
  const summary = useSummary(range.from, range.to);
  const monthly = useMonthly(6);
  const daily = useDaily(range.from, range.to);
  const s = summary.data;
  const firstName = me.data?.displayName.split(" ")[0];
  const hasAccounts = (accounts.data ?? []).some((a) => !a.archived);

  function addTransaction() {
    if (!accounts.data) return;
    if (!hasAccounts) {
      toast("Add an account first (Accounts), then you can add transactions to it.");
      return;
    }
    setAdding(true);
  }
  const addButton = (
    <Button size="sm" onClick={addTransaction} disabled={!accounts.data}><Plus /> Add transaction</Button>
  );

  return (
    <>
      <PageHeader title={firstName ? `Hello, ${firstName}` : "Dashboard"} actions={<div className="flex flex-wrap items-center gap-2"><DateRangePicker value={range} onChange={setRange} />{addButton}</div>} />

      {summary.isError ? (
        <ErrorState error={summary.error} retry={() => summary.refetch()} />
      ) : !s ? (
        <div className="space-y-6"><Skeleton className="h-48" /><div className="grid gap-6 lg:grid-cols-2"><Skeleton className="h-72" /><Skeleton className="h-72" /></div></div>
      ) : s.accounts.accounts.every((a) => a.transactionCount === 0) && s.income === 0 && s.expenses === 0 ? (
        <Panel>
          <EmptyState
            icon={hasAccounts ? <button type="button" onClick={addTransaction} aria-label="Add a transaction" className="grid place-items-center"><Plus /></button> : <Plus />}
            title="No transactions yet"
            body={hasAccounts
              ? "Add a transaction, import a statement or connect bank SMS alerts. Your dashboard fills in from real data only."
              : "Add your bank accounts, then import a statement or connect bank SMS alerts. Your dashboard fills in from real data only."}
            action={<div className="flex flex-wrap justify-center gap-2">
              {hasAccounts ? <Button onClick={addTransaction}><Plus /> Add transaction</Button> : <Button asChild><Link href="/accounts">Add an account</Link></Button>}
              <Button asChild variant="outline"><Link href="/integrations">Connect a source</Link></Button>
            </div>} />
        </Panel>
      ) : (
        <div className="space-y-6">
          {s.needsReviewCount > 0 && (
            <Link href="/review" className="flex items-center gap-3 rounded-2xl border border-marigold/50 bg-marigold-soft px-4 py-3 text-sm text-marigold-ink hover:brightness-[0.98]">
              <Inbox className="size-5 shrink-0" />
              <span className="flex-1">
                <strong>{s.needsReviewCount} transaction{s.needsReviewCount === 1 ? " is" : "s are"} waiting for you.</strong>{" "}
                Categorise them to complete your spending breakdown.
              </span>
              <ArrowRight className="size-4" />
            </Link>
          )}

          <MoneyFlow s={s} />

          <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
            <Stat label="Balance across accounts" value={inr(s.accounts.totalBalance)} hint="Opening balances plus imported transactions" />
            <Stat label="Credit card outstanding" value={inr(s.accounts.creditCardOutstanding)} tone={s.accounts.creditCardOutstanding > 0 ? "out" : undefined} />
            <Link href="/networth" className="block rounded-2xl hover:ring-2 hover:ring-peacock-2/40">
              <Stat label="Net worth" value={inr(s.accounts.netWorth)}
                hint={s.accounts.investments > 0 ? `Includes ${inr(s.accounts.investments)} of investments` : "Add investments and loans in Net Worth"} />
            </Link>
            <Stat label="Savings rate" value={s.savingsRate === null ? "—" : `${s.savingsRate}%`} tone={s.savingsRate !== null && s.savingsRate < 0 ? "out" : "in"}
              hint={s.previousExpenses > 0 ? `Spent ${inr(s.previousExpenses)} in the previous period` : undefined} />
          </div>

          <div className="grid gap-6 lg:grid-cols-[1.25fr_1fr]">
            <Panel title="Where it went" action={<Link href="/transactions" className="text-[13px] font-semibold text-peacock hover:underline">All transactions</Link>}>
              <CategoryBreakdown slices={s.expenseByCategory} pending={s.pendingCategorisationAmount} total={s.expenses} />
            </Panel>
            <div className="space-y-6">
              <Panel title="What stands out"><Insights items={s.insights} /></Panel>
              <Panel title="Spent through">
                <PaymentApps rows={s.paymentMethods} />
                <p className="mt-3 text-xs text-ink-3">Bank alerts rarely say which UPI app you used. &ldquo;App unknown&rdquo; shrinks as you tag payments or import PhonePe statements.</p>
              </Panel>
            </div>
          </div>

          <div className="grid gap-6 lg:grid-cols-2">
            <Panel title="Earned, spent and invested, last 6 months">
              {monthly.data ? <IncomeExpenseChart data={monthly.data} /> : <Skeleton className="h-64" />}
            </Panel>
            <Panel title="Daily spending">
              {daily.data ? <DailySpendChart data={daily.data} /> : <Skeleton className="h-48" />}
              {s.topMerchants.length > 0 && (
                <>
                  <h3 className="mb-3 mt-5 text-[13px] font-semibold text-ink-2">Top merchants</h3>
                  <ul className="space-y-2">
                    {s.topMerchants.map((m) => (
                      <li key={m.merchantId} className="flex items-center gap-3 text-sm">
                        <Monogram name={m.name} className="size-8 rounded-xl text-[11px]" />
                        <span className="flex-1 font-medium">{m.name}</span>
                        <span className="text-xs text-ink-3">{m.count}×</span>
                        <span className="num w-20 text-right font-semibold">{inr(m.amount)}</span>
                      </li>
                    ))}
                  </ul>
                </>
              )}
            </Panel>
          </div>
        </div>
      )}
      {adding && accounts.data && <ManualTransactionDialog accounts={accounts.data} categories={categories.data ?? []} open onOpenChange={setAdding} />}
    </>
  );
}

function Stat({ label, value, hint, tone }: { label: string; value: string; hint?: string; tone?: "in" | "out" }) {
  return (
    <div className="rounded-2xl border border-line bg-surface px-4 py-3.5">
      <p className="text-[13px] text-ink-2">{label}</p>
      <p className={cn("num mt-1 text-xl font-semibold", tone === "in" && "text-in", tone === "out" && "text-out")}>{value}</p>
      {hint && <p className="mt-0.5 text-xs text-ink-3">{hint}</p>}
    </div>
  );
}
