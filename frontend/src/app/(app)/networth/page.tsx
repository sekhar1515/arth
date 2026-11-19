"use client";

import { Landmark, Plus, TrendingUp } from "lucide-react";
import Link from "next/link";
import { useState } from "react";
import { toast } from "sonner";
import { Area, AreaChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";
import { PageHeader } from "@/components/layout/app-shell";
import { BUCKET_LABEL, LiabilityDialog } from "@/components/finance/networth-dialogs";
import { BUCKET_COLOR, DayChange, Gain, GroupCard, XirrBadge } from "@/components/finance/wealth-parts";
import { Button } from "@/components/ui/button";
import { useConfirm } from "@/components/ui/confirm";
import { EmptyState, ErrorState, Panel, Skeleton } from "@/components/ui/states";
import { useHoldings, useInvalidateWealth, useLiabilities, useNetWorth, useNetWorthHistory, usePlans } from "@/hooks/queries";
import { api, ApiError } from "@/lib/api";
import { formatDate, inr, inrCompact } from "@/lib/format";
import { cn } from "@/lib/utils";
import type { Liability, NetWorthItem } from "@/types/api";

export default function NetWorthPage() {
  const nw = useNetWorth();
  const history = useNetWorthHistory(12);
  const holdings = useHoldings();
  const liabilities = useLiabilities();
  const plans = usePlans();
  const invalidate = useInvalidateWealth();
  const [dialog, setDialog] = useState<null | "loan">(null);
  const [editLoan, setEditLoan] = useState<Liability | null>(null);
  const s = nw.data;

  const { confirm, dialog: confirmDialog } = useConfirm();

  async function removeItem(item: NetWorthItem) {
    const ok = await confirm({
      title: `Delete ${item.name}?`,
      body: item.kind === "HOLDING"
        ? "It's removed from your net worth. Past net worth snapshots keep their numbers. This can't be undone."
        : "The loan is removed from your net worth. Past net worth snapshots keep their numbers. This can't be undone.",
      confirmLabel: "Delete", danger: true,
    });
    if (!ok) return;
    try {
      if (item.kind === "HOLDING") await api.del(`/networth/holdings/${item.id}`);
      else await api.del(`/networth/liabilities/${item.id}`);
      invalidate();
      toast.success("Deleted");
    } catch (e) {
      toast.error(e instanceof ApiError ? e.message : "Couldn't remove");
    }
  }

  const empty = s && s.assetGroups.every((g) => g.key === "CASH") && s.liabilityGroups.length === 0 && (holdings.data?.length ?? 0) === 0;

  return (
    <>
      <PageHeader
        title="Net Worth"
        subtitle="Everything you own minus everything you owe. Bank balances and cards come from your accounts; investments, SIPs and PF from the Investments tab."
        actions={
          <>
            <Button variant="outline" size="sm" onClick={() => setDialog("loan")}><Plus /> Add loan</Button>
            <Button size="sm" asChild><Link href="/investments"><TrendingUp /> Investments</Link></Button>
          </>
        }
      />

      {nw.isError ? <ErrorState error={nw.error} retry={() => nw.refetch()} /> : !s ? <Skeleton className="h-96" /> : (
        <div className="space-y-6">
          <section className="grid grid-cols-[minmax(0,1fr)] gap-6 rounded-[28px] border border-line bg-surface p-6 sm:p-8 lg:grid-cols-[minmax(0,1fr)_minmax(0,1.2fr)]">
            <div>
              <p className="text-sm text-ink-2">You&apos;re worth</p>
              <p className={cn("num mt-1 text-[44px] font-semibold leading-none tracking-tight sm:text-[56px]", s.netWorth < 0 && "text-out")}>{inr(s.netWorth)}</p>
              <p className="mt-3 text-sm text-ink-2">
                You own <span className="num font-semibold text-ink">{inr(s.assets)}</span> and owe <span className="num font-semibold text-ink">{inr(s.liabilities)}</span>.
              </p>
              <div className="mt-3 flex flex-wrap gap-x-4 gap-y-1 text-xs text-ink-2">
                {s.sincePrevious && (
                  <span>Since {formatDate(s.sincePrevious.since, { day: "numeric", month: "short" })} <Gain value={s.sincePrevious.amount} pct={s.sincePrevious.pct} /></span>
                )}
                {s.holdingsDayChange != null && <span className="inline-flex items-center gap-1">Investments, latest day <DayChange value={s.holdingsDayChange} pct={s.holdingsDayChangePct} /></span>}
                {s.change30d && <span>Last 30 days <Gain value={s.change30d.amount} pct={s.change30d.pct} /></span>}
                {!s.change30d && s.sinceStart && s.sinceStart.since !== s.sincePrevious?.since && (
                  <span>Since {formatDate(s.sinceStart.since, { day: "numeric", month: "short" })} <Gain value={s.sinceStart.amount} pct={s.sinceStart.pct} /></span>
                )}
                {s.changeYtd && <span>Since {formatDate(s.changeYtd.since, { day: "numeric", month: "short" })} <Gain value={s.changeYtd.amount} pct={s.changeYtd.pct} /></span>}
              </div>
              <p className="mt-2 text-xs text-ink-3">Worked out live from your accounts, today&apos;s prices and interest earned.</p>
              {s.holdingsValue > 0 && (
                <div className="mt-5 rounded-2xl bg-surface-2 p-4">
                  <p className="text-[13px] text-ink-2">Investments &amp; other assets</p>
                  <p className="num text-xl font-semibold">{inr(s.holdingsValue)}</p>
                  <p className="text-xs text-ink-3">Invested {inr(s.investedInHoldings)} <Gain value={s.holdingsGain} pct={s.holdingsGainPct} className="ml-1" /></p>
                  {s.holdingsXirr != null && <p className="mt-1 text-xs text-ink-3"><XirrBadge value={s.holdingsXirr} /> based on {s.holdingsXirrCount} of {s.holdingsCount} holdings</p>}
                </div>
              )}
            </div>
            <div className="min-w-0">
              {history.data && history.data.length > 1 ? (
                <div className="h-56">
                  <ResponsiveContainer width="100%" height="100%">
                    <AreaChart data={history.data} margin={{ left: -8, right: 4, top: 8 }}>
                      <defs>
                        <linearGradient id="nwFill" x1="0" y1="0" x2="0" y2="1">
                          <stop offset="0%" stopColor="var(--peacock-2)" stopOpacity={0.35} />
                          <stop offset="100%" stopColor="var(--peacock-2)" stopOpacity={0} />
                        </linearGradient>
                      </defs>
                      <CartesianGrid vertical={false} stroke="var(--line)" />
                      <XAxis dataKey="date" tickFormatter={(d) => formatDate(d + "T12:00:00+05:30", { day: "numeric", month: "short" })} tickLine={false} axisLine={false} tick={{ fill: "var(--ink-3)", fontSize: 11 }} minTickGap={30} />
                      <YAxis tickFormatter={inrCompact} tickLine={false} axisLine={false} width={56} tick={{ fill: "var(--ink-3)", fontSize: 11 }} domain={["auto", "auto"]} />
                      <Tooltip formatter={(v) => inr(Number(v))} labelFormatter={(d) => formatDate(String(d) + "T12:00:00+05:30")} contentStyle={{ borderRadius: 12, border: "1px solid var(--line)", background: "var(--surface)" }} />
                      <Area type="monotone" dataKey="netWorth" name="Net worth" stroke="var(--peacock-2)" strokeWidth={2} fill="url(#nwFill)" />
                    </AreaChart>
                  </ResponsiveContainer>
                </div>
              ) : (
                <div className="grid h-56 place-items-center rounded-2xl border border-dashed border-line p-6 text-center text-sm text-ink-3">
                  Arth records your net worth every night. The trend line appears from tomorrow.
                </div>
              )}
              {s.allocation.length > 0 && (
                <div className="mt-4">
                  <div className="flex h-3 overflow-hidden rounded-full" role="img" aria-label={s.allocation.map((a) => `${BUCKET_LABEL[a.bucket]} ${a.share}%`).join(", ")}>
                    {s.allocation.map((a) => <div key={a.bucket} style={{ width: `${a.share}%`, background: BUCKET_COLOR[a.bucket] }} className="h-full border-r-2 border-surface last:border-0" />)}
                  </div>
                  <ul className="mt-2 flex flex-wrap gap-x-4 gap-y-1 text-xs">
                    {s.allocation.map((a) => (
                      <li key={a.bucket} className="flex items-center gap-1.5">
                        <span className="size-2 rounded-full" style={{ background: BUCKET_COLOR[a.bucket] }} />
                        <span className="text-ink-2">{BUCKET_LABEL[a.bucket]}</span>
                        <span className="num font-semibold">{a.share}%</span>
                      </li>
                    ))}
                  </ul>
                </div>
              )}
            </div>
          </section>

          {(plans.data ?? []).some((p) => p.active) && (() => {
            const active = (plans.data ?? []).filter((p) => p.active);
            const monthly = active.reduce((t, p) => t + p.total, 0);
            const upcoming = [...active].filter((p) => p.nextDue).sort((a, b) => a.nextDue!.localeCompare(b.nextDue!)).slice(0, 4);
            return (
              <section className="rounded-3xl border border-line bg-surface p-5">
                <div className="flex flex-wrap items-baseline justify-between gap-2">
                  <h2 className="font-semibold">SIPs &amp; monthly contributions</h2>
                  <Link href="/investments" className="text-sm font-medium text-peacock hover:underline">Manage →</Link>
                </div>
                <p className="mt-1 text-sm text-ink-2">
                  <span className="num font-semibold text-ink">{inr(monthly)}</span> goes into your investments every month ({active.length} {active.length === 1 ? "plan" : "plans"}).
                  On each date the bank account is debited and the investment grows, so your net worth stays right.
                </p>
                <ul className="mt-3 grid gap-2 sm:grid-cols-2">
                  {upcoming.map((p) => (
                    <li key={p.id} className="flex items-center justify-between gap-3 rounded-2xl bg-surface-2 px-3 py-2 text-sm">
                      <span className="min-w-0 truncate">{p.holdingName}<span className="block text-xs text-ink-3">{p.kind === "SIP" ? "SIP" : "Contribution"}{p.accountName ? ` from ${p.accountName}` : ""}</span></span>
                      <span className="shrink-0 text-right"><span className="num font-semibold">{inr(p.total)}</span><span className="block text-xs text-ink-3">{formatDate(p.nextDue!, { day: "numeric", month: "short" })}</span></span>
                    </li>
                  ))}
                </ul>
              </section>
            );
          })()}

          {empty && (
            <Panel>
              <EmptyState icon={<Landmark />} title="Add what you own"
                body="Add mutual funds, stocks, SIPs, PF, PPF, NPS, gold or property in the Investments tab, and loans here. Arth keeps the total up to date."
                action={<Button asChild><Link href="/investments">Add investments</Link></Button>} />
            </Panel>
          )}

          <div className="grid grid-cols-[minmax(0,1fr)] gap-6 lg:grid-cols-[minmax(0,1.4fr)_minmax(0,1fr)]">
            <div className="min-w-0 space-y-4">
              <h2 className="font-display text-lg font-semibold">What you own</h2>
              {s.assetGroups.map((g) => (
                <GroupCard key={g.key} g={g} manageHref={g.items.some((i) => i.kind === "HOLDING") ? "/investments" : undefined} />
              ))}
            </div>
            <div className="min-w-0 space-y-4">
              <h2 className="font-display text-lg font-semibold">What you owe</h2>
              {s.liabilityGroups.length === 0 && <p className="rounded-3xl border border-line bg-surface p-5 text-sm text-ink-2">Nothing owed. Add home, car or personal loans so your net worth is complete.</p>}
              {s.liabilityGroups.map((g) => (
                <GroupCard key={g.key} g={g} negative onAction={(item, action) => {
                  if (item.kind !== "LIABILITY") return;
                  const l = liabilities.data?.find((x) => x.id === item.id);
                  if (action === "remove") removeItem(item);
                  else if (l) setEditLoan(l);
                }} />
              ))}
            </div>
          </div>
        </div>
      )}

      {dialog === "loan" && <LiabilityDialog open onOpenChange={(o) => !o && setDialog(null)} />}
      {editLoan && <LiabilityDialog liability={editLoan} open onOpenChange={(o) => !o && setEditLoan(null)} />}
      {confirmDialog}
    </>
  );
}
