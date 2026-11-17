"use client";

import { Archive, ChevronDown, Pencil, PiggyBank, Plus, RotateCcw, Trash2 } from "lucide-react";
import { useEffect, useState } from "react";
import { toast } from "sonner";
import { Bar, BarChart, ReferenceLine, ResponsiveContainer, Tooltip, XAxis } from "recharts";
import { PageHeader } from "@/components/layout/app-shell";
import { Meter, Stat } from "@/components/finance/meter";
import { Button } from "@/components/ui/button";
import { useConfirm } from "@/components/ui/confirm";
import { Dialog, DialogContent } from "@/components/ui/dialog";
import { Field, Input, Select } from "@/components/ui/input";
import { EmptyState, ErrorState, Pill, Skeleton } from "@/components/ui/states";
import { useCategories } from "@/hooks/queries";
import { useBudgets, useInvalidatePlanning } from "@/hooks/planning";
import { api, ApiError } from "@/lib/api";
import { inr } from "@/lib/format";
import { cn } from "@/lib/utils";
import type { Budget, BudgetPeriod, BudgetState, Category } from "@/types/api";

const STATE: Record<BudgetState, { label: string; tone: "brand" | "attention" | "out" | "in" }> = {
  ON_TRACK: { label: "On track", tone: "in" },
  AT_RISK: { label: "Heading over", tone: "attention" },
  NEAR_LIMIT: { label: "Near the limit", tone: "attention" },
  OVER: { label: "Over budget", tone: "out" },
};
const PERIOD_LABEL: Record<BudgetPeriod, string> = { WEEKLY: "Weekly", MONTHLY: "Monthly", YEARLY: "Yearly (Apr to Mar)" };

export default function BudgetsPage() {
  const budgets = useBudgets();
  const cats = useCategories();
  const invalidate = useInvalidatePlanning();
  const { confirm, dialog } = useConfirm();
  const [editing, setEditing] = useState<Budget | "new" | null>(null);
  const data = budgets.data;
  const active = data?.budgets.filter((b) => !b.archived) ?? [];
  const archived = data?.budgets.filter((b) => b.archived) ?? [];

  async function act(fn: () => Promise<unknown>, ok: string) {
    try {
      await fn();
      invalidate();
      toast.success(ok);
    } catch (e) {
      toast.error(e instanceof ApiError ? e.message : "Something went wrong");
    }
  }

  async function remove(b: Budget) {
    if (await confirm({ title: `Delete the ${b.name} budget?`, body: "Its alerts stop. Your transactions aren't touched.", confirmLabel: "Delete", danger: true })) {
      act(() => api.del(`/budgets/${b.id}`), "Budget deleted");
    }
  }

  return (
    <>
      <PageHeader title="Budgets" subtitle="Set a limit for the month, week or year, overall or per category. Arth tracks it from your transactions and warns you at 50, 75, 90 and 100%."
        actions={<Button size="sm" onClick={() => setEditing("new")}><Plus /> New budget</Button>} />
      {budgets.isError ? <ErrorState error={budgets.error} retry={() => budgets.refetch()} /> : !data ? <Skeleton className="h-96" /> : active.length === 0 && archived.length === 0 ? (
        <div className="rounded-3xl border border-line bg-surface">
          <EmptyState icon={<PiggyBank />} title="No budgets yet" body="Start with the category you most want to rein in. Arth suggests an amount from your last three months."
            action={<Button onClick={() => setEditing("new")}><Plus /> Create a budget</Button>} />
        </div>
      ) : (
        <>
          <div className="mb-6 flex flex-wrap gap-x-10 gap-y-3">
            <Stat label="Monthly category budgets" value={inr(data.totalBudgeted)} />
            <Stat label="Spent so far" value={inr(data.totalSpent)} />
            <Stat label="Over budget" value={String(data.over)} tone={data.over > 0 ? "out" : undefined} />
            <Stat label="Heading over" value={String(data.atRisk)} tone={data.atRisk > 0 ? "attention" : undefined} />
          </div>
          <div className="grid gap-4 md:grid-cols-2">
            {active.map((b) => <BudgetCard key={b.id} b={b} onEdit={() => setEditing(b)} onArchive={() => act(() => api.post(`/budgets/${b.id}/archive`), "Budget archived")} onDelete={() => remove(b)} />)}
          </div>
          <p className="mt-5 max-w-3xl text-xs text-ink-3">{data.note}</p>
          {archived.length > 0 && (
            <details className="mt-8">
              <summary className="cursor-pointer text-sm font-semibold text-ink-2">Archived budgets ({archived.length})</summary>
              <ul className="mt-3 space-y-2">
                {archived.map((b) => (
                  <li key={b.id} className="flex items-center justify-between rounded-2xl border border-line bg-surface px-4 py-2 text-sm">
                    <span>{b.name} <span className="text-ink-3">{inr(b.amount)} {b.period.toLowerCase()}</span></span>
                    <span className="flex gap-1">
                      <Button variant="ghost" size="sm" onClick={() => act(() => api.post(`/budgets/${b.id}/restore`), "Budget restored")}><RotateCcw /> Restore</Button>
                      <Button variant="ghost" size="sm" className="text-out hover:bg-out-soft hover:text-out" onClick={() => remove(b)}>Delete</Button>
                    </span>
                  </li>
                ))}
              </ul>
            </details>
          )}
        </>
      )}
      {editing && <BudgetDialog budget={editing === "new" ? null : editing} categories={cats.data ?? []} onClose={() => setEditing(null)} />}
      {dialog}
    </>
  );
}

function BudgetCard({ b, onEdit, onArchive, onDelete }: { b: Budget; onEdit: () => void; onArchive: () => void; onDelete: () => void }) {
  const [open, setOpen] = useState(false);
  const s = b.status;
  const daysLeft = b.daysTotal - b.daysElapsed + 1;
  const forecastPct = s.available > 0 ? (s.forecast / s.available) * 100 : null;
  return (
    <article className="rounded-3xl border border-line bg-surface p-5">
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <div className="flex items-center gap-2">
            {b.categoryColor && <span className="size-2.5 shrink-0 rounded-full" style={{ background: b.categoryColor }} aria-hidden />}
            <h2 className="truncate font-display text-lg font-semibold">{b.name}</h2>
          </div>
          <p className="text-[13px] text-ink-3">{b.periodLabel}{b.rollover ? ", rolls over" : ""}</p>
        </div>
        <Pill tone={STATE[s.state].tone}>{STATE[s.state].label}</Pill>
      </div>
      <div className="mt-4 flex items-baseline justify-between gap-2">
        <p className="num text-2xl font-semibold">{inr(s.spent)}<span className="text-base font-medium text-ink-3"> of {inr(s.available)}</span></p>
        <p className="num text-sm font-semibold text-ink-2">{Math.round(s.pctUsed)}%</p>
      </div>
      <Meter className="mt-2" pct={s.pctUsed} ticks={b.alertThresholds} forecastPct={forecastPct} label={`${b.name} budget used`} />
      <p className="mt-3 text-sm text-ink-2">
        {s.remaining >= 0
          ? <>{inr(s.remaining)} left. That&apos;s about <span className="num font-semibold text-ink">{inr(s.dailyAllowance)}</span> a day for {daysLeft} day{daysLeft === 1 ? "" : "s"}.</>
          : <><span className="num font-semibold text-out">{inr(-s.remaining)}</span> over the limit.</>}
      </p>
      {s.projectedOverspend > 0 && s.remaining >= 0 && (
        <p className="mt-1 text-[13px] text-marigold-ink">At this pace you&apos;ll spend about {inr(s.forecast)}, {inr(s.projectedOverspend)} more than planned.</p>
      )}
      {s.carriedOver !== 0 && (
        <p className="mt-1 text-xs text-ink-3">{s.carriedOver > 0 ? `Includes ${inr(s.carriedOver)} left over from last period.` : `Reduced by ${inr(-s.carriedOver)} overspent last period.`}</p>
      )}
      <div className="mt-4 flex items-center gap-1">
        <Button variant="ghost" size="sm" onClick={() => setOpen(!open)} aria-expanded={open}><ChevronDown className={cn("transition-transform", open && "rotate-180")} /> History</Button>
        <Button variant="ghost" size="sm" onClick={onEdit}><Pencil /> Edit</Button>
        <Button variant="ghost" size="sm" onClick={onArchive}><Archive /> Archive</Button>
        <Button variant="ghost" size="icon" className="ml-auto text-out hover:bg-out-soft hover:text-out" aria-label={`Delete ${b.name} budget`} onClick={onDelete}><Trash2 /></Button>
      </div>
      {open && (
        <div className="mt-3 border-t border-line pt-4">
          <div className="h-36">
            <ResponsiveContainer width="100%" height="100%">
              <BarChart data={b.history} margin={{ left: 0, right: 0, top: 6 }}>
                <XAxis dataKey="label" tickLine={false} axisLine={false} tick={{ fontSize: 11, fill: "var(--ink-3)" }}
                  tickFormatter={(l: string) => l.split(" ")[0].slice(0, 3)} />
                <Tooltip cursor={{ fill: "var(--surface-2)" }} formatter={(v) => inr(Number(v))} contentStyle={{ borderRadius: 12, border: "1px solid var(--line)", background: "var(--surface)" }} />
                <ReferenceLine y={b.amount} stroke="var(--marigold)" strokeDasharray="4 4" />
                <Bar dataKey="spent" name="Spent" radius={[6, 6, 0, 0]} fill="var(--peacock-2)" />
              </BarChart>
            </ResponsiveContainer>
          </div>
          <p className="mt-2 text-xs text-ink-3">Dashed line: today&apos;s limit of {inr(b.amount)}. {b.recommended ? `Suggested limit ${inr(b.recommended)}: ${b.recommendationBasis}.` : ""}</p>
        </div>
      )}
    </article>
  );
}

function BudgetDialog({ budget, categories, onClose }: { budget: Budget | null; categories: Category[]; onClose: () => void }) {
  const invalidate = useInvalidatePlanning();
  const [categoryId, setCategoryId] = useState(budget?.categoryId ?? "");
  const [period, setPeriod] = useState<BudgetPeriod>(budget?.period ?? "MONTHLY");
  const [amount, setAmount] = useState(budget ? String(budget.amount) : "");
  const [rollover, setRollover] = useState(budget?.rollover ?? false);
  const [thresholds, setThresholds] = useState(budget?.alertThresholds.join(", ") ?? "50, 75, 90, 100");
  const [suggestion, setSuggestion] = useState<{ recommended: number | null; basis: string } | null>(null);
  const [busy, setBusy] = useState(false);
  const expense = categories.filter((c) => c.kind === "EXPENSE" && !c.archived && !c.investment);

  useEffect(() => {
    if (budget) return;
    let cancelled = false;
    api.get<{ recommended: number | null; basis: string }>("/budgets/suggest", { categoryId: categoryId || undefined, period })
      .then((r) => !cancelled && setSuggestion(r)).catch(() => setSuggestion(null));
    return () => { cancelled = true; };
  }, [categoryId, period, budget]);

  async function save() {
    setBusy(true);
    try {
      const body = { categoryId: categoryId || null, period, amount, rollover, alertThresholds: thresholds, version: budget?.version };
      if (budget) await api.put(`/budgets/${budget.id}`, body);
      else await api.post("/budgets", body);
      invalidate();
      toast.success(budget ? "Budget saved" : "Budget created");
      onClose();
    } catch (e) {
      toast.error(e instanceof ApiError ? e.message : "Couldn't save");
      setBusy(false);
    }
  }

  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent title={budget ? `Edit ${budget.name}` : "New budget"}>
        <div className="space-y-4">
          {!budget && (
            <Field label="What's it for?" htmlFor="b-cat" hint="An overall budget counts all spending, including transactions not yet categorised.">
              <Select id="b-cat" value={categoryId} onChange={(e) => setCategoryId(e.target.value)}>
                <option value="">All spending</option>
                {expense.map((c) => (
                  <optgroup key={c.id} label={c.name}>
                    <option value={c.id}>{c.name} (all)</option>
                    {c.children.filter((ch) => !ch.archived).map((ch) => <option key={ch.id} value={ch.id}>{ch.name}</option>)}
                  </optgroup>
                ))}
              </Select>
            </Field>
          )}
          <Field label="Period" htmlFor="b-period">
            <Select id="b-period" value={period} onChange={(e) => setPeriod(e.target.value as BudgetPeriod)}>
              {(Object.keys(PERIOD_LABEL) as BudgetPeriod[]).map((p) => <option key={p} value={p}>{PERIOD_LABEL[p]}</option>)}
            </Select>
          </Field>
          <Field label="Limit (₹)" htmlFor="b-amount">
            <Input id="b-amount" inputMode="decimal" value={amount} onChange={(e) => setAmount(e.target.value.replace(/[^0-9.]/g, ""))} placeholder="8000" />
          </Field>
          {suggestion && (
            suggestion.recommended ? (
              <button type="button" className="w-full rounded-2xl bg-peacock-soft p-3 text-left text-sm" onClick={() => setAmount(String(suggestion.recommended))}>
                <span className="font-semibold text-peacock">Use {inr(suggestion.recommended)}</span>
                <span className="block text-[13px] text-ink-2">{suggestion.basis}</span>
              </button>
            ) : <p className="text-[13px] text-ink-3">{suggestion.basis}</p>
          )}
          <label className="flex items-start gap-3 text-sm">
            <input type="checkbox" className="mt-1 size-4 accent-[var(--peacock)]" checked={rollover} onChange={(e) => setRollover(e.target.checked)} />
            <span><span className="block font-semibold">Roll over</span><span className="block text-[13px] text-ink-2">Unspent money adds to next period&apos;s limit; overspending takes away from it.</span></span>
          </label>
          <Field label="Alert me at (% used)" htmlFor="b-thr" hint="Comma separated. Each alert is sent once per period.">
            <Input id="b-thr" value={thresholds} onChange={(e) => setThresholds(e.target.value)} />
          </Field>
          <Button className="w-full" disabled={busy || !amount} onClick={save}>{budget ? "Save budget" : "Create budget"}</Button>
        </div>
      </DialogContent>
    </Dialog>
  );
}
