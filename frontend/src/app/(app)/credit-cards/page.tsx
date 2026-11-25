"use client";

import { CreditCard as CardIcon, Info, Pencil, Plus } from "lucide-react";
import Link from "next/link";
import { useState } from "react";
import { toast } from "sonner";
import { PageHeader } from "@/components/layout/app-shell";
import { Stat } from "@/components/finance/meter";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent } from "@/components/ui/dialog";
import { Field, Input } from "@/components/ui/input";
import { AmountInput } from "@/components/ui/amount-input";
import { EmptyState, ErrorState, Pill, Skeleton } from "@/components/ui/states";
import { useCards, useInvalidatePlanning } from "@/hooks/planning";
import { api, ApiError } from "@/lib/api";
import { formatDate, inr } from "@/lib/format";
import { cn } from "@/lib/utils";
import type { CreditCard, DueState } from "@/types/api";
import { AccountMonthDialog } from "@/components/finance/account-month-dialog";

const DUE: Record<DueState, { label: string; tone: "in" | "attention" | "out" | "neutral" | "brand" }> = {
  NO_DUES: { label: "Nothing due", tone: "neutral" }, PAID: { label: "Paid in full", tone: "in" }, PARTIALLY_PAID: { label: "Part paid", tone: "attention" },
  DUE: { label: "Due", tone: "brand" }, OVERDUE: { label: "Overdue", tone: "out" },
};
const short = (iso: string) => formatDate(iso, { day: "numeric", month: "short" });

export default function CreditCardsPage() {
  const cards = useCards();
  const [editing, setEditing] = useState<CreditCard | null>(null);
  const d = cards.data;
  return (
    <>
      <PageHeader title="Credit cards"
        subtitle="Statement, due date, minimum due and how much of your limit you're using. Card purchases count as spending when you make them; paying the bill is a transfer, so it's never counted twice." />
      {cards.isError ? <ErrorState error={cards.error} retry={() => cards.refetch()} /> : !d ? <Skeleton className="h-96" /> : d.cards.length === 0 ? (
        <div className="rounded-3xl border border-line bg-surface">
          <EmptyState icon={<CardIcon />} title="No credit cards" body="Add your card as an account first, then import its statement."
            action={<Button asChild><Link href="/accounts"><Plus /> Add a card account</Link></Button>} />
        </div>
      ) : (
        <div className="space-y-6">
          <div className="flex flex-wrap gap-x-10 gap-y-3">
            <Stat label="You owe across cards" value={inr(d.totalOutstanding)} tone={d.totalOutstanding > 0 ? "out" : undefined} />
            <Stat label="Still to pay on statements" value={inr(d.totalDue)} />
            {d.overallUtilisationPct !== null && <Stat label="Limit used" value={`${d.overallUtilisationPct}%`} tone={d.overallUtilisationPct >= 30 ? "attention" : undefined}
              hint="Keeping this under 30% helps your credit score" />}
          </div>
          <div className="grid gap-4 lg:grid-cols-2">
            {d.cards.map((c) => <Card key={c.accountId} c={c} onEdit={() => setEditing(c)} />)}
          </div>
        </div>
      )}
      {editing && <CardDialog card={editing} onClose={() => setEditing(null)} />}
    </>
  );
}

function Card({ c, onEdit }: { c: CreditCard; onEdit: () => void }) {
  const [why, setWhy] = useState(false);
  const [viewing, setViewing] = useState(false);
  return (
    <article className="overflow-hidden rounded-3xl border border-line bg-surface">
      {viewing && <AccountMonthDialog accountId={c.accountId} name={c.name} isCard onClose={() => setViewing(false)} />}
      <button type="button" onClick={() => setViewing(true)} aria-label={`${c.name}: this month's transactions`}
        className="relative block w-full bg-gradient-to-br from-peacock to-[#0a4246] p-5 text-left text-white hover:brightness-110">
        <div className="flex items-start justify-between">
          <div>
            <p className="font-display text-lg font-semibold">{c.name}</p>
            <p className="text-[13px] text-white/70">{c.institution ?? "Credit card"}</p>
          </div>
          {c.last4 && <p className="num text-sm text-white/80">•••• {c.last4}</p>}
        </div>
        <p className="mt-6 text-[13px] text-white/70">Outstanding now</p>
        <p className="num text-3xl font-semibold">{inr(Math.max(0, c.outstanding))}</p>
        {c.configured && c.creditLimit !== null && c.utilisationPct !== null && (
          <div className="mt-3">
            <div className="h-1.5 rounded-full bg-white/20"><div className={cn("h-1.5 rounded-full", c.utilisationPct >= 30 ? "bg-marigold" : "bg-white")} style={{ width: `${Math.min(100, c.utilisationPct)}%` }} /></div>
            <p className="mt-1 text-xs text-white/70">{c.utilisationPct}% of {inr(c.creditLimit)} used, {inr(c.available)} available</p>
          </div>
        )}
        <p className="mt-3 text-xs font-semibold text-white/90">This month&apos;s transactions →</p>
      </button>
      <div className="p-5">
        {!c.configured ? (
          <div className="text-sm">
            <p className="text-ink-2">{c.explanation}</p>
            <Button className="mt-3" size="sm" onClick={onEdit}><Plus /> Add card details</Button>
          </div>
        ) : (
          <>
            {c.due && c.lastStatement && (
              <div className="flex items-start justify-between gap-3">
                <div>
                  <p className="text-[13px] text-ink-2">Statement of {short(c.lastStatement.statementDate)}</p>
                  <p className="num text-xl font-semibold">{inr(c.due.statementAmount)}</p>
                  {c.due.paidSinceStatement > 0 && <p className="text-xs text-ink-3">{inr(c.due.paidSinceStatement)} paid since</p>}
                </div>
                <div className="text-right">
                  <Pill tone={DUE[c.due.state].tone}>{DUE[c.due.state].label}</Pill>
                  {c.due.remaining > 0 && (
                    <>
                      <p className="num mt-1 text-sm font-semibold">{inr(c.due.remaining)} by {short(c.lastStatement.dueDate)}</p>
                      <p className="text-xs text-ink-3">Minimum {inr(c.due.minimumRemaining)}{c.due.state !== "OVERDUE" ? `, ${c.due.daysToDue} days left` : ""}</p>
                    </>
                  )}
                </div>
              </div>
            )}
            {c.currentCycle && (
              <div className="mt-4 rounded-2xl bg-surface-2 p-3 text-sm">
                <p className="text-ink-2">This cycle, {short(c.currentCycle.start)} to {short(c.currentCycle.statementDate)}</p>
                <p><span className="num font-semibold">{inr(c.currentCycleSpend)}</span> spent so far
                  {c.estimatedPointsThisCycle !== null && <span className="text-ink-3">, about {Math.round(c.estimatedPointsThisCycle)} reward points</span>}</p>
              </div>
            )}
            {(c.rewardPoints !== null || c.annualFee) && (
              <p className="mt-3 text-[13px] text-ink-2">
                {c.rewardPoints !== null && <>Reward points: <span className="num font-semibold text-ink">{c.rewardPoints}</span>{c.estimatedRewardValue !== null && <> (about {inr(c.estimatedRewardValue)})</>}. </>}
                {c.annualFee ? <>Annual fee {inr(c.annualFee)}.</> : null}
              </p>
            )}
            {c.payments.length > 0 && (
              <details className="mt-4">
                <summary className="cursor-pointer text-sm font-semibold text-ink-2">Payments ({c.payments.length})</summary>
                <ul className="mt-2 divide-y divide-line text-sm">
                  {c.payments.map((p) => (
                    <li key={p.transactionId} className="flex justify-between py-1.5">
                      <span>{formatDate(p.date)}{p.linkedTransfer ? "" : <span className="ml-2 text-xs text-marigold-ink">not linked to a bank debit</span>}</span>
                      <span className="num font-semibold text-in">{inr(p.amount)}</span>
                    </li>
                  ))}
                </ul>
              </details>
            )}
            <div className="mt-4 flex items-center gap-1">
              <Button variant="ghost" size="sm" onClick={onEdit}><Pencil /> Edit details</Button>
              <Button variant="ghost" size="sm" onClick={() => setWhy(!why)} aria-expanded={why}><Info /> How this is worked out</Button>
            </div>
            {why && <p className="mt-2 rounded-2xl bg-surface-2 p-3 text-[13px] text-ink-2">{c.explanation}</p>}
          </>
        )}
      </div>
    </article>
  );
}

function CardDialog({ card, onClose }: { card: CreditCard; onClose: () => void }) {
  const invalidate = useInvalidatePlanning();
  const s = (v: number | null | undefined) => (v === null || v === undefined ? "" : String(v));
  const [f, setF] = useState({
    creditLimit: s(card.creditLimit), statementDay: s(card.statementDay), dueDaysAfter: s(card.dueDaysAfter ?? 20), minDuePct: s(card.minDuePct ?? 5),
    minDueFloor: s(card.minDueFloor ?? 200), rewardPoints: s(card.rewardPoints), rewardRate: s(card.rewardRate), rewardPointValue: s(card.rewardPointValue), annualFee: s(card.annualFee),
  });
  const [busy, setBusy] = useState(false);
  const set = (k: keyof typeof f) => (e: React.ChangeEvent<HTMLInputElement>) => setF({ ...f, [k]: e.target.value.replace(/[^0-9.]/g, "") });
  async function save() {
    setBusy(true);
    const body: Record<string, number | null> = {};
    for (const [k, v] of Object.entries(f)) body[k] = v === "" ? null : Number(v);
    try {
      await api.put(`/credit-cards/${card.accountId}`, body);
      invalidate();
      toast.success("Card details saved");
      onClose();
    } catch (e) {
      toast.error(e instanceof ApiError ? e.message : "Couldn't save");
      setBusy(false);
    }
  }
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent title={`${card.name} details`} description="From your card statement or the bank's app. Arth never asks for card numbers, CVV or passwords.">
        <div className="grid gap-4 sm:grid-cols-2">
          <Field label="Credit limit (₹)" htmlFor="c-limit"><AmountInput id="c-limit" value={f.creditLimit} onValueChange={(v) => setF({ ...f, creditLimit: v })} /></Field>
          <Field label="Statement day" htmlFor="c-sday" hint="Day of the month, 1 to 28"><Input id="c-sday" inputMode="numeric" value={f.statementDay} onChange={set("statementDay")} /></Field>
          <Field label="Due days after statement" htmlFor="c-due" hint="Usually 15 to 20"><Input id="c-due" inputMode="numeric" value={f.dueDaysAfter} onChange={set("dueDaysAfter")} /></Field>
          <Field label="Minimum due (%)" htmlFor="c-min" hint={`At least ₹${f.minDueFloor || 0}`}><Input id="c-min" inputMode="decimal" value={f.minDuePct} onChange={set("minDuePct")} /></Field>
          <Field label="Reward points balance" htmlFor="c-pts"><Input id="c-pts" inputMode="decimal" value={f.rewardPoints} onChange={set("rewardPoints")} /></Field>
          <Field label="Points per ₹100 spent" htmlFor="c-rate"><Input id="c-rate" inputMode="decimal" value={f.rewardRate} onChange={set("rewardRate")} /></Field>
          <Field label="Value of one point (₹)" htmlFor="c-val"><Input id="c-val" inputMode="decimal" value={f.rewardPointValue} onChange={set("rewardPointValue")} /></Field>
          <Field label="Annual fee (₹)" htmlFor="c-fee"><AmountInput id="c-fee" value={f.annualFee} onValueChange={(v) => setF({ ...f, annualFee: v })} /></Field>
          <Button className="sm:col-span-2" disabled={busy || !f.creditLimit || !f.statementDay} onClick={save}>Save details</Button>
        </div>
      </DialogContent>
    </Dialog>
  );
}
