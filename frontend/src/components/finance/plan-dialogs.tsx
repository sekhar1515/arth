"use client";

import { useState } from "react";
import { toast } from "sonner";
import { api, ApiError } from "@/lib/api";
import { formatDate, inr, isoDay } from "@/lib/format";
import type { Holding, HoldingOwner, InvestmentPlan } from "@/types/api";
import { useAccounts, useInvalidateWealth } from "@/hooks/queries";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent } from "@/components/ui/dialog";
import { Field, Input, Select } from "@/components/ui/input";
import { AmountInput } from "@/components/ui/amount-input";

const err = (e: unknown) => (e instanceof ApiError ? [e.message, ...e.details].join(". ") : "Something went wrong");
const num = (v: string) => v.replace(/[^0-9.]/g, "");
const ordinal = (n: number) => n + (n % 10 === 1 && n !== 11 ? "st" : n % 10 === 2 && n !== 12 ? "nd" : n % 10 === 3 && n !== 13 ? "rd" : "th");

export const planSummary = (p: InvestmentPlan) =>
  `${inr(p.total)} on the ${ordinal(p.dayOfMonth)} of every month${p.accountName ? ` from ${p.accountName}` : ""}`;

/** Funds can have a SIP; things valued by hand (PF/EPF, PPF, NPS, RD…) can have a monthly contribution. */
export function planKindFor(h: Holding): "SIP" | "CONTRIBUTION" | null {
  if (h.assetClass === "MUTUAL_FUND" && h.valuationMethod === "MARKET_PRICE") return "SIP";
  if (h.valuationMethod === "MANUAL") return "CONTRIBUTION";
  return null;
}

function BankSelect({ value, onChange, hint }: { value: string; onChange: (v: string) => void; hint: string }) {
  const accounts = useAccounts();
  const banks = (accounts.data ?? []).filter((a) => !a.archived && a.type !== "CREDIT_CARD");
  return (
    <Field label="Debited from" htmlFor="plan-bank" hint={hint}>
      <Select id="plan-bank" value={value} onChange={(e) => onChange(e.target.value)}>
        <option value="">Not from a bank account here</option>
        {banks.map((a) => <option key={a.id} value={a.id}>{a.name}{a.last4 ? ` ••${a.last4}` : ""}</option>)}
      </Select>
    </Field>
  );
}

/** Add or change a SIP (fund) or monthly contribution (PF/EPF, PPF, NPS…). */
export function PlanDialog({ holding: given, holdings = [], kind: wanted, plan, open, onOpenChange }: {
  holding?: Holding; holdings?: Holding[]; kind?: "SIP" | "CONTRIBUTION"; plan?: InvestmentPlan; open: boolean; onOpenChange: (o: boolean) => void;
}) {
  const invalidate = useInvalidateWealth();
  const kind = plan?.kind ?? (given ? planKindFor(given) : null) ?? wanted ?? "SIP";
  // which investment this goes into: any of your funds for a SIP, anything valued by hand for a contribution
  const options = holdings.filter((h) => planKindFor(h) === kind);
  const [selectedId, setSelectedId] = useState(given?.id ?? options.find((h) => kind !== "SIP" || h.schemeCode)?.id ?? options[0]?.id ?? "");
  const holding = given?.id === selectedId ? given : options.find((h) => h.id === selectedId) ?? given;
  const pf = holding?.assetClass === "EPF";
  const [f, setF] = useState({
    amount: plan?.amount?.toString() ?? "", employerAmount: plan?.employerAmount?.toString() ?? "",
    dayOfMonth: String(plan?.dayOfMonth ?? 5), accountId: plan?.accountId ?? "", endDate: plan?.endDate ?? "",
  });
  const [busy, setBusy] = useState(false);
  const set = (p: Partial<typeof f>) => setF({ ...f, ...p });
  const total = Number(f.amount || 0) + Number(f.employerAmount || 0);

  async function save() {
    setBusy(true);
    if (!holding) return;
    const body = {
      holdingId: holding.id, kind, amount: f.amount || null, employerAmount: f.employerAmount || "0", dayOfMonth: Number(f.dayOfMonth),
      accountId: f.accountId || null, endDate: f.endDate || null, version: plan?.version,
    };
    try {
      if (plan) await api.put(`/networth/plans/${plan.id}`, body);
      else await api.post("/networth/plans", body);
      invalidate();
      toast.success(plan ? "Saved" : kind === "SIP" ? "SIP added" : "Monthly contribution added");
      onOpenChange(false);
    } catch (e) {
      toast.error(err(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent title={`${plan ? "Change" : "Add"} ${kind === "SIP" ? "SIP" : "monthly contribution"}${plan && holding ? `: ${holding.name}` : ""}`}
        description={kind === "SIP"
          ? (holding?.owner === "PARENTS"
            ? "On each SIP date Arth adds the units at that day's NAV. If it's paid from your parents' bank, leave \"Not from a bank account here\" so your own balance isn't touched."
            : "On each SIP date Arth adds the units at that day's NAV and takes the amount out of the bank account you pick.")
          : pf ? "Your PF grows each month by your contribution plus your employer's. It comes out of your salary, so no bank account is needed."
            : "On each date Arth adds the amount to this investment's value and, if you pick a bank account, takes it out of that account."}>
        {!plan && (options.length > 0 || given) && (
          <div className="mb-4"><Field label={kind === "SIP" ? "Fund" : "Investment"} htmlFor="plan-holding">
            <Select id="plan-holding" value={selectedId} onChange={(e) => setSelectedId(e.target.value)}>
              {(options.length > 0 ? options : given ? [given] : []).map((h) => (
                <option key={h.id} value={h.id} disabled={kind === "SIP" && !h.schemeCode}>
                  {h.name}{kind === "SIP" && !h.schemeCode ? " (link to AMFI first: ⋯ → Get latest price)" : ""}
                </option>
              ))}
            </Select>
          </Field></div>
        )}
        {!holding && (
          <p className="mb-4 rounded-2xl bg-surface-2 p-3 text-sm text-ink-2">
            {kind === "SIP" ? "Add the fund first (Investments → Fund or stock), then set up its SIP." : "Add the investment first (e.g. PF / EPF), then its monthly contribution."}
          </p>
        )}
        <div className="grid gap-4 sm:grid-cols-2">
          <Field label={pf ? "Your contribution" : "Amount"} htmlFor="plan-amount" hint={pf ? "Employee share, from your payslip" : undefined}>
            <AmountInput id="plan-amount" className="num" value={f.amount} onValueChange={(v) => set({ amount: v })} autoFocus />
          </Field>
          {pf && (
            <Field label="Employer's contribution" htmlFor="plan-employer" hint="The part your employer adds to EPF">
              <AmountInput id="plan-employer" className="num" value={f.employerAmount} onValueChange={(v) => set({ employerAmount: v })} />
            </Field>
          )}
          <Field label={kind === "SIP" ? "SIP date each month" : "Date each month"} htmlFor="plan-day" hint="Day 1 to 28">
            <Select id="plan-day" value={f.dayOfMonth} onChange={(e) => set({ dayOfMonth: e.target.value })}>
              {Array.from({ length: 28 }, (_, i) => i + 1).map((d) => <option key={d} value={d}>{ordinal(d)}</option>)}
            </Select>
          </Field>
          {!pf && (
            <BankSelect value={f.accountId} onChange={(v) => set({ accountId: v })}
              hint="The debit is recorded there on each date; it merges with the line when you import that bank's statement" />
          )}
          <Field label="Stops on (optional)" htmlFor="plan-end">
            <Input id="plan-end" type="date" value={f.endDate} min={isoDay(new Date())} onChange={(e) => set({ endDate: e.target.value })} />
          </Field>
        </div>
        <p className="mt-4 rounded-2xl bg-surface-2 p-3 text-xs text-ink-2">
          {total > 0 ? <><strong className="num text-ink">{inr(total)}</strong> goes in on the {ordinal(Number(f.dayOfMonth))} of every month. </> : null}
          {kind === "SIP"
            ? "The units you entered should already include your past SIPs: Arth adds SIPs from the next date on. If the date falls on a weekend or market holiday, units come at the next working day's NAV (as your fund house does)."
            : "The balance you entered should already include past contributions: Arth adds them from the next date on."}
        </p>
        <Button className="mt-4 w-full" disabled={busy || !f.amount || !holding} onClick={save}>{plan ? "Save changes" : kind === "SIP" ? "Add SIP" : "Add monthly contribution"}</Button>
      </DialogContent>
    </Dialog>
  );
}

/** One step for PF: the balance from your passbook plus the monthly contributions. */
export function PfDialog({ owner = "SELF", open, onOpenChange }: { owner?: HoldingOwner; open: boolean; onOpenChange: (o: boolean) => void }) {
  const invalidate = useInvalidateWealth();
  const [f, setF] = useState({ name: "EPF", balance: "", amount: "", employerAmount: "", dayOfMonth: "1" });
  const [busy, setBusy] = useState(false);
  const set = (p: Partial<typeof f>) => setF({ ...f, ...p });

  async function save() {
    setBusy(true);
    try {
      const h = await api.post<Holding>("/networth/holdings", { assetClass: "EPF", name: f.name || "EPF", manualValue: f.balance, investedAmount: f.balance, owner });
      if (f.amount) {
        await api.post("/networth/plans", { holdingId: h.id, kind: "CONTRIBUTION", amount: f.amount, employerAmount: f.employerAmount || "0", dayOfMonth: Number(f.dayOfMonth) });
      }
      invalidate();
      toast.success(owner === "PARENTS" ? "PF added to parents' investments" : "PF added to your investments and net worth");
      onOpenChange(false);
    } catch (e) {
      toast.error(err(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent title="Add your PF / EPF" description="Enter today's balance from the EPFO passbook (or UMANG app). Add the monthly contributions and Arth grows it every month.">
        <div className="grid gap-4 sm:grid-cols-2">
          <Field label="Name" htmlFor="pf-name"><Input id="pf-name" value={f.name} onChange={(e) => set({ name: e.target.value })} /></Field>
          <Field label="Balance today" htmlFor="pf-balance" hint="Employee + employer share, as on the passbook">
            <AmountInput id="pf-balance" className="num" value={f.balance} onValueChange={(v) => set({ balance: v })} autoFocus />
          </Field>
          <Field label="Your monthly contribution (optional)" htmlFor="pf-amount" hint="Usually 12% of basic pay">
            <AmountInput id="pf-amount" className="num" value={f.amount} onValueChange={(v) => set({ amount: v })} />
          </Field>
          <Field label="Employer's monthly contribution" htmlFor="pf-employer" hint="The EPF part (excluding pension)">
            <AmountInput id="pf-employer" className="num" value={f.employerAmount} onValueChange={(v) => set({ employerAmount: v })} />
          </Field>
          <Field label="Added on" htmlFor="pf-day" hint="Day your salary is credited">
            <Select id="pf-day" value={f.dayOfMonth} onChange={(e) => set({ dayOfMonth: e.target.value })}>
              {Array.from({ length: 28 }, (_, i) => i + 1).map((d) => <option key={d} value={d}>{ordinal(d)} of each month</option>)}
            </Select>
          </Field>
        </div>
        <p className="mt-4 rounded-2xl bg-surface-2 p-3 text-xs text-ink-2">
          Interest at 8.25% a year (EPFO rate) is added automatically: worked out monthly and credited into the balance each March. Change the rate in Edit if yours differs.
        </p>
        <Button className="mt-4 w-full" disabled={busy || !f.balance} onClick={save}>Add PF</Button>
      </DialogContent>
    </Dialog>
  );
}

export function InstallmentLine({ i, sip }: { i: InvestmentPlan["installments"][number]; sip: boolean }) {
  return (
    <li className="flex justify-between gap-3 text-xs text-ink-2">
      <span>{formatDate(i.dueDate, { day: "numeric", month: "short", year: "numeric" })}</span>
      <span className="num">
        {inr(i.amount)}
        {sip && i.units != null && i.nav != null && <> → {i.units} units at ₹{i.nav}{i.navDate && i.navDate !== i.dueDate ? ` (NAV ${formatDate(i.navDate, { day: "numeric", month: "short" })})` : ""}</>}
      </span>
    </li>
  );
}
