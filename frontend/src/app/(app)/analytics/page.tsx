"use client";

import { AlertTriangle, ArrowDownRight, ArrowUpRight, ChevronLeft, ChevronRight, Lightbulb, Sparkles } from "lucide-react";
import Link from "next/link";
import { useState } from "react";
import { Bar, BarChart, CartesianGrid, Legend, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";
import { PageHeader } from "@/components/layout/app-shell";
import { Stat } from "@/components/finance/meter";
import { Button } from "@/components/ui/button";
import { ErrorState, Panel, Skeleton } from "@/components/ui/states";
import { useAnalytics } from "@/hooks/planning";
import { inr, inrCompact, isoDay } from "@/lib/format";
import { cn } from "@/lib/utils";
import type { Insight } from "@/types/api";

function shift(month: string, by: number) {
  const [y, m] = month.split("-").map(Number);
  const d = new Date(Date.UTC(y, m - 1 + by, 1));
  return `${d.getUTCFullYear()}-${String(d.getUTCMonth() + 1).padStart(2, "0")}`;
}

const ICON = { WARNING: AlertTriangle, CRITICAL: AlertTriangle, POSITIVE: Sparkles, INFO: Lightbulb };

function InsightRow({ i }: { i: Insight }) {
  const [open, setOpen] = useState(false);
  const Icon = ICON[i.severity] ?? Lightbulb;
  return (
    <li className="py-3">
      <div className="flex items-start gap-3">
        <span className={cn("mt-0.5 grid size-7 shrink-0 place-items-center rounded-xl",
          i.severity === "WARNING" || i.severity === "CRITICAL" ? "bg-marigold-soft text-marigold-ink" : i.severity === "POSITIVE" ? "bg-in-soft text-in" : "bg-peacock-soft text-peacock")}>
          <Icon className="size-4" />
        </span>
        <div className="min-w-0 flex-1">
          <p className="font-semibold">{i.title}</p>
          <p className="text-sm text-ink-2">{i.detail}</p>
          <div className="mt-1 flex gap-3 text-[13px]">
            <button className="font-semibold text-peacock hover:underline" onClick={() => setOpen(!open)} aria-expanded={open}>{open ? "Hide" : "Show"} the numbers</button>
            {i.link && <Link href={i.link} className="text-ink-2 hover:underline">See transactions</Link>}
          </div>
          {open && <p className="mt-2 rounded-xl bg-surface-2 p-2.5 font-mono text-[12px] text-ink-2">{i.calculation}</p>}
        </div>
      </div>
    </li>
  );
}

export default function AnalyticsPage() {
  const current = isoDay(new Date()).slice(0, 7);
  const [month, setMonth] = useState(current);
  const r = useAnalytics(month);
  const d = r.data;
  const delta = d && d.previousSpending !== null ? d.spending - d.previousSpending : null;
  return (
    <>
      <PageHeader title="Analytics" subtitle="A monthly look at where money came from and went, how it compares with earlier months, and anything that stands out."
        actions={
          <div className="flex items-center gap-1 rounded-full border border-line bg-surface p-0.5">
            <Button variant="ghost" size="icon" aria-label="Previous month" onClick={() => setMonth(shift(month, -1))}><ChevronLeft /></Button>
            <span className="min-w-28 text-center text-sm font-semibold">{d?.label ?? month}</span>
            <Button variant="ghost" size="icon" aria-label="Next month" disabled={month >= current} onClick={() => setMonth(shift(month, 1))}><ChevronRight /></Button>
          </div>
        } />
      {r.isError ? <ErrorState error={r.error} retry={() => r.refetch()} /> : !d ? <Skeleton className="h-96" /> : (
        <div className="space-y-6">
          <div className="flex flex-wrap gap-x-10 gap-y-3">
            <Stat label="Income" value={inr(d.income)} tone="in" />
            <Stat label="Spending" value={inr(d.spending)} hint={delta !== null ? `${delta >= 0 ? "+" : "−"}${inr(Math.abs(delta))} vs ${d.inProgress ? "same days " : ""}last month` : undefined} />
            <Stat label="Invested" value={inr(d.invested)} />
            <Stat label="Saved" value={inr(d.savings)} tone={d.savings < 0 ? "out" : undefined} hint={d.savingsRate !== null ? `${d.savingsRate}% of income` : undefined} />
            <Stat label="Average a day" value={inr(d.averageDailySpend)} />
          </div>

          <div className="grid gap-6 lg:grid-cols-[minmax(0,1.3fr)_minmax(0,1fr)]">
            <Panel title="Last six months">
              <div className="h-64">
                <ResponsiveContainer width="100%" height="100%">
                  <BarChart data={d.trend} margin={{ left: -6, right: 4, top: 8 }}>
                    <CartesianGrid vertical={false} stroke="var(--line)" />
                    <XAxis dataKey="label" tickLine={false} axisLine={false} tick={{ fontSize: 12, fill: "var(--ink-3)" }} tickFormatter={(l: string) => l.split(" ")[0]} />
                    <YAxis tickLine={false} axisLine={false} tick={{ fontSize: 11, fill: "var(--ink-3)" }} tickFormatter={(v: number) => inrCompact(v)} width={56} />
                    <Tooltip cursor={{ fill: "var(--surface-2)" }} formatter={(v) => inr(Number(v))} contentStyle={{ borderRadius: 12, border: "1px solid var(--line)", background: "var(--surface)" }} />
                    <Legend iconType="circle" wrapperStyle={{ fontSize: 12 }} />
                    <Bar dataKey="income" name="Income" fill="var(--in)" radius={[5, 5, 0, 0]} />
                    <Bar dataKey="spending" name="Spending" fill="var(--peacock-2)" radius={[5, 5, 0, 0]} />
                    <Bar dataKey="invested" name="Invested" fill="var(--marigold)" radius={[5, 5, 0, 0]} />
                  </BarChart>
                </ResponsiveContainer>
              </div>
            </Panel>
            <Panel title="What stands out">
              {d.insights.length === 0 ? <p className="text-sm text-ink-3">Nothing unusual this month.</p> : (
                <ul className="-my-3 divide-y divide-line">{d.insights.map((i, n) => <InsightRow key={n} i={i} />)}</ul>
              )}
            </Panel>
          </div>

          <Panel title="Categories">
            {d.categories.length === 0 ? <p className="text-sm text-ink-3">No categorised spending this month.</p> : (
              <div className="overflow-x-auto">
                <table className="w-full text-sm">
                  <thead className="text-left text-[13px] text-ink-3">
                    <tr><th className="py-1.5 pr-4 font-medium">Category</th><th className="pr-4 text-right font-medium">This month</th><th className="pr-4 text-right font-medium">Last month</th><th className="pr-4 text-right font-medium">3-month average</th><th className="text-right font-medium">Change</th></tr>
                  </thead>
                  <tbody className="divide-y divide-line">
                    {d.categories.map((c) => (
                      <tr key={c.categoryId}>
                        <td className="py-2 pr-4"><Link href={`/transactions?categoryId=${c.categoryId}`} className="inline-flex items-center gap-2 hover:underline">
                          <span className="size-2.5 rounded-full" style={{ background: c.color ?? "var(--ink-3)" }} aria-hidden />{c.name}</Link></td>
                        <td className="num pr-4 text-right font-semibold">{inr(c.thisMonth)}</td>
                        <td className="num pr-4 text-right text-ink-2">{inr(c.lastMonth)}</td>
                        <td className="num pr-4 text-right text-ink-2">{inr(c.average3m)}</td>
                        <td className={cn("num text-right font-semibold", c.changePct === null || c.changePct === 0 ? "text-ink-3" : c.changePct > 0 ? "text-out" : "text-in")}>
                          {c.changePct === null ? "new" : c.changePct === 0 ? "same" : <span className="inline-flex items-center gap-0.5">{c.changePct > 0 ? <ArrowUpRight className="size-3.5" /> : <ArrowDownRight className="size-3.5" />}{Math.abs(c.changePct)}%</span>}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </Panel>

          <Panel title="Where you spent the most">
            {d.merchants.length === 0 ? <p className="text-sm text-ink-3">No merchant payments this month.</p> : (
              <ul className="grid gap-x-8 sm:grid-cols-2">
                {d.merchants.map((m, n) => (
                  <li key={m.merchantId} className="flex items-center gap-3 border-b border-line py-2 text-sm">
                    <span className="num w-5 text-ink-3">{n + 1}</span>
                    <Link href={`/transactions?merchantId=${m.merchantId}`} className="min-w-0 flex-1 truncate font-medium hover:underline">{m.name}</Link>
                    <span className="text-xs text-ink-3">{m.count}×</span>
                    <span className="num font-semibold">{inr(m.amount)}</span>
                  </li>
                ))}
              </ul>
            )}
          </Panel>
          <p className="text-xs text-ink-3">{d.note}</p>
        </div>
      )}
    </>
  );
}
