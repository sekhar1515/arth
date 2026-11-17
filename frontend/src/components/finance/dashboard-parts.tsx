"use client";

import { AlertCircle, CheckCircle2, Info, TrendingUp } from "lucide-react";
import Link from "next/link";
import { Bar, BarChart, CartesianGrid, Area, AreaChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";
import { formatDate, inr, inrCompact, METHOD_LABEL, monthLabel } from "@/lib/format";
import { cn } from "@/lib/utils";
import type { CategorySlice, DashboardSummary, DayPoint, Insight, MonthPoint } from "@/types/api";
import { CategoryIcon } from "./category-icon";

/**
 * The opening statement: where this period's money went, as one proportional bar.
 * Segments: each category's spending, pending (uncategorised) spending, and what's left as savings.
 */
export function MoneyFlow({ s }: { s: DashboardSummary }) {
  const spentCats = s.expenseByCategory.filter((c) => c.amount > 0);
  const invested = s.invested ?? 0;
  const kept = Math.max(s.savings - invested, 0);
  const base = Math.max(s.income, s.expenses + invested, 1);
  const segments = [
    ...spentCats.slice(0, 7).map((c) => ({ key: c.categoryId, label: c.name, amount: c.amount, color: c.color })),
    ...(spentCats.length > 7 ? [{ key: "more", label: "Other categories", amount: spentCats.slice(7).reduce((a, c) => a + c.amount, 0), color: "#94A3B8" }] : []),
    ...(s.pendingCategorisationAmount > 0 ? [{ key: "pending", label: "Not categorised yet", amount: s.pendingCategorisationAmount, color: "var(--marigold)", striped: true }] : []),
    ...(invested > 0 ? [{ key: "invested", label: "Invested", amount: invested, color: "var(--peacock)" }] : []),
    ...(kept > 0 ? [{ key: "saved", label: "Left over", amount: kept, color: "var(--in)" }] : []),
  ] as { key: string; label: string; amount: number; color: string; striped?: boolean }[];

  const overspent = s.expenses > s.income && s.income > 0;

  return (
    <section aria-labelledby="flow-title" className="rounded-[28px] border border-line bg-surface p-6 sm:p-8">
      <h2 id="flow-title" className="max-w-3xl font-display text-[26px] font-semibold leading-[1.2] tracking-tight sm:text-[34px]">
        <span className="num text-in">{inr(s.income)}</span> came in{invested > 0 ? ", " : " and "}<span className="num">{inr(s.expenses)}</span>{" "}
        {invested > 0 ? <>was spent and <span className="num text-peacock">{inr(invested)}</span> invested</> : "went out"}
        {s.income > 0 && (
          <span className="text-ink-2">
            {overspent ? `, ${inr(s.expenses - s.income)} more than you earned.` : `, so you kept ${s.savingsRate ?? 0}%.`}
          </span>
        )}
        {s.income === 0 && <span className="text-ink-2">.</span>}
      </h2>
      <p className="mt-2 text-sm text-ink-3">
        {formatDate(s.period.from, { day: "numeric", month: "long" })} to {formatDate(s.period.to, { day: "numeric", month: "long", year: "numeric" })}
        {s.refunds > 0 && `. Spending is after ${inr(s.refunds)} of refunds`}
        {s.transfersOut > 0 && `. ${inr(s.transfersOut)} moved between your own accounts and is not counted`}
      </p>

      {segments.length > 0 ? (
        <>
          <div className="flow-in mt-6 flex h-5 w-full overflow-hidden rounded-full bg-surface-2" role="img"
            aria-label={segments.map((g) => `${g.label} ${inr(g.amount)}`).join(", ")}>
            {segments.map((g) => (
              <div key={g.key} title={`${g.label}: ${inr(g.amount)}`} className="h-full border-r-2 border-surface last:border-r-0"
                style={{
                  width: `${(g.amount / base) * 100}%`,
                  background: g.striped ? `repeating-linear-gradient(135deg, ${g.color} 0 6px, transparent 6px 10px)` : g.color,
                }} />
            ))}
          </div>
          <ul className="mt-4 flex flex-wrap gap-x-5 gap-y-2 text-[13px]">
            {segments.map((g) => (
              <li key={g.key} className="flex items-center gap-2">
                <span className="size-2.5 rounded-full" style={{ background: g.color }} aria-hidden />
                <span className="text-ink-2">{g.label}</span>
                <span className="num font-semibold">{inr(g.amount)}</span>
              </li>
            ))}
          </ul>
        </>
      ) : (
        <p className="mt-6 text-sm text-ink-2">No transactions in this period yet.</p>
      )}
    </section>
  );
}

export function CategoryBreakdown({ slices, pending, total }: { slices: CategorySlice[]; pending: number; total: number }) {
  if (slices.length === 0 && pending === 0) return <p className="py-6 text-center text-sm text-ink-3">No spending in this period.</p>;
  const max = Math.max(...slices.map((s) => s.amount), pending, 1);
  return (
    <ul className="space-y-3">
      {slices.map((s) => (
        <li key={s.categoryId}>
          <Link href={`/transactions?categoryId=${s.categoryId}`} className="group flex items-center gap-3">
            <CategoryIcon icon={s.icon} color={s.color} />
            <div className="min-w-0 flex-1">
              <div className="flex items-baseline justify-between gap-2">
                <span className="truncate text-sm font-semibold group-hover:underline">{s.name}</span>
                <span className="num text-sm font-semibold">{inr(s.amount)}</span>
              </div>
              <div className="mt-1.5 flex items-center gap-2">
                <div className="h-1.5 flex-1 overflow-hidden rounded-full bg-surface-2">
                  <div className="h-full rounded-full" style={{ width: `${(s.amount / max) * 100}%`, background: s.color }} />
                </div>
                <span className="num w-12 text-right text-xs text-ink-3">{s.share}%</span>
              </div>
            </div>
          </Link>
        </li>
      ))}
      {pending > 0 && (
        <li>
          <Link href="/review" className="group flex items-center gap-3">
            <span className="grid size-9 place-items-center rounded-xl bg-marigold-soft text-marigold-ink" aria-hidden><AlertCircle className="size-4" /></span>
            <div className="min-w-0 flex-1">
              <div className="flex items-baseline justify-between gap-2">
                <span className="text-sm font-semibold group-hover:underline">Pending categorisation</span>
                <span className="num text-sm font-semibold">{inr(pending)}</span>
              </div>
              <div className="mt-1.5 flex items-center gap-2">
                <div className="h-1.5 flex-1 overflow-hidden rounded-full bg-surface-2">
                  <div className="h-full rounded-full bg-marigold" style={{ width: `${(pending / max) * 100}%` }} />
                </div>
                <span className="num w-12 text-right text-xs text-ink-3">{total > 0 ? ((pending / total) * 100).toFixed(1) : 0}%</span>
              </div>
            </div>
          </Link>
        </li>
      )}
    </ul>
  );
}

function ChartTooltip({ active, payload, label, labelFormat }: { active?: boolean; payload?: { name: string; value: number; color: string }[]; label?: string; labelFormat?: (l: string) => string }) {
  if (!active || !payload?.length) return null;
  return (
    <div className="rounded-xl border border-line bg-surface px-3 py-2 text-xs shadow-lift">
      <p className="mb-1 font-semibold">{labelFormat && label ? labelFormat(label) : label}</p>
      {payload.map((p) => (
        <p key={p.name} className="flex items-center gap-2">
          <span className="size-2 rounded-full" style={{ background: p.color }} />
          <span className="text-ink-2">{p.name}</span>
          <span className="num ml-auto font-semibold">{inr(p.value)}</span>
        </p>
      ))}
    </div>
  );
}

export function IncomeExpenseChart({ data }: { data: MonthPoint[] }) {
  return (
    <div className="h-64">
      <ResponsiveContainer width="100%" height="100%">
        <BarChart data={data} barGap={4} margin={{ left: -8, right: 4, top: 8 }}>
          <CartesianGrid vertical={false} stroke="var(--line)" />
          <XAxis dataKey="month" tickFormatter={monthLabel} tickLine={false} axisLine={false} tick={{ fill: "var(--ink-3)", fontSize: 12 }} />
          <YAxis tickFormatter={inrCompact} tickLine={false} axisLine={false} width={56} tick={{ fill: "var(--ink-3)", fontSize: 12 }} />
          <Tooltip cursor={{ fill: "var(--surface-2)" }} content={<ChartTooltip labelFormat={monthLabel} />} />
          <Bar dataKey="income" name="In" fill="var(--in)" radius={[6, 6, 0, 0]} maxBarSize={28} />
          <Bar dataKey="expenses" name="Spent" fill="var(--out)" radius={[6, 6, 0, 0]} maxBarSize={28} />
          <Bar dataKey="invested" name="Invested" fill="var(--peacock)" radius={[6, 6, 0, 0]} maxBarSize={28} />
        </BarChart>
      </ResponsiveContainer>
    </div>
  );
}

export function DailySpendChart({ data }: { data: DayPoint[] }) {
  const series = data.map((d) => ({ ...d, label: formatDate(d.date + "T12:00:00+05:30", { day: "numeric", month: "short" }) }));
  return (
    <div className="h-48">
      <ResponsiveContainer width="100%" height="100%">
        <AreaChart data={series} margin={{ left: -8, right: 4, top: 8 }}>
          <defs>
            <linearGradient id="spendFill" x1="0" y1="0" x2="0" y2="1">
              <stop offset="0%" stopColor="var(--peacock-2)" stopOpacity={0.35} />
              <stop offset="100%" stopColor="var(--peacock-2)" stopOpacity={0} />
            </linearGradient>
          </defs>
          <CartesianGrid vertical={false} stroke="var(--line)" />
          <XAxis dataKey="label" tickLine={false} axisLine={false} tick={{ fill: "var(--ink-3)", fontSize: 11 }} interval="preserveStartEnd" minTickGap={24} />
          <YAxis tickFormatter={inrCompact} tickLine={false} axisLine={false} width={52} tick={{ fill: "var(--ink-3)", fontSize: 11 }} />
          <Tooltip content={<ChartTooltip />} />
          <Area type="monotone" dataKey="expenses" name="Spent" stroke="var(--peacock-2)" strokeWidth={2} fill="url(#spendFill)" />
        </AreaChart>
      </ResponsiveContainer>
    </div>
  );
}

const TONE: Record<Insight["tone"], { icon: typeof Info; cls: string }> = {
  neutral: { icon: Info, cls: "text-ink-3" },
  positive: { icon: CheckCircle2, cls: "text-in" },
  warning: { icon: TrendingUp, cls: "text-out" },
  action: { icon: AlertCircle, cls: "text-marigold" },
};

export function Insights({ items }: { items: Insight[] }) {
  return (
    <ul className="space-y-3">
      {items.map((i) => {
        const T = TONE[i.tone] ?? TONE.neutral;
        const Icon = T.icon;
        const body = <span className="text-sm leading-snug text-ink">{i.message}</span>;
        return (
          <li key={i.type} className="flex gap-3">
            <Icon className={cn("mt-0.5 size-4 shrink-0", T.cls)} aria-hidden />
            {i.type === "NEEDS_REVIEW" || i.type === "DUPLICATES" ? <Link href="/review" className="hover:underline">{body}</Link> : body}
          </li>
        );
      })}
    </ul>
  );
}

export function PaymentApps({ rows }: { rows: DashboardSummary["paymentMethods"] }) {
  const total = rows.reduce((a, r) => a + r.amount, 0) || 1;
  const grouped = new Map<string, number>();
  for (const r of rows) {
    const k = r.provider ? `${r.provider}` : METHOD_LABEL[r.method] + (r.method === "UPI" ? " (app unknown)" : "");
    grouped.set(k, (grouped.get(k) ?? 0) + r.amount);
  }
  const list = [...grouped.entries()].sort((a, b) => b[1] - a[1]);
  if (list.length === 0) return <p className="py-4 text-sm text-ink-3">No spending yet.</p>;
  return (
    <ul className="space-y-2.5">
      {list.map(([k, v]) => (
        <li key={k} className="flex items-center gap-3 text-sm">
          <span className="w-40 truncate text-ink-2">{k}</span>
          <div className="h-1.5 flex-1 overflow-hidden rounded-full bg-surface-2">
            <div className="h-full rounded-full bg-peacock-2" style={{ width: `${(v / total) * 100}%` }} />
          </div>
          <span className="num w-20 text-right font-semibold">{inr(v)}</span>
        </li>
      ))}
    </ul>
  );
}
