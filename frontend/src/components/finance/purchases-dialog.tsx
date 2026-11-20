"use client";

import { Plus, Trash2 } from "lucide-react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { toast } from "sonner";
import { api, ApiError } from "@/lib/api";
import { formatDate, inr, isoDay } from "@/lib/format";
import { cn } from "@/lib/utils";
import type { Holding } from "@/types/api";
import { useInvalidateWealth } from "@/hooks/queries";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent } from "@/components/ui/dialog";
import { Input, Select } from "@/components/ui/input";
import { ErrorState, Skeleton } from "@/components/ui/states";
import { Gain, XirrBadge } from "@/components/finance/wealth-parts";

type Lot = {
  id: string; date: string; kind: "BUY" | "SELL"; amount: number; units: number | null; price: number | null; source: "MANUAL" | "SIP"; note: string | null;
  valueToday: number | null; gain: number | null; gainPct: number | null; yearlyReturn: number | null;
};
type Year = { year: number; invested: number; valueToday: number | null; gain: number | null; yearlyReturn: number | null; purchases: number };
type LotsView = {
  holdingId: string; holdingName: string; currentPrice: number | null; priceDate: string | null; lots: Lot[]; years: Year[];
  xirr: number | null; boughtTotal: number; investedOnRecord: number | null; coverageNote: string | null;
};
type Draft = { date: string; kind: "BUY" | "SELL"; amount: string; units: string; price: string };

const num = (v: string) => v.replace(/[^0-9.]/g, "");
const day = (d: string) => formatDate(d, { day: "numeric", month: "short", year: "numeric" });
const blank = (): Draft => ({ date: "", kind: "BUY", amount: "", units: "", price: "" });

function Pct({ v }: { v: number | null }) {
  if (v == null) return <span className="text-ink-3">—</span>;
  return <span className={cn("num font-semibold", v >= 0 ? "text-in" : "text-out")}>{v >= 0 ? "+" : ""}{v}%</span>;
}

/**
 * Each purchase on its own date and price: exact XIRR, how each purchase has done (gain and its own yearly return),
 * and how the money put in each year has done.
 */
export function PurchasesDialog({ holding, open, onOpenChange }: { holding: Holding; open: boolean; onOpenChange: (o: boolean) => void }) {
  const qc = useQueryClient();
  const invalidate = useInvalidateWealth();
  const key = ["lots", holding.id];
  const view = useQuery({ queryKey: key, queryFn: () => api.get<LotsView>(`/networth/holdings/${holding.id}/lots`) });
  const [drafts, setDrafts] = useState<Draft[]>([blank()]);
  const [setTotals, setSetTotals] = useState(false);
  const [busy, setBusy] = useState(false);
  const v = view.data;
  const setDraft = (i: number, p: Partial<Draft>) => setDrafts(drafts.map((d, j) => (j === i ? { ...d, ...p } : d)));
  const ready = drafts.filter((d) => d.date && (d.amount || (d.units && d.price)));

  async function save() {
    setBusy(true);
    try {
      const r = await api.post<LotsView>(`/networth/holdings/${holding.id}/lots`, {
        setTotals,
        lots: ready.map((d) => ({ date: d.date, kind: d.kind, amount: d.amount || null, units: d.units || null, price: d.price || null })),
      });
      qc.setQueryData(key, r);
      invalidate();
      setDrafts([blank()]);
      toast.success(`${ready.length} ${ready.length === 1 ? "entry" : "entries"} added`);
    } catch (e) {
      toast.error(e instanceof ApiError ? e.message : "Couldn't save");
    } finally {
      setBusy(false);
    }
  }

  async function remove(lot: Lot) {
    try {
      const r = await api.del<LotsView>(`/networth/lots/${lot.id}`);
      qc.setQueryData(key, r);
      invalidate();
    } catch (e) {
      toast.error(e instanceof ApiError ? e.message : "Couldn't delete");
    }
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent wide title={`Purchases & returns: ${holding.name}`}
        description="Add each purchase on its own date and price. Arth works out your exact XIRR and how the money put in at each stage has done.">
        {view.isError ? <ErrorState error={view.error} retry={() => view.refetch()} /> : !v ? <Skeleton className="h-40" /> : (
          <div className="space-y-5">
            <div className="flex flex-wrap items-center gap-x-5 gap-y-1 rounded-2xl bg-surface-2 px-4 py-3 text-sm">
              {v.xirr != null ? <span className="inline-flex items-center gap-2">Average yearly return <XirrBadge value={v.xirr} /></span>
                : <span className="text-ink-2">Add purchases to see your XIRR.</span>}
              {v.currentPrice != null && <span className="text-xs text-ink-3">Valued at ₹{v.currentPrice}{v.priceDate ? ` (${day(v.priceDate)})` : ""}</span>}
              {v.coverageNote && <span className="basis-full text-xs text-marigold-ink">{v.coverageNote}</span>}
            </div>

            {v.years.length > 0 && (
              <section>
                <h3 className="mb-2 text-sm font-semibold">By year of buying</h3>
                <div className="overflow-x-auto rounded-2xl border border-line">
                  <table className="w-full text-sm">
                    <thead className="text-xs text-ink-3"><tr className="border-b border-line">
                      <th className="px-3 py-2 text-left font-medium">Bought in</th><th className="px-3 py-2 text-right font-medium">Put in</th>
                      <th className="px-3 py-2 text-right font-medium">Worth today</th><th className="px-3 py-2 text-right font-medium">Gain</th>
                      <th className="px-3 py-2 text-right font-medium">A year (XIRR)</th>
                    </tr></thead>
                    <tbody className="divide-y divide-line">
                      {v.years.map((y) => (
                        <tr key={y.year}>
                          <td className="px-3 py-2">{y.year} <span className="text-xs text-ink-3">· {y.purchases}</span></td>
                          <td className="num px-3 py-2 text-right">{inr(y.invested)}</td>
                          <td className="num px-3 py-2 text-right">{y.valueToday != null ? inr(y.valueToday) : "—"}</td>
                          <td className="px-3 py-2 text-right">{y.gain != null ? <Gain value={y.gain} pct={null} /> : "—"}</td>
                          <td className="px-3 py-2 text-right"><Pct v={y.yearlyReturn} /></td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
                <p className="mt-1 text-xs text-ink-3">How the money you put in each year has done since, at today&apos;s price. Needs units (or price) for each purchase.</p>
              </section>
            )}

            {v.lots.length > 0 && (
              <section>
                <h3 className="mb-2 text-sm font-semibold">Each purchase</h3>
                <div className="max-h-72 overflow-auto rounded-2xl border border-line">
                  <table className="w-full text-sm">
                    <thead className="sticky top-0 bg-surface text-xs text-ink-3"><tr className="border-b border-line">
                      <th className="px-3 py-2 text-left font-medium">Date</th><th className="px-3 py-2 text-right font-medium">Amount</th>
                      <th className="px-3 py-2 text-right font-medium">Units @ price</th><th className="px-3 py-2 text-right font-medium">Worth today</th>
                      <th className="px-3 py-2 text-right font-medium">A year</th><th className="w-8" />
                    </tr></thead>
                    <tbody className="divide-y divide-line">
                      {v.lots.map((l) => (
                        <tr key={l.id}>
                          <td className="px-3 py-2">{day(l.date)} <span className={cn("ml-1 rounded px-1 text-[10px] font-semibold", l.kind === "SELL" ? "bg-out/10 text-out" : "bg-surface-2 text-ink-3")}>{l.kind === "SELL" ? "SOLD" : l.source === "SIP" ? "SIP" : "BUY"}</span></td>
                          <td className="num px-3 py-2 text-right">{inr(l.amount)}</td>
                          <td className="num px-3 py-2 text-right text-xs text-ink-2">{l.units != null ? `${+Number(l.units).toFixed(3)}` : "—"}{l.price != null ? ` @ ₹${+Number(l.price).toFixed(2)}` : ""}</td>
                          <td className="px-3 py-2 text-right">{l.valueToday != null ? <><span className="num">{inr(l.valueToday)}</span><br /><Gain value={l.gain} pct={l.gainPct} /></> : <span className="text-ink-3">—</span>}</td>
                          <td className="px-3 py-2 text-right"><Pct v={l.yearlyReturn} /></td>
                          <td className="px-1 text-right">{l.source === "MANUAL" && (
                            <button type="button" className="rounded p-1 text-ink-3 hover:bg-surface-2 hover:text-out" aria-label={`Delete purchase on ${day(l.date)}`} onClick={() => remove(l)}><Trash2 className="size-4" /></button>
                          )}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </section>
            )}

            <section className="rounded-2xl border border-dashed border-line p-3">
              <h3 className="mb-2 text-sm font-semibold">Add purchases</h3>
              <div className="space-y-2">
                {drafts.map((d, i) => (
                  <div key={i} className="grid grid-cols-2 gap-2 sm:grid-cols-[1.2fr_0.9fr_1fr_0.9fr_0.9fr]">
                    <Input type="date" aria-label="Date" value={d.date} max={isoDay(new Date())} onChange={(e) => setDraft(i, { date: e.target.value })} />
                    <Select aria-label="Bought or sold" value={d.kind} onChange={(e) => setDraft(i, { kind: e.target.value as "BUY" | "SELL" })}>
                      <option value="BUY">Bought</option><option value="SELL">Sold</option>
                    </Select>
                    <Input inputMode="decimal" className="num" placeholder="Amount ₹" aria-label="Amount" value={d.amount} onChange={(e) => setDraft(i, { amount: num(e.target.value) })} />
                    <Input inputMode="decimal" className="num" placeholder="Units" aria-label="Units" value={d.units} onChange={(e) => setDraft(i, { units: num(e.target.value) })} />
                    <Input inputMode="decimal" className="num" placeholder="Price ₹" aria-label="Price per unit" value={d.price} onChange={(e) => setDraft(i, { price: num(e.target.value) })} />
                  </div>
                ))}
              </div>
              <button type="button" className="mt-2 inline-flex items-center gap-1 text-sm font-medium text-peacock hover:underline" onClick={() => setDrafts([...drafts, blank()])}>
                <Plus className="size-4" /> Another row
              </button>
              <p className="mt-2 text-xs text-ink-3">Amount alone is enough for XIRR; add units or price per unit to see how each purchase has done. Your account statement or the fund&apos;s CAS lists these.</p>
              <label className="mt-3 flex items-start gap-2 text-sm">
                <input type="checkbox" className="mt-1" checked={setTotals} onChange={(e) => setSetTotals(e.target.checked)} />
                <span>These are all my purchases: set the units, amount put in and start date from them</span>
              </label>
              <Button className="mt-3 w-full" disabled={busy || ready.length === 0} onClick={save}>
                {ready.length > 1 ? `Add ${ready.length} entries` : "Add purchase"}
              </Button>
            </section>
          </div>
        )}
      </DialogContent>
    </Dialog>
  );
}
