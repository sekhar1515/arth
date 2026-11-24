"use client";

import { Ban, Check, CheckCircle2, Eye, Pencil, Plus, Radar, Repeat, Trash2, TrendingUp, X } from "lucide-react";
import { useState } from "react";
import { toast } from "sonner";
import { PageHeader } from "@/components/layout/app-shell";
import { Stat } from "@/components/finance/meter";
import { Button } from "@/components/ui/button";
import { useConfirm } from "@/components/ui/confirm";
import { Dialog, DialogContent } from "@/components/ui/dialog";
import { Field, Input, Select } from "@/components/ui/input";
import { EmptyState, ErrorState, Panel, Pill, Skeleton } from "@/components/ui/states";
import { useInvalidatePlanning, useRecurring } from "@/hooks/planning";
import { api, ApiError } from "@/lib/api";
import { formatDate, inr, isoDay } from "@/lib/format";
import { cn } from "@/lib/utils";
import type { Frequency, Recurring, RecurringKind } from "@/types/api";
import { AmountInput } from "@/components/ui/amount-input";

const KIND_LABEL: Record<RecurringKind, string> = {
  SUBSCRIPTION: "Subscription", BILL: "Bill", EMI: "EMI", RENT: "Rent", INSURANCE: "Insurance", SIP: "SIP / investment", INCOME: "Income", OTHER: "Other",
};
const FREQ_LABEL: Record<Frequency, string> = { WEEKLY: "every week", MONTHLY: "every month", QUARTERLY: "every 3 months", YEARLY: "every year" };

function due(r: Recurring) {
  if (r.nextDue === null || r.daysUntilDue === null) return "No date";
  if (r.overdue) return `Expected ${formatDate(r.nextDue, { day: "numeric", month: "short" })}`;
  if (r.daysUntilDue === 0) return "Due today";
  if (r.daysUntilDue === 1) return "Due tomorrow";
  if (r.daysUntilDue > 0 && r.daysUntilDue <= 14) return `Due in ${r.daysUntilDue} days`;
  return `Next ${formatDate(r.nextDue, { day: "numeric", month: "short" })}`;
}

export default function SubscriptionsPage() {
  const rec = useRecurring();
  const invalidate = useInvalidatePlanning();
  const { confirm, dialog } = useConfirm();
  const [editing, setEditing] = useState<Recurring | "new" | null>(null);
  const [detecting, setDetecting] = useState(false);
  const d = rec.data;
  const suggestions = d?.items.filter((i) => i.status === "SUGGESTED") ?? [];
  const active = d?.items.filter((i) => i.status === "ACTIVE") ?? [];
  const upcoming = active.filter((i) => !i.income && i.daysUntilDue !== null && (i.overdue || i.daysUntilDue <= 30)).slice(0, 8);
  const inactive = d?.items.filter((i) => i.status === "CANCELLED" || i.status === "DISMISSED") ?? [];

  async function act(fn: () => Promise<unknown>, ok: string) {
    try {
      await fn();
      invalidate();
      toast.success(ok);
    } catch (e) {
      toast.error(e instanceof ApiError ? e.message : "Something went wrong");
    }
  }

  async function detect() {
    setDetecting(true);
    try {
      const r = await api.post<{ newSuggestions: number }>("/recurring/detect");
      toast.success(r.newSuggestions ? `Found ${r.newSuggestions} new repeating payment${r.newSuggestions === 1 ? "" : "s"} to review` : "Nothing new found");
      invalidate();
    } catch (e) {
      toast.error(e instanceof ApiError ? e.message : "Couldn't scan");
    } finally {
      setDetecting(false);
    }
  }

  async function remove(r: Recurring) {
    if (await confirm({ title: `Delete ${r.name}?`, body: r.detected ? "It's removed from this list. If the payments keep happening, the nightly scan may suggest it again." : "It's removed from this list and its reminders stop.", confirmLabel: "Delete", danger: true })) {
      act(() => api.del(`/recurring/${r.id}`), "Deleted");
    }
  }

  return (
    <>
      <PageHeader title="Subscriptions & bills"
        subtitle="Repeating payments found in your history, plus any you add yourself. Found ones wait for you to confirm, because not every repeat is a subscription."
        actions={<>
          <Button variant="outline" size="sm" onClick={detect} disabled={detecting}><Radar className={detecting ? "animate-pulse" : undefined} /> Scan history</Button>
          <Button size="sm" onClick={() => setEditing("new")}><Plus /> Add manually</Button>
        </>} />
      {rec.isError ? <ErrorState error={rec.error} retry={() => rec.refetch()} /> : !d ? <Skeleton className="h-96" /> : d.items.length === 0 ? (
        <div className="rounded-3xl border border-line bg-surface">
          <EmptyState icon={<Repeat />} title="No repeating payments yet" body="Arth needs about three months of transactions to spot monthly payments. You can also add rent, EMIs or subscriptions yourself."
            action={<div className="flex gap-2"><Button variant="outline" onClick={detect}><Radar /> Scan history</Button><Button onClick={() => setEditing("new")}><Plus /> Add one</Button></div>} />
        </div>
      ) : (
        <div className="space-y-6">
          <div className="flex flex-wrap gap-x-10 gap-y-3">
            <Stat label="Committed each month" value={inr(d.monthlyCommitted)} hint="Confirmed bills, EMIs, rent, SIPs and subscriptions" />
            <Stat label="Subscriptions a year" value={inr(d.annualSubscriptions)} hint={`${d.activeSubscriptions} active`} />
            <Stat label="Due in the next 7 days" value={String(d.dueIn7Days)} tone={d.dueIn7Days > 0 ? "attention" : undefined} />
            {d.forgotten > 0 && <Stat label="Not reviewed in 6 months" value={String(d.forgotten)} tone="attention" />}
          </div>

          {suggestions.length > 0 && (
            <section className="rounded-3xl border border-marigold/50 bg-marigold-soft/50 p-5">
              <h2 className="font-display text-base font-semibold">Is this a repeating payment?</h2>
              <p className="text-[13px] text-ink-2">Found by looking for steady gaps between payments to the same payee. Confirm the ones that are real, and fix the type if it&apos;s wrong.</p>
              <ul className="mt-4 space-y-2">
                {suggestions.map((r) => <SuggestionRow key={r.id} r={r} act={act} />)}
              </ul>
            </section>
          )}

          {upcoming.length > 0 && (
            <Panel title="Coming up">
              <ul className="divide-y divide-line">
                {upcoming.map((r) => (
                  <li key={r.id} className="flex items-center gap-3 py-2.5 text-sm">
                    <span className={cn("w-32 shrink-0 font-semibold", r.overdue ? "text-out" : r.daysUntilDue !== null && r.daysUntilDue <= 3 ? "text-marigold-ink" : "text-ink-2")}>{due(r)}</span>
                    <span className="min-w-0 flex-1 truncate">{r.name}</span>
                    <span className="num font-semibold">{r.amountVaries ? "≈ " : ""}{inr(r.amount)}</span>
                    {!r.detected && <Button variant="ghost" size="sm" onClick={() => act(() => api.post(`/recurring/${r.id}/paid`), `${r.name} marked paid`)}><Check /> Paid</Button>}
                  </li>
                ))}
              </ul>
            </Panel>
          )}

          <Panel title="Active">
            {active.length === 0 ? <p className="text-sm text-ink-3">Nothing confirmed yet.</p> : (
              <ul className="divide-y divide-line">
                {active.map((r) => (
                  <li key={r.id} className="flex flex-wrap items-center gap-x-4 gap-y-2 py-3">
                    <div className="min-w-0 flex-1 basis-56">
                      <p className="flex flex-wrap items-center gap-2 font-semibold">
                        {r.name}
                        <Pill tone={r.income ? "in" : r.kind === "SUBSCRIPTION" ? "brand" : "neutral"}>{KIND_LABEL[r.kind]}</Pill>
                        {r.priceChanged && r.previousAmount !== null && (
                          <Pill tone={r.amount > r.previousAmount ? "out" : "in"}><TrendingUp className="size-3" /> was {inr(r.previousAmount)}</Pill>
                        )}
                        {r.forgotten && <Pill tone="attention">Still using it?</Pill>}
                        {r.overdue && <Pill tone="out">Overdue</Pill>}
                      </p>
                      <p className="text-[13px] text-ink-3">
                        {r.amountVaries ? "About " : ""}{inr(r.amount)} {FREQ_LABEL[r.frequency]}{r.categoryName ? `, ${r.categoryName}` : ""}{r.lastPaidOn ? `. Last paid ${formatDate(r.lastPaidOn, { day: "numeric", month: "short" })}` : ""}
                      </p>
                    </div>
                    <div className="text-right">
                      <p className="num text-sm font-semibold">{inr(r.annualCost)}<span className="font-normal text-ink-3"> a year</span></p>
                      <p className="text-xs text-ink-3">{due(r)}</p>
                    </div>
                    <div className="flex">
                      {r.forgotten && <Button variant="ghost" size="icon" title="Yes, still using it" aria-label={`Mark ${r.name} reviewed`} onClick={() => act(() => api.post(`/recurring/${r.id}/reviewed`), "Thanks, noted")}><Eye /></Button>}
                      <Button variant="ghost" size="icon" title="Edit" aria-label={`Edit ${r.name}`} onClick={() => setEditing(r)}><Pencil /></Button>
                      <Button variant="ghost" size="icon" title="I cancelled this" aria-label={`Mark ${r.name} cancelled`} onClick={() => act(() => api.post(`/recurring/${r.id}/cancel`), `${r.name} marked cancelled. Arth will warn you if it's charged again.`)}><Ban /></Button>
                      <Button variant="ghost" size="icon" className="text-out hover:bg-out-soft hover:text-out" title="Delete" aria-label={`Delete ${r.name}`} onClick={() => remove(r)}><Trash2 /></Button>
                    </div>
                  </li>
                ))}
              </ul>
            )}
          </Panel>

          {inactive.length > 0 && (
            <details>
              <summary className="cursor-pointer text-sm font-semibold text-ink-2">Cancelled and dismissed ({inactive.length})</summary>
              <ul className="mt-3 space-y-2">
                {inactive.map((r) => (
                  <li key={r.id} className="flex items-center justify-between gap-3 rounded-2xl border border-line bg-surface px-4 py-2 text-sm">
                    <span>{r.name} <span className="text-ink-3">{r.status === "CANCELLED" ? "cancelled" : "not a repeating payment"}</span></span>
                    <span className="flex gap-1">
                      <Button variant="ghost" size="sm" onClick={() => act(() => api.post(`/recurring/${r.id}/confirm`, {}), "Marked active")}>Make active</Button>
                      <Button variant="ghost" size="sm" className="text-out hover:bg-out-soft hover:text-out" onClick={() => remove(r)}>Delete</Button>
                    </span>
                  </li>
                ))}
              </ul>
            </details>
          )}
          <p className="text-xs text-ink-3">{d.note}</p>
        </div>
      )}
      {editing && <RecurringDialog item={editing === "new" ? null : editing} onClose={() => setEditing(null)} />}
      {dialog}
    </>
  );
}

function SuggestionRow({ r, act }: { r: Recurring; act: (fn: () => Promise<unknown>, ok: string) => void }) {
  const [kind, setKind] = useState<RecurringKind>(r.kind);
  return (
    <li className="flex flex-wrap items-center gap-3 rounded-2xl bg-surface p-3">
      <div className="min-w-0 flex-1 basis-52">
        <p className="font-semibold">{r.name}</p>
        <p className="text-[13px] text-ink-3">{r.amountVaries ? "About " : ""}{inr(r.amount)} {FREQ_LABEL[r.frequency]}, seen {r.occurrences} times. {due(r)}.</p>
      </div>
      <Select aria-label={`Type for ${r.name}`} className="w-40" value={kind} onChange={(e) => setKind(e.target.value as RecurringKind)}>
        {(Object.keys(KIND_LABEL) as RecurringKind[]).map((k) => <option key={k} value={k}>{KIND_LABEL[k]}</option>)}
      </Select>
      <Button size="sm" onClick={() => act(() => api.post(`/recurring/${r.id}/confirm`, { kind }), `${r.name} confirmed`)}><CheckCircle2 /> Yes</Button>
      <Button size="sm" variant="ghost" onClick={() => act(() => api.post(`/recurring/${r.id}/dismiss`), "Dismissed")}><X /> No</Button>
    </li>
  );
}

function RecurringDialog({ item, onClose }: { item: Recurring | null; onClose: () => void }) {
  const invalidate = useInvalidatePlanning();
  const [f, setF] = useState({
    name: item?.name ?? "", kind: item?.kind ?? ("SUBSCRIPTION" as RecurringKind), frequency: item?.frequency ?? ("MONTHLY" as Frequency),
    amount: item ? String(item.amount) : "", nextDue: item?.nextDue ?? isoDay(new Date()), reminderDays: String(item?.reminderDays ?? 3), notes: item?.notes ?? "",
  });
  const [busy, setBusy] = useState(false);
  async function save() {
    setBusy(true);
    try {
      const body = { ...f, reminderDays: Number(f.reminderDays), income: f.kind === "INCOME", version: item?.version };
      if (item) await api.put(`/recurring/${item.id}`, body);
      else await api.post("/recurring", body);
      invalidate();
      toast.success("Saved");
      onClose();
    } catch (e) {
      toast.error(e instanceof ApiError ? e.message : "Couldn't save");
      setBusy(false);
    }
  }
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent title={item ? `Edit ${item.name}` : "Add a repeating payment"}>
        <div className="grid gap-4 sm:grid-cols-2">
          <div className="sm:col-span-2"><Field label="Name" htmlFor="r-name"><Input id="r-name" value={f.name} onChange={(e) => setF({ ...f, name: e.target.value })} placeholder="House rent" /></Field></div>
          <Field label="Type" htmlFor="r-kind">
            <Select id="r-kind" value={f.kind} onChange={(e) => setF({ ...f, kind: e.target.value as RecurringKind })}>
              {(Object.keys(KIND_LABEL) as RecurringKind[]).map((k) => <option key={k} value={k}>{KIND_LABEL[k]}</option>)}
            </Select>
          </Field>
          <Field label="Repeats" htmlFor="r-freq">
            <Select id="r-freq" value={f.frequency} onChange={(e) => setF({ ...f, frequency: e.target.value as Frequency })}>
              {(Object.keys(FREQ_LABEL) as Frequency[]).map((k) => <option key={k} value={k}>{FREQ_LABEL[k].replace("every ", "Every ")}</option>)}
            </Select>
          </Field>
          <Field label="Amount (₹)" htmlFor="r-amt"><AmountInput id="r-amt" value={f.amount} onValueChange={(v) => setF({ ...f, amount: v })} /></Field>
          <Field label="Next due" htmlFor="r-due"><Input id="r-due" type="date" value={f.nextDue} onChange={(e) => setF({ ...f, nextDue: e.target.value })} /></Field>
          <Field label="Remind me (days before)" htmlFor="r-rem"><Input id="r-rem" inputMode="numeric" value={f.reminderDays} onChange={(e) => setF({ ...f, reminderDays: e.target.value.replace(/\D/g, "") })} /></Field>
          <Field label="Note" htmlFor="r-note"><Input id="r-note" value={f.notes} onChange={(e) => setF({ ...f, notes: e.target.value })} /></Field>
          <Button className="sm:col-span-2" disabled={busy || !f.name || !f.amount} onClick={save}>{item ? "Save" : "Add"}</Button>
        </div>
      </DialogContent>
    </Dialog>
  );
}
