"use client";

import { Info, Link2, Pencil, Plus, Target, Trash2, X } from "lucide-react";
import { useState } from "react";
import { toast } from "sonner";
import { PageHeader } from "@/components/layout/app-shell";
import { Button } from "@/components/ui/button";
import { useConfirm } from "@/components/ui/confirm";
import { Dialog, DialogContent } from "@/components/ui/dialog";
import { Field, Input, Select } from "@/components/ui/input";
import { EmptyState, ErrorState, Pill, Skeleton } from "@/components/ui/states";
import { useHoldings } from "@/hooks/queries";
import { useGoals, useInvalidatePlanning } from "@/hooks/planning";
import { api, ApiError } from "@/lib/api";
import { formatDate, inr, isoDay } from "@/lib/format";
import { cn } from "@/lib/utils";
import type { Goal, GoalKind, GoalProjection } from "@/types/api";

const KIND: Record<GoalKind, string> = {
  EMERGENCY_FUND: "Emergency fund", HOUSE: "Home", CAR: "Car", TRAVEL: "Travel", EDUCATION: "Education", RETIREMENT: "Retirement",
  WEDDING: "Wedding", GADGET: "Gadget", DEBT_PAYOFF: "Pay off a loan", OTHER: "Something else",
};
const STATE: Record<GoalProjection["state"], { label: string; tone: "in" | "attention" | "out" | "neutral" | "brand" }> = {
  ACHIEVED: { label: "Reached", tone: "in" }, ON_TRACK: { label: "On track", tone: "in" }, BEHIND: { label: "Behind", tone: "attention" },
  IN_PROGRESS: { label: "In progress", tone: "brand" }, NO_CONTRIBUTIONS: { label: "No recent contributions", tone: "neutral" },
};

/** Ring progress: one arc, no decoration. */
function Ring({ pct }: { pct: number }) {
  const r = 26, c = 2 * Math.PI * r, p = Math.max(0, Math.min(100, pct));
  return (
    <svg viewBox="0 0 64 64" className="size-16 shrink-0 -rotate-90" aria-hidden>
      <circle cx="32" cy="32" r={r} fill="none" stroke="var(--surface-2)" strokeWidth="7" />
      <circle cx="32" cy="32" r={r} fill="none" stroke={p >= 100 ? "var(--in)" : "var(--peacock-2)"} strokeWidth="7" strokeLinecap="round"
        strokeDasharray={`${(p / 100) * c} ${c}`} />
    </svg>
  );
}

export default function GoalsPage() {
  const goals = useGoals();
  const invalidate = useInvalidatePlanning();
  const { confirm, dialog } = useConfirm();
  const [editing, setEditing] = useState<Goal | "new" | null>(null);
  const [adding, setAdding] = useState<Goal | null>(null);
  const [linking, setLinking] = useState<Goal | null>(null);

  async function act(fn: () => Promise<unknown>, ok: string) {
    try {
      await fn();
      invalidate();
      toast.success(ok);
    } catch (e) {
      toast.error(e instanceof ApiError ? e.message : "Something went wrong");
    }
  }

  const list = goals.data ?? [];
  return (
    <>
      <PageHeader title="Goals" subtitle="Save toward something specific. Add money as you put it aside, or link an investment that's meant for it, and see what it takes to get there on time."
        actions={<Button size="sm" onClick={() => setEditing("new")}><Plus /> New goal</Button>} />
      {goals.isError ? <ErrorState error={goals.error} retry={() => goals.refetch()} /> : !goals.data ? <Skeleton className="h-96" /> : list.length === 0 ? (
        <div className="rounded-3xl border border-line bg-surface">
          <EmptyState icon={<Target />} title="No goals yet" body="An emergency fund of six months' spending is a good first one. Arth can work out the amount from your history."
            action={<Button onClick={() => setEditing("new")}><Plus /> Set a goal</Button>} />
        </div>
      ) : (
        <div className="grid gap-4 lg:grid-cols-2">
          {list.map((g) => (
            <GoalCard key={g.id} g={g} onEdit={() => setEditing(g)} onAdd={() => setAdding(g)} onLink={() => setLinking(g)}
              onDelete={async () => { if (await confirm({ title: `Delete ${g.name}?`, body: "The goal and its contribution records are removed. Linked investments aren't touched.", confirmLabel: "Delete", danger: true })) act(() => api.del(`/goals/${g.id}`), "Goal deleted"); }}
              onDeleteContribution={async (cid) => { if (await confirm({ title: "Delete this entry?", confirmLabel: "Delete", danger: true })) act(() => api.del(`/goals/${g.id}/contributions/${cid}`), "Entry deleted"); }}
              onUnlink={(hid) => act(() => api.del(`/goals/${g.id}/holdings/${hid}`), "Unlinked")} />
          ))}
        </div>
      )}
      {editing && <GoalDialog goal={editing === "new" ? null : editing} onClose={() => setEditing(null)} />}
      {adding && <ContributionDialog goal={adding} onClose={() => setAdding(null)} />}
      {linking && <LinkDialog goal={linking} onClose={() => setLinking(null)} />}
      {dialog}
    </>
  );
}

function GoalCard({ g, onEdit, onAdd, onLink, onDelete, onDeleteContribution, onUnlink }: {
  g: Goal; onEdit: () => void; onAdd: () => void; onLink: () => void; onDelete: () => void; onDeleteContribution: (id: string) => void; onUnlink: (id: string) => void;
}) {
  const p = g.projection;
  const [why, setWhy] = useState(false);
  return (
    <article className={cn("rounded-3xl border border-line bg-surface p-5", g.status !== "ACTIVE" && "opacity-70")}>
      <div className="flex items-start gap-4">
        <div className="relative">
          <Ring pct={p.progressPct} />
          <span className="num absolute inset-0 grid place-items-center text-sm font-semibold">{Math.round(p.progressPct)}%</span>
        </div>
        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-center gap-2">
            <h2 className="font-display text-lg font-semibold">{g.name}</h2>
            <Pill tone={STATE[p.state].tone}>{STATE[p.state].label}</Pill>
            {g.status !== "ACTIVE" && <Pill>{g.status.toLowerCase()}</Pill>}
          </div>
          <p className="text-[13px] text-ink-3">{KIND[g.kind]}{g.targetDate ? `, by ${formatDate(g.targetDate, { month: "short", year: "numeric" })}` : ""}</p>
          <p className="num mt-2 text-xl font-semibold">{inr(p.saved)}<span className="text-sm font-medium text-ink-3"> of {inr(g.targetAmount)}</span></p>
        </div>
      </div>
      <div className="mt-4 grid grid-cols-2 gap-3 text-sm">
        {p.requiredMonthly !== null && p.remaining > 0 && (
          <div className="rounded-2xl bg-surface-2 p-3">
            <p className="text-[13px] text-ink-2">Needed each month</p>
            <p className="num font-semibold">{inr(p.requiredMonthly)}</p>
            <p className="text-xs text-ink-3">for {p.monthsLeft} month{p.monthsLeft === 1 ? "" : "s"}</p>
          </div>
        )}
        <div className="rounded-2xl bg-surface-2 p-3">
          <p className="text-[13px] text-ink-2">At your recent pace</p>
          <p className="font-semibold">{p.state === "ACHIEVED" ? "Done" : p.projectedDate ? formatDate(p.projectedDate, { month: "short", year: "numeric" }) : "Not on current savings"}</p>
          <p className="text-xs text-ink-3">{inr(p.recentMonthly)} a month lately</p>
        </div>
      </div>
      {g.holdings.length > 0 && (
        <ul className="mt-3 space-y-1 text-[13px]">
          {g.holdings.map((h) => (
            <li key={h.holdingId} className="flex items-center gap-2">
              <Link2 className="size-3.5 text-ink-3" />
              <span className="min-w-0 flex-1 truncate">{h.name}{h.sharePct < 100 ? ` (${h.sharePct}%)` : ""}</span>
              <span className="num">{inr(h.counted)}{h.estimated ? " est." : ""}</span>
              <button className="rounded-full p-1 text-ink-3 hover:bg-surface-2" aria-label={`Unlink ${h.name}`} onClick={() => onUnlink(h.holdingId)}><X className="size-3" /></button>
            </li>
          ))}
        </ul>
      )}
      <div className="mt-4 flex flex-wrap items-center gap-1">
        <Button size="sm" onClick={onAdd}><Plus /> Add money</Button>
        <Button variant="ghost" size="sm" onClick={onLink}><Link2 /> Link investment</Button>
        <Button variant="ghost" size="sm" onClick={onEdit}><Pencil /> Edit</Button>
        <Button variant="ghost" size="icon" onClick={() => setWhy(!why)} aria-label="How this is worked out" title="How this is worked out"><Info /></Button>
        <Button variant="ghost" size="icon" className="ml-auto text-out hover:bg-out-soft hover:text-out" aria-label={`Delete ${g.name}`} onClick={onDelete}><Trash2 /></Button>
      </div>
      {why && <p className="mt-2 rounded-2xl bg-surface-2 p-3 text-[13px] text-ink-2">{g.explanation}</p>}
      {g.contributions.length > 0 && (
        <details className="mt-3">
          <summary className="cursor-pointer text-sm font-semibold text-ink-2">Entries ({g.contributions.length})</summary>
          <ul className="mt-2 divide-y divide-line text-sm">
            {g.contributions.map((c) => (
              <li key={c.id} className="flex items-center gap-2 py-1.5">
                <span className="w-24 text-ink-2">{formatDate(c.date, { day: "numeric", month: "short", year: "2-digit" })}</span>
                <span className="min-w-0 flex-1 truncate text-ink-3">{c.note}</span>
                <span className={cn("num font-semibold", c.amount < 0 && "text-out")}>{c.amount < 0 ? "−" : "+"}{inr(Math.abs(c.amount))}</span>
                <button className="rounded-full p-1 text-ink-3 hover:bg-out-soft hover:text-out" aria-label="Delete entry" onClick={() => onDeleteContribution(c.id)}><Trash2 className="size-3.5" /></button>
              </li>
            ))}
          </ul>
        </details>
      )}
    </article>
  );
}

function GoalDialog({ goal, onClose }: { goal: Goal | null; onClose: () => void }) {
  const invalidate = useInvalidatePlanning();
  const [f, setF] = useState({
    name: goal?.name ?? "", kind: goal?.kind ?? ("OTHER" as GoalKind), targetAmount: goal ? String(goal.targetAmount) : "", targetDate: goal?.targetDate ?? "",
    startingAmount: goal ? String(goal.startingAmount) : "", expectedReturnPct: goal ? String(goal.expectedReturnPct) : "0", priority: String(goal?.priority ?? 2),
    status: goal?.status ?? "ACTIVE",
  });
  const [hint, setHint] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  async function suggestEmergency() {
    try {
      const r = await api.get<{ suggestedTarget: number; basis: string }>("/goals/emergency-fund-suggestion");
      setF((x) => ({ ...x, targetAmount: String(r.suggestedTarget), name: x.name || "Emergency fund" }));
      setHint(r.basis);
    } catch {
      setHint("Couldn't work it out. Enter an amount yourself.");
    }
  }
  async function save() {
    setBusy(true);
    try {
      const body = { ...f, targetDate: f.targetDate || null, startingAmount: f.startingAmount || "0", priority: Number(f.priority), version: goal?.version };
      if (goal) await api.put(`/goals/${goal.id}`, body);
      else await api.post("/goals", body);
      invalidate();
      toast.success("Goal saved");
      onClose();
    } catch (e) {
      toast.error(e instanceof ApiError ? e.message : "Couldn't save");
      setBusy(false);
    }
  }
  const money = (k: "targetAmount" | "startingAmount" | "expectedReturnPct") => (e: React.ChangeEvent<HTMLInputElement>) => setF({ ...f, [k]: e.target.value.replace(/[^0-9.]/g, "") });
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent title={goal ? `Edit ${goal.name}` : "New goal"}>
        <div className="grid gap-4 sm:grid-cols-2">
          <Field label="Name" htmlFor="g-name"><Input id="g-name" value={f.name} onChange={(e) => setF({ ...f, name: e.target.value })} placeholder="Goa trip" /></Field>
          <Field label="Type" htmlFor="g-kind">
            <Select id="g-kind" value={f.kind} onChange={(e) => setF({ ...f, kind: e.target.value as GoalKind })}>
              {(Object.keys(KIND) as GoalKind[]).map((k) => <option key={k} value={k}>{KIND[k]}</option>)}
            </Select>
          </Field>
          <Field label="Target (₹)" htmlFor="g-target"><Input id="g-target" inputMode="decimal" value={f.targetAmount} onChange={money("targetAmount")} /></Field>
          <Field label="By when" htmlFor="g-date" hint="Optional"><Input id="g-date" type="date" min={isoDay(new Date())} value={f.targetDate} onChange={(e) => setF({ ...f, targetDate: e.target.value })} /></Field>
          {f.kind === "EMERGENCY_FUND" && (
            <div className="sm:col-span-2">
              <Button type="button" variant="outline" size="sm" onClick={suggestEmergency}>Suggest from my spending</Button>
              {hint && <p className="mt-1 text-xs text-ink-3">{hint}</p>}
            </div>
          )}
          <Field label="Already saved (₹)" htmlFor="g-start" hint="Money set aside before today"><Input id="g-start" inputMode="decimal" value={f.startingAmount} onChange={money("startingAmount")} /></Field>
          <Field label="Expected growth (% a year)" htmlFor="g-ret" hint="0 for a savings account; an estimate, not a promise"><Input id="g-ret" inputMode="decimal" value={f.expectedReturnPct} onChange={money("expectedReturnPct")} /></Field>
          <Field label="Priority" htmlFor="g-pri">
            <Select id="g-pri" value={f.priority} onChange={(e) => setF({ ...f, priority: e.target.value })}>
              <option value="1">High</option><option value="2">Medium</option><option value="3">Low</option>
            </Select>
          </Field>
          {goal && (
            <Field label="Status" htmlFor="g-status">
              <Select id="g-status" value={f.status} onChange={(e) => setF({ ...f, status: e.target.value as Goal["status"] })}>
                <option value="ACTIVE">Active</option><option value="PAUSED">Paused</option><option value="ACHIEVED">Reached</option><option value="ARCHIVED">Archived</option>
              </Select>
            </Field>
          )}
          <Button className="sm:col-span-2" disabled={busy || !f.name || !f.targetAmount} onClick={save}>{goal ? "Save goal" : "Create goal"}</Button>
        </div>
      </DialogContent>
    </Dialog>
  );
}

function ContributionDialog({ goal, onClose }: { goal: Goal; onClose: () => void }) {
  const invalidate = useInvalidatePlanning();
  const [amount, setAmount] = useState("");
  const [withdraw, setWithdraw] = useState(false);
  const [date, setDate] = useState(isoDay(new Date()));
  const [note, setNote] = useState("");
  async function save() {
    try {
      await api.post(`/goals/${goal.id}/contributions`, { amount: (withdraw ? "-" : "") + amount, date, note });
      invalidate();
      toast.success(withdraw ? "Withdrawal recorded" : "Added");
      onClose();
    } catch (e) {
      toast.error(e instanceof ApiError ? e.message : "Couldn't save");
    }
  }
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent title={`Add to ${goal.name}`} description="Record money you've put aside for this goal. This doesn't move money; it's your record.">
        <div className="space-y-4">
          <div className="flex rounded-full border border-line p-0.5" role="radiogroup" aria-label="Entry type">
            {[false, true].map((w) => (
              <button key={String(w)} role="radio" aria-checked={withdraw === w} onClick={() => setWithdraw(w)}
                className={cn("flex-1 rounded-full px-3 py-1.5 text-sm font-semibold", withdraw === w ? "bg-peacock text-white dark:text-[#06201f]" : "text-ink-2")}>
                {w ? "Took money out" : "Put money in"}
              </button>
            ))}
          </div>
          <Field label="Amount (₹)" htmlFor="c-amt"><Input id="c-amt" inputMode="decimal" value={amount} onChange={(e) => setAmount(e.target.value.replace(/[^0-9.]/g, ""))} autoFocus /></Field>
          <Field label="Date" htmlFor="c-date"><Input id="c-date" type="date" value={date} onChange={(e) => setDate(e.target.value)} /></Field>
          <Field label="Note" htmlFor="c-note"><Input id="c-note" value={note} onChange={(e) => setNote(e.target.value)} placeholder="Bonus" /></Field>
          <Button className="w-full" disabled={!amount} onClick={save}>Save</Button>
        </div>
      </DialogContent>
    </Dialog>
  );
}

function LinkDialog({ goal, onClose }: { goal: Goal; onClose: () => void }) {
  const holdings = useHoldings();
  const invalidate = useInvalidatePlanning();
  const [holdingId, setHoldingId] = useState("");
  const [share, setShare] = useState("100");
  const linked = new Set(goal.holdings.map((h) => h.holdingId));
  async function save() {
    try {
      await api.post(`/goals/${goal.id}/holdings`, { holdingId, sharePct: share });
      invalidate();
      toast.success("Investment linked");
      onClose();
    } catch (e) {
      toast.error(e instanceof ApiError ? e.message : "Couldn't link");
    }
  }
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent title={`Fund ${goal.name} with an investment`} description="Its current value (or the share you choose) counts toward the goal and moves with the market.">
        <div className="space-y-4">
          <Field label="Investment" htmlFor="l-h">
            <Select id="l-h" value={holdingId} onChange={(e) => setHoldingId(e.target.value)}>
              <option value="">Choose one</option>
              {holdings.data?.filter((h) => !h.archived && !linked.has(h.id)).map((h) => <option key={h.id} value={h.id}>{h.name} ({inr(h.value)})</option>)}
            </Select>
          </Field>
          <Field label="Share for this goal (%)" htmlFor="l-s"><Input id="l-s" inputMode="decimal" value={share} onChange={(e) => setShare(e.target.value.replace(/[^0-9.]/g, ""))} /></Field>
          <Button className="w-full" disabled={!holdingId} onClick={save}>Link</Button>
        </div>
      </DialogContent>
    </Dialog>
  );
}
