"use client";

import { FileUp, Landmark, MoreHorizontal, Pencil, Plus, RefreshCw, Trash2, TrendingDown, TrendingUp } from "lucide-react";
import Link from "next/link";
import { useState } from "react";
import { toast } from "sonner";
import { Area, AreaChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";
import { PageHeader } from "@/components/layout/app-shell";
import { BUCKET_LABEL, HoldingDialog, ImportHoldingsDialog, LiabilityDialog, SourceBadge, UpdateValueDialog } from "@/components/finance/networth-dialogs";
import { Button } from "@/components/ui/button";
import { useConfirm } from "@/components/ui/confirm";
import { Menu, MenuItem } from "@/components/ui/menu";
import { EmptyState, ErrorState, Panel, Skeleton } from "@/components/ui/states";
import { useHoldings, useInvalidateWealth, useLiabilities, useNetWorth, useNetWorthHistory } from "@/hooks/queries";
import { api, ApiError } from "@/lib/api";
import { formatDate, inr, inrCompact } from "@/lib/format";
import { cn } from "@/lib/utils";
import type { AllocationBucket, Holding, Liability, NetWorthGroup, NetWorthItem } from "@/types/api";

const BUCKET_COLOR: Record<AllocationBucket, string> = {
  EQUITY: "#0F5E63", DEBT: "#6366F1", HYBRID: "#8B5CF6", GOLD: "#E8A317", REAL_ESTATE: "#B45309", CASH: "#5FB3B0", OTHER: "#94A3B8",
};

function Gain({ value, pct, className }: { value: number | null; pct: number | null; className?: string }) {
  if (value === null) return null;
  const up = value >= 0;
  return (
    <span className={cn("num inline-flex items-center gap-1 text-xs font-semibold", up ? "text-in" : "text-out", className)}>
      {up ? <TrendingUp className="size-3" /> : <TrendingDown className="size-3" />}
      {up ? "+" : "−"}{inr(Math.abs(value))}{pct !== null && ` (${up ? "+" : ""}${pct}%)`}
    </span>
  );
}

export default function NetWorthPage() {
  const nw = useNetWorth();
  const history = useNetWorthHistory(12);
  const holdings = useHoldings();
  const liabilities = useLiabilities();
  const invalidate = useInvalidateWealth();
  const [dialog, setDialog] = useState<null | "asset" | "loan" | "import">(null);
  const [editHolding, setEditHolding] = useState<Holding | null>(null);
  const [updateHolding, setUpdateHolding] = useState<Holding | null>(null);
  const [editLoan, setEditLoan] = useState<Liability | null>(null);
  const [refreshing, setRefreshing] = useState(false);
  const s = nw.data;

  async function refreshPrices() {
    setRefreshing(true);
    try {
      type Part = { ok: boolean; holdingsUpdated: number; message: string };
      const r = await api.post<{ ok: boolean; holdingsUpdated: number; message: string; latestNavDate: string | null;
        funds: Part & { latestNavDate: string | null }; stocks: Part & { priceDate: string | null } }>("/networth/prices/refresh");
      const parts = [
        r.funds.ok ? `fund NAVs${r.funds.latestNavDate ? ` for ${formatDate(r.funds.latestNavDate)}` : ""}` : null,
        r.stocks.ok ? `stock prices${r.stocks.priceDate ? ` for ${formatDate(r.stocks.priceDate)}` : ""}` : null,
      ].filter(Boolean);
      if (r.ok) toast.success(`Loaded ${parts.join(" and ")}. ${r.holdingsUpdated} holding${r.holdingsUpdated === 1 ? "" : "s"} re-priced.`);
      if (!r.funds.ok) toast.error(r.funds.message);
      if (!r.stocks.ok) toast.error(r.stocks.message);
      invalidate();
    } catch (e) {
      toast.error(e instanceof ApiError ? e.message : "Couldn't refresh prices");
    } finally {
      setRefreshing(false);
    }
  }

  async function uploadPriceFile(file: File) {
    const form = new FormData();
    form.append("file", file);
    try {
      const r = await api.post<{ ok: boolean; holdingsUpdated: number; message: string }>("/networth/prices/bhavcopy", form);
      if (r.ok) toast.success(`${r.message}. ${r.holdingsUpdated} holding${r.holdingsUpdated === 1 ? "" : "s"} re-priced.`);
      else toast.error(r.message);
      invalidate();
    } catch (e) {
      toast.error(e instanceof ApiError ? e.message : "Couldn't read that file");
    }
  }

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
        subtitle="Everything you own minus everything you owe. Bank balances, cards and loans come from your accounts. Investments and other assets you add here."
        actions={
          <>
            <Button variant="outline" size="sm" onClick={refreshPrices} disabled={refreshing}><RefreshCw className={refreshing ? "animate-spin" : undefined} /> Refresh prices</Button>
            <Button variant="outline" size="sm" onClick={() => setDialog("import")}><FileUp /> Import holdings</Button>
            <Button variant="outline" size="sm" onClick={() => setDialog("loan")}><Plus /> Add loan</Button>
            <Button size="sm" onClick={() => setDialog("asset")}><Plus /> Add investment or asset</Button>
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
                {s.change30d && <span>Last 30 days <Gain value={s.change30d.amount} pct={s.change30d.pct} /></span>}
                {s.changeYtd && <span>Since {formatDate(s.changeYtd.since, { day: "numeric", month: "short" })} <Gain value={s.changeYtd.amount} pct={s.changeYtd.pct} /></span>}
              </div>
              {s.holdingsValue > 0 && (
                <div className="mt-5 rounded-2xl bg-surface-2 p-4">
                  <p className="text-[13px] text-ink-2">Investments &amp; other assets</p>
                  <p className="num text-xl font-semibold">{inr(s.holdingsValue)}</p>
                  <p className="text-xs text-ink-3">Invested {inr(s.investedInHoldings)} <Gain value={s.holdingsGain} pct={s.holdingsGainPct} className="ml-1" /></p>
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

          {empty && (
            <Panel>
              <EmptyState icon={<Landmark />} title="Add what you own"
                body="Mutual funds, stocks, FDs, PPF, EPF, NPS, gold, property: add them once and Arth keeps the total up to date. Fund NAVs refresh every night from AMFI; stock, ETF and SGB prices every trading evening from NSE/BSE."
                action={<div className="flex flex-wrap justify-center gap-2"><Button onClick={() => setDialog("asset")}>Add investment or asset</Button><Button variant="outline" onClick={() => setDialog("import")}>Import from broker</Button></div>} />
            </Panel>
          )}

          <div className="grid grid-cols-[minmax(0,1fr)] gap-6 lg:grid-cols-[minmax(0,1.4fr)_minmax(0,1fr)]">
            <div className="min-w-0 space-y-4">
              <h2 className="font-display text-lg font-semibold">What you own</h2>
              {s.assetGroups.map((g) => (
                <GroupCard key={g.key} g={g} onAction={(item, action) => {
                  if (item.kind === "ACCOUNT") return;
                  const h = holdings.data?.find((x) => x.id === item.id);
                  if (!h) return;
                  if (action === "update") setUpdateHolding(h);
                  else if (action === "edit") setEditHolding(h);
                  else removeItem(item);
                }} />
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
              <p className="text-xs text-ink-3">{s.pricesNote}{s.estimatedCount > 0 && `. ${s.estimatedCount} value${s.estimatedCount === 1 ? " is an estimate" : "s are estimates"} (marked in yellow).`}</p>
              <p className="text-xs text-ink-3">
                Server can&apos;t reach the exchanges?{" "}
                <label className="cursor-pointer font-semibold text-peacock hover:underline">
                  Upload an NSE/BSE bhavcopy
                  <input type="file" accept=".csv,.zip" className="sr-only" onChange={(e) => { const f = e.target.files?.[0]; if (f) uploadPriceFile(f); e.target.value = ""; }} />
                </label>{" "}
                (CSV or zip from nseindia.com → Market data → All reports).
              </p>
            </div>
          </div>
        </div>
      )}

      {dialog === "asset" && <HoldingDialog open onOpenChange={(o) => !o && setDialog(null)} />}
      {dialog === "loan" && <LiabilityDialog open onOpenChange={(o) => !o && setDialog(null)} />}
      {dialog === "import" && <ImportHoldingsDialog open onOpenChange={(o) => !o && setDialog(null)} />}
      {editHolding && <HoldingDialog holding={editHolding} open onOpenChange={(o) => !o && setEditHolding(null)} />}
      {updateHolding && <UpdateValueDialog holding={updateHolding} open onOpenChange={(o) => !o && setUpdateHolding(null)} />}
      {editLoan && <LiabilityDialog liability={editLoan} open onOpenChange={(o) => !o && setEditLoan(null)} />}
      {confirmDialog}
    </>
  );
}

function GroupCard({ g, negative, onAction }: { g: NetWorthGroup; negative?: boolean; onAction: (item: NetWorthItem, action: "update" | "edit" | "remove") => void }) {
  return (
    <section className="overflow-hidden rounded-3xl border border-line bg-surface">
      <header className="flex flex-wrap items-baseline justify-between gap-x-3 gap-y-1 border-b border-line px-5 py-3.5">
        <h3 className="font-semibold">{g.label}</h3>
        <div className="text-right">
          <span className={cn("num font-semibold", negative && "text-out")}>{inr(g.value)}</span>
          {g.gain !== null && <Gain value={g.gain} pct={g.gainPct} className="ml-2" />}
        </div>
      </header>
      <ul className="divide-y divide-line">
        {g.items.map((i) => (
          <li key={i.id} className="flex items-center gap-3 px-5 py-3">
            <div className="min-w-0 flex-1">
              <p className="truncate text-sm font-medium">{i.kind === "ACCOUNT" ? <Link href="/accounts" className="hover:underline">{i.name}</Link> : i.name}</p>
              <p className="mt-0.5 flex flex-wrap items-center gap-x-2 gap-y-1 text-xs text-ink-3">
                {i.detail && <span>{i.detail}</span>}
                {i.kind === "HOLDING" && <SourceBadge source={i.source} estimated={i.estimated} />}
              </p>
            </div>
            <div className="text-right">
              <p className="num text-sm font-semibold">{inr(i.value)}</p>
              {i.gain !== null && <Gain value={i.gain} pct={i.gainPct} />}
            </div>
            {i.kind !== "ACCOUNT" && (
              <Menu trigger={<Button variant="ghost" size="icon" aria-label={`Actions for ${i.name}`}><MoreHorizontal /></Button>}>
                {i.kind === "HOLDING" && !i.source.startsWith("Estimated") && (
                  <MenuItem onSelect={() => onAction(i, "update")}><RefreshCw className="size-4 text-ink-3" /> Update value</MenuItem>
                )}
                <MenuItem onSelect={() => onAction(i, "edit")}><Pencil className="size-4 text-ink-3" /> Edit</MenuItem>
                <MenuItem danger onSelect={() => onAction(i, "remove")}><Trash2 className="size-4" /> Delete</MenuItem>
              </Menu>
            )}
          </li>
        ))}
      </ul>
    </section>
  );
}
