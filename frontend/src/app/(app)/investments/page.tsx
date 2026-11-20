"use client";

import { Briefcase, FileUp, Pause, Pencil, Play, Plus, RefreshCw, Repeat, Trash2, TrendingUp } from "lucide-react";
import { useState } from "react";
import { toast } from "sonner";
import { PageHeader } from "@/components/layout/app-shell";
import { canFetchPrice, HoldingDialog, ImportHoldingsDialog, UpdateValueDialog } from "@/components/finance/networth-dialogs";
import { InstallmentLine, PfDialog, planKindFor, PlanDialog, planSummary } from "@/components/finance/plan-dialogs";
import { DayChange, Gain, GroupCard, XirrBadge, type ItemAction } from "@/components/finance/wealth-parts";
import { XirrDialog } from "@/components/finance/xirr-dialog";
import { PurchasesDialog } from "@/components/finance/purchases-dialog";
import { Button } from "@/components/ui/button";
import { useConfirm } from "@/components/ui/confirm";
import { Menu, MenuItem } from "@/components/ui/menu";
import { EmptyState, ErrorState, Panel, Skeleton } from "@/components/ui/states";
import { useHoldings, useInvalidateWealth, useNetWorth, usePlans, usePriceStatus } from "@/hooks/queries";
import { api, ApiError } from "@/lib/api";
import { formatDate, inr } from "@/lib/format";
import type { Holding, InvestmentPlan, NetWorthItem } from "@/types/api";

const short = (d: string | null) => (d ? formatDate(d, { day: "numeric", month: "short" }) : "—");

export default function InvestmentsPage() {
  const nw = useNetWorth();
  const holdings = useHoldings();
  const plans = usePlans();
  const status = usePriceStatus();
  const invalidate = useInvalidateWealth();
  const { confirm, dialog: confirmDialog } = useConfirm();
  const [dialog, setDialog] = useState<null | "asset" | "import" | "pf">(null);
  const [editHolding, setEditHolding] = useState<Holding | null>(null);
  const [updateHolding, setUpdateHolding] = useState<Holding | null>(null);
  const [planFor, setPlanFor] = useState<{ holding: Holding; plan?: InvestmentPlan } | null>(null);
  const [xirrFor, setXirrFor] = useState<Holding | null>(null);
  const [lotsFor, setLotsFor] = useState<Holding | null>(null);
  const [refreshing, setRefreshing] = useState(false);
  const s = nw.data;
  const groups = (s?.assetGroups ?? []).filter((g) => g.items.some((i) => i.kind === "HOLDING"));
  const byId = (id: string) => holdings.data?.find((h) => h.id === id);
  const plansFor = (holdingId: string) => (plans.data ?? []).filter((p) => p.holdingId === holdingId);

  async function refreshPrices() {
    setRefreshing(true);
    try {
      type Part = { ok: boolean; message: string };
      const r = await api.post<{ funds: Part; stocks: Part }>("/networth/prices/refresh");
      for (const part of [r.funds, r.stocks]) {
        if (!part.ok) toast.error(part.message);
        else toast(part.message, { duration: 9000 });
      }
      invalidate();
    } catch (e) {
      toast.error(e instanceof ApiError ? e.message : "Couldn't refresh prices");
    } finally {
      setRefreshing(false);
    }
  }

  async function removeHolding(item: NetWorthItem) {
    const ok = await confirm({ title: `Delete ${item.name}?`, body: "It's removed from your investments and net worth, with any SIP or contribution set up for it. Past net worth snapshots keep their numbers.", confirmLabel: "Delete", danger: true });
    if (!ok) return;
    try {
      await api.del(`/networth/holdings/${item.id}`);
      invalidate();
      toast.success("Deleted");
    } catch (e) {
      toast.error(e instanceof ApiError ? e.message : "Couldn't remove");
    }
  }

  async function togglePlan(p: InvestmentPlan) {
    try {
      await api.put(`/networth/plans/${p.id}`, { active: !p.active, version: p.version });
      invalidate();
      toast.success(p.active ? "Paused" : "Resumed");
    } catch (e) {
      toast.error(e instanceof ApiError ? e.message : "Couldn't change it");
    }
  }

  async function deletePlan(p: InvestmentPlan) {
    const ok = await confirm({ title: `Stop this ${p.kind === "SIP" ? "SIP" : "contribution"}?`, body: "Nothing more is added from now on. What was already added stays in the investment and the bank account.", confirmLabel: "Stop and delete", danger: true });
    if (!ok) return;
    try {
      await api.del(`/networth/plans/${p.id}`);
      invalidate();
      toast.success("Deleted");
    } catch (e) {
      toast.error(e instanceof ApiError ? e.message : "Couldn't delete");
    }
  }

  function onAction(item: NetWorthItem, action: ItemAction) {
    const h = byId(item.id);
    if (!h) return;
    if (action === "update") setUpdateHolding(h);
    else if (action === "edit") setEditHolding(h);
    else if (action === "plan") setPlanFor({ holding: h });
    else if (action === "xirr") setXirrFor(h);
    else if (action === "lots") setLotsFor(h);
    else removeHolding(item);
  }

  const st = status.data;
  const hasFunds = (holdings.data ?? []).some((h) => h.assetClass === "MUTUAL_FUND");
  const hasStocks = (holdings.data ?? []).some((h) => ["STOCK", "ETF", "SGB", "BOND"].includes(h.assetClass));

  return (
    <>
      <PageHeader
        title="Investments"
        subtitle="Mutual funds, stocks, SIPs, PF and everything else you've invested in. All of it counts in your net worth."
        actions={
          <>
            <Button variant="outline" size="sm" onClick={refreshPrices} disabled={refreshing}><RefreshCw className={refreshing ? "animate-spin" : undefined} /> Refresh prices</Button>
            <Button variant="outline" size="sm" onClick={() => setDialog("import")}><FileUp /> Import holdings</Button>
            <Button size="sm" onClick={() => setDialog("asset")}><Plus /> Add investment</Button>
          </>
        }
      />

      {/* where the prices come from, and as of when */}
      <div className="-mt-3 mb-5 rounded-2xl bg-surface-2 px-4 py-3 text-[13px] text-ink-2">
        <div className="flex flex-wrap gap-x-5 gap-y-1">
          <span><strong className="text-ink">Fund NAVs as of {short(st?.latestNavDate ?? null)}</strong>: refreshed automatically every evening once AMFI publishes the day&apos;s NAV.</span>
          <span><strong className="text-ink">Stock prices as of {short(st?.latestCloseDate ?? null)} close</strong>: on working days, the value after 3:45 pm is that day&apos;s closing price.</span>
        </div>
        <p className="mt-1.5 text-xs text-ink-3">
          Tap Refresh any time: stock prices update after 3:45 pm on working days and fund NAVs once AMFI publishes them. Until then you see the last values, with their dates above.
          Arth only downloads when newer prices exist (at most once before 9:15 am and once after 3:45 pm).
        </p>
      </div>

      {nw.isError ? <ErrorState error={nw.error} retry={() => nw.refetch()} /> : !s ? <Skeleton className="h-96" /> : (
        <div className="space-y-6">
          <section className="grid gap-4 sm:grid-cols-3">
            <div className="rounded-3xl border border-line bg-surface p-5 sm:col-span-1">
              <p className="text-sm text-ink-2">Invested value today</p>
              <p className="num mt-1 text-3xl font-semibold tracking-tight">{inr(s.holdingsValue)}</p>
              <p className="mt-1 text-xs text-ink-3">You put in {inr(s.investedInHoldings)} <Gain value={s.holdingsGain} pct={s.holdingsGainPct} className="ml-1" /></p>
              <p className="mt-2 flex flex-wrap items-center gap-2 text-xs text-ink-2">
                {s.holdingsXirr != null ? (
                  <><XirrBadge value={s.holdingsXirr} /> <span className="text-ink-3">based on {s.holdingsXirrCount} of {s.holdingsCount} {s.holdingsCount === 1 ? "holding" : "holdings"}</span></>
                ) : (
                  <span className="text-ink-3">Tap &ldquo;Show XIRR&rdquo; on a holding to see your yearly return.</span>
                )}
              </p>
              {s.holdingsDayChange != null ? (
                <p className="mt-2 flex items-center gap-2 text-xs text-ink-2">Latest day <DayChange value={s.holdingsDayChange} pct={s.holdingsDayChangePct} /></p>
              ) : (
                <p className="mt-2 text-xs text-ink-3">The day&apos;s change shows once the next NAV/close is loaded.</p>
              )}
            </div>
            <div className="grid grid-cols-2 gap-3 sm:col-span-2 sm:grid-cols-4">
              <QuickAdd icon={<TrendingUp />} label="Fund or stock" onClick={() => setDialog("asset")} />
              <QuickAdd icon={<Repeat />} label="SIP" onClick={() => {
                const fund = (holdings.data ?? []).find((h) => planKindFor(h) === "SIP");
                if (fund) setPlanFor({ holding: fund });
                else toast("Add the fund first (Fund or stock), then set up its SIP.");
              }} />
              <QuickAdd icon={<Briefcase />} label="PF / EPF" onClick={() => setDialog("pf")} />
              <QuickAdd icon={<FileUp />} label="Import from Zerodha, Groww…" onClick={() => setDialog("import")} />
            </div>
          </section>

          {groups.length > 0 && (
            <section className="overflow-hidden rounded-3xl border border-line bg-surface" aria-label="Investments by type">
              <div className="grid grid-cols-[minmax(0,1.4fr)_minmax(0,1fr)_minmax(0,1fr)] gap-3 border-b border-line px-5 py-2.5 text-xs font-medium text-ink-3 sm:grid-cols-[minmax(0,1.4fr)_repeat(4,minmax(0,1fr))]">
                <span>By type</span>
                <span className="text-right">Value</span>
                <span className="hidden text-right sm:block">Overall gain</span>
                <span className="hidden text-right sm:block">XIRR</span>
                <span className="text-right">Latest day</span>
              </div>
              <ul className="divide-y divide-line">
                {groups.map((g) => (
                  <li key={g.key} className="grid grid-cols-[minmax(0,1.4fr)_minmax(0,1fr)_minmax(0,1fr)] items-center gap-3 px-5 py-3 text-sm sm:grid-cols-[minmax(0,1.4fr)_repeat(4,minmax(0,1fr))]">
                    <span className="min-w-0">
                      <span className="block truncate font-medium">{g.label}</span>
                      <span className="block text-xs text-ink-3">{g.items.length} {g.items.length === 1 ? "holding" : "holdings"}{g.invested != null ? ` · put in ${inr(g.invested)}` : ""}</span>
                    </span>
                    <span className="num text-right font-semibold">
                      {inr(g.value)}
                      <span className="block sm:hidden">{g.gain != null && <Gain value={g.gain} pct={g.gainPct} />}</span>
                    </span>
                    <span className="hidden text-right sm:block">{g.gain != null ? <Gain value={g.gain} pct={g.gainPct} /> : <span className="text-xs text-ink-3">—</span>}</span>
                    <span className="hidden text-right sm:block">
                      {g.xirr != null ? <><XirrBadge value={g.xirr} />{g.xirrCount < g.items.length && <span className="block text-[11px] text-ink-3">{g.xirrCount} of {g.items.length}</span>}</> : <span className="text-xs text-ink-3">—</span>}
                    </span>
                    <span className="text-right">{g.dayChange != null ? <DayChange value={g.dayChange} pct={g.dayChangePct} /> : <span className="text-xs text-ink-3">{g.key === "MUTUAL_FUND" || g.key === "STOCK" || g.key === "ETF" ? "after next update" : "—"}</span>}</span>
                  </li>
                ))}
              </ul>
            </section>
          )}

          {groups.length === 0 && (
            <Panel>
              <EmptyState icon={<TrendingUp />} title="Add your investments"
                body="Mutual funds, stocks, SIPs, PF, PPF, NPS, FDs, gold: add them once and Arth keeps the value up to date and adds it to your net worth."
                action={<div className="flex flex-wrap justify-center gap-2"><Button onClick={() => setDialog("asset")}>Add investment</Button><Button variant="outline" onClick={() => setDialog("pf")}>Add PF</Button><Button variant="outline" onClick={() => setDialog("import")}>Import from broker</Button></div>} />
            </Panel>
          )}

          {(plans.data?.length ?? 0) > 0 && (
            <section className="space-y-3">
              <h2 className="font-display text-lg font-semibold">SIPs &amp; monthly contributions</h2>
              <div className="grid gap-3 md:grid-cols-2">
                {plans.data!.map((p) => (
                  <article key={p.id} className="rounded-3xl border border-line bg-surface p-5">
                    <div className="flex items-start gap-3">
                      <div className="min-w-0 flex-1">
                        <p className="truncate text-sm font-semibold">{p.holdingName}</p>
                        <p className="mt-0.5 text-xs text-ink-2">{p.kind === "SIP" ? "SIP" : "Contribution"}: {planSummary(p)}</p>
                        {p.employerAmount ? <p className="text-xs text-ink-3">Yours {inr(p.amount)} + employer {inr(p.employerAmount)}</p> : null}
                      </div>
                      <Menu trigger={<Button variant="ghost" size="icon" aria-label={`Actions for ${p.holdingName} plan`}><MoreIcon /></Button>}>
                        <MenuItem onSelect={() => { const h = byId(p.holdingId); if (h) setPlanFor({ holding: h, plan: p }); }}><Pencil className="size-4 text-ink-3" /> Change</MenuItem>
                        <MenuItem onSelect={() => togglePlan(p)}>{p.active ? <><Pause className="size-4 text-ink-3" /> Pause</> : <><Play className="size-4 text-ink-3" /> Resume</>}</MenuItem>
                        <MenuItem danger onSelect={() => deletePlan(p)}><Trash2 className="size-4" /> Stop and delete</MenuItem>
                      </Menu>
                    </div>
                    <p className="mt-3 text-xs">
                      {!p.active ? <span className="rounded-full bg-surface-2 px-2 py-0.5 text-ink-2">Paused</span>
                        : p.waiting ? <span className="text-marigold-ink">{p.waiting}</span>
                          : p.nextDue ? <span className="text-ink-2">Next on <strong className="text-ink">{formatDate(p.nextDue, { weekday: "short", day: "numeric", month: "short" })}</strong></span> : <span className="text-ink-3">Ended</span>}
                    </p>
                    {p.installments.length > 0 && (
                      <ul className="mt-3 space-y-1 border-t border-line pt-3">
                        {p.installments.map((i) => <InstallmentLine key={i.dueDate} i={i} sip={p.kind === "SIP"} />)}
                      </ul>
                    )}
                  </article>
                ))}
              </div>
            </section>
          )}

          <section className="space-y-4">
            {groups.length > 0 && <h2 className="font-display text-lg font-semibold">What you hold</h2>}
            <div className="grid grid-cols-[minmax(0,1fr)] gap-4 lg:grid-cols-2">
              {groups.map((g) => (
                <GroupCard key={g.key} g={g} onAction={onAction}
                  fetchable={(id) => { const h = byId(id); return !!h && canFetchPrice(h); }}
                  planLabel={(id) => {
                    const h = byId(id);
                    if (!h) return null;
                    const kind = planKindFor(h);
                    if (!kind) return null;
                    const existing = plansFor(id).length;
                    return kind === "SIP" ? (existing ? "Add another SIP" : "Set up SIP") : (existing ? "Add another contribution" : "Add monthly contribution");
                  }} />
              ))}
            </div>
            {(hasFunds || hasStocks) && (
              <p className="text-xs text-ink-3">
                Amounts with &ldquo;Broker export&rdquo; use the price from the file you imported until the next official NAV or close arrives.
              </p>
            )}
          </section>
        </div>
      )}

      {dialog === "asset" && <HoldingDialog open onOpenChange={(o) => !o && setDialog(null)} />}
      {dialog === "import" && <ImportHoldingsDialog open onOpenChange={(o) => !o && setDialog(null)} />}
      {dialog === "pf" && <PfDialog open onOpenChange={(o) => !o && setDialog(null)} />}
      {editHolding && <HoldingDialog holding={editHolding} open onOpenChange={(o) => !o && setEditHolding(null)} />}
      {updateHolding && <UpdateValueDialog holding={updateHolding} open onOpenChange={(o) => !o && setUpdateHolding(null)} />}
      {planFor && <PlanDialog holding={planFor.holding} plan={planFor.plan} open onOpenChange={(o) => !o && setPlanFor(null)} />}
      {xirrFor && <XirrDialog holding={xirrFor} open onOpenChange={(o) => !o && setXirrFor(null)} onPurchases={() => { setLotsFor(xirrFor); setXirrFor(null); }} />}
      {lotsFor && <PurchasesDialog holding={lotsFor} open onOpenChange={(o) => !o && setLotsFor(null)} />}
      {confirmDialog}
    </>
  );
}

function MoreIcon() {
  return <svg viewBox="0 0 24 24" className="size-4" aria-hidden><circle cx="5" cy="12" r="1.6" fill="currentColor" /><circle cx="12" cy="12" r="1.6" fill="currentColor" /><circle cx="19" cy="12" r="1.6" fill="currentColor" /></svg>;
}

function QuickAdd({ icon, label, onClick }: { icon: React.ReactNode; label: string; onClick: () => void }) {
  return (
    <button type="button" onClick={onClick}
      className="flex flex-col items-start gap-2 rounded-3xl border border-line bg-surface p-4 text-left text-sm font-medium transition-colors hover:border-peacock-2/60 hover:bg-surface-2 [&_svg]:size-5 [&_svg]:text-peacock">
      {icon}
      <span><Plus className="mr-1 inline size-3.5 !text-ink-3" />{label}</span>
    </button>
  );
}
