"use client";

import { useMemo, useState } from "react";
import { Bar, BarChart, CartesianGrid, Cell, ReferenceLine, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";
import { formatDate, inr, inrCompact, isoDay } from "@/lib/format";
import { cn } from "@/lib/utils";
import type { NetWorthPoint } from "@/types/api";

export interface Move {
  /** YYYY-MM-DD for a day, YYYY-MM for a month */
  key: string;
  netWorth: number;
  change: number;
  pct: number | null;
  /** a day: days since the previous saved day (1 normally); a month: true while the month is still running */
  gapDays?: number;
  current?: boolean;
  /** a month with no earlier month saved: the change is from this day */
  from?: string;
}

const pctOf = (change: number, base: number) => (base !== 0 ? Math.round((change * 10000) / Math.abs(base)) / 100 : null);
const daysBetween = (a: string, b: string) => Math.round((Date.parse(b + "T00:00:00Z") - Date.parse(a + "T00:00:00Z")) / 86_400_000);

/**
 * Day-by-day and month-by-month change in net worth, from the nightly points. Today's point is replaced by the live
 * figure so the numbers match the total shown on the page.
 */
export function netWorthMoves(history: NetWorthPoint[] | undefined, liveNetWorth: number | undefined, today: string = isoDay(new Date())) {
  const pts = [...(history ?? [])].sort((a, b) => a.date.localeCompare(b.date)).map((p) => ({ date: p.date, netWorth: p.netWorth }));
  if (liveNetWorth != null) {
    if (pts.length && pts[pts.length - 1].date === today) pts[pts.length - 1] = { date: today, netWorth: liveNetWorth };
    else pts.push({ date: today, netWorth: liveNetWorth });
  }

  const daily: Move[] = [];
  for (let i = 1; i < pts.length; i++) {
    const prev = pts[i - 1], p = pts[i];
    const change = p.netWorth - prev.netWorth;
    daily.push({ key: p.date, netWorth: p.netWorth, change, pct: pctOf(change, prev.netWorth), gapDays: daysBetween(prev.date, p.date) });
  }

  // month-end value = the last point saved in that month
  const byMonth = new Map<string, { first: { date: string; netWorth: number }; last: { date: string; netWorth: number } }>();
  for (const p of pts) {
    const m = p.date.slice(0, 7);
    const e = byMonth.get(m);
    if (!e) byMonth.set(m, { first: p, last: p });
    else e.last = p;
  }
  const months = [...byMonth.entries()];
  const monthly: Move[] = [];
  months.forEach(([m, e], i) => {
    const base = i > 0 ? months[i - 1][1].last.netWorth : e.first.netWorth;
    if (i === 0 && e.first.date === e.last.date) return; // a single day: nothing to compare yet
    const change = e.last.netWorth - base;
    monthly.push({ key: m, netWorth: e.last.netWorth, change, pct: pctOf(change, base), current: m === today.slice(0, 7), from: i === 0 ? e.first.date : undefined });
  });

  return {
    daily: daily.reverse(),
    monthly: monthly.reverse(),
    today: daily.length && daily[0].key === today ? daily[0] : null,
    thisMonth: monthly.length && monthly[0].current ? monthly[0] : null,
  };
}

export function Signed({ value, pct, className }: { value: number; pct?: number | null; className?: string }) {
  const up = value >= 0;
  return (
    <span className={cn("num font-semibold", value === 0 ? "text-ink-2" : up ? "text-in" : "text-out", className)}>
      {value === 0 ? "" : up ? "+" : "−"}{inr(Math.abs(value))}
      {pct != null && <span className="ml-1 text-[0.85em] font-medium">({up ? "+" : ""}{pct}%)</span>}
    </span>
  );
}

const dayLabel = (d: string, o: Intl.DateTimeFormatOptions) => formatDate(d + "T12:00:00+05:30", o);
const monthName = (m: string, o: Intl.DateTimeFormatOptions = { month: "short", year: "numeric" }) => dayLabel(m + "-15", o);

export function NetWorthMoves({ daily, monthly }: { daily: Move[]; monthly: Move[] }) {
  const [view, setView] = useState<"daily" | "monthly">("daily");
  const [shown, setShown] = useState(31);
  const rows = view === "daily" ? daily : monthly;
  const chart = useMemo(() => [...(view === "daily" ? daily.slice(0, 30) : monthly.slice(0, 12))].reverse(), [view, daily, monthly]);

  return (
    <section className="rounded-3xl border border-line bg-surface p-5" aria-label="How your net worth changed">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h2 className="font-display text-lg font-semibold">How your net worth changed</h2>
        <div role="tablist" aria-label="Period" className="inline-flex rounded-full bg-surface-2 p-1 text-sm">
          {(["daily", "monthly"] as const).map((v) => (
            <button key={v} role="tab" aria-selected={view === v} onClick={() => setView(v)}
              className={cn("rounded-full px-4 py-1.5 font-medium transition-colors", view === v ? "bg-surface text-ink shadow-sm" : "text-ink-2 hover:text-ink")}>
              {v === "daily" ? "Daily" : "Monthly"}
            </button>
          ))}
        </div>
      </div>

      {rows.length === 0 ? (
        <p className="mt-4 rounded-2xl border border-dashed border-line p-6 text-center text-sm text-ink-3">
          {view === "daily" ? "Arth saves your net worth every night. The daily change shows from tomorrow." : "The monthly change shows once there are two days saved."}
        </p>
      ) : (
        <>
          <div className="mt-4 h-44">
            <ResponsiveContainer width="100%" height="100%">
              <BarChart data={chart} margin={{ left: -8, right: 4, top: 8 }}>
                <CartesianGrid vertical={false} stroke="var(--line)" />
                <XAxis dataKey="key" tickLine={false} axisLine={false} minTickGap={20} tick={{ fill: "var(--ink-3)", fontSize: 11 }}
                  tickFormatter={(k: string) => (view === "daily" ? dayLabel(k, { day: "numeric", month: "short" }) : monthName(k, { month: "short" }))} />
                <YAxis tickFormatter={inrCompact} tickLine={false} axisLine={false} width={56} tick={{ fill: "var(--ink-3)", fontSize: 11 }} />
                <ReferenceLine y={0} stroke="var(--line)" />
                <Tooltip cursor={{ fill: "var(--surface-2)" }} contentStyle={{ borderRadius: 12, border: "1px solid var(--line)", background: "var(--surface)" }}
                  labelFormatter={(k) => (view === "daily" ? dayLabel(String(k), { weekday: "short", day: "numeric", month: "short" }) : monthName(String(k), { month: "long", year: "numeric" }))}
                  formatter={(v) => [(Number(v) >= 0 ? "+" : "−") + inr(Math.abs(Number(v))), "Change"]} />
                <Bar dataKey="change" radius={[4, 4, 4, 4]} maxBarSize={28}>
                  {chart.map((m) => <Cell key={m.key} fill={m.change >= 0 ? "var(--in)" : "var(--out)"} />)}
                </Bar>
              </BarChart>
            </ResponsiveContainer>
          </div>

          <div className="mt-4 overflow-hidden rounded-2xl border border-line">
            <div className="grid grid-cols-[minmax(0,1.2fr)_minmax(0,1fr)_minmax(0,1fr)] gap-3 border-b border-line bg-surface-2 px-4 py-2 text-xs font-medium text-ink-3">
              <span>{view === "daily" ? "Day" : "Month"}</span>
              <span className="text-right">Change</span>
              <span className="text-right">{view === "daily" ? "Net worth" : "Month-end net worth"}</span>
            </div>
            <ul className="divide-y divide-line">
              {(view === "daily" ? rows.slice(0, shown) : rows).map((m) => (
                <li key={m.key} className="grid grid-cols-[minmax(0,1.2fr)_minmax(0,1fr)_minmax(0,1fr)] items-center gap-3 px-4 py-2.5 text-sm">
                  <span className="min-w-0">
                    {view === "daily" ? dayLabel(m.key, { weekday: "short", day: "numeric", month: "short", year: "numeric" }) : monthName(m.key, { month: "long", year: "numeric" })}
                    {view === "daily" && m.gapDays && m.gapDays > 1 && <span className="block text-[11px] text-ink-3">over {m.gapDays} days (no record in between)</span>}
                    {view === "monthly" && m.current && <span className="block text-[11px] text-ink-3">so far</span>}
                    {view === "monthly" && m.from && <span className="block text-[11px] text-ink-3">from {dayLabel(m.from, { day: "numeric", month: "short" })}</span>}
                  </span>
                  <span className="text-right"><Signed value={m.change} pct={m.pct} /></span>
                  <span className="num text-right text-ink-2">{inr(m.netWorth)}</span>
                </li>
              ))}
            </ul>
          </div>
          {view === "daily" && rows.length > shown && (
            <button className="mt-3 text-sm font-medium text-peacock hover:underline" onClick={() => setShown((n) => n + 31)}>Show earlier days</button>
          )}
        </>
      )}
    </section>
  );
}
