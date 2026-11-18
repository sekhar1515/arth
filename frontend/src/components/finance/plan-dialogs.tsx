"use client";

import { useState } from "react";
import { toast } from "sonner";
import { api, ApiError } from "@/lib/api";
import { formatDate, inr, isoDay } from "@/lib/format";
import type { Holding, InvestmentPlan } from "@/types/api";
import { useAccounts, useInvalidateWealth } from "@/hooks/queries";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent } from "@/components/ui/dialog";
import { Field, Input, Select } from "@/components/ui/input";

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
export function PlanDialog({ holding, plan, open, onOpenChange }: { holding: Holding; plan?: InvestmentPlan; open: boolean; onOpenChange: (o: boolean) => void }) {
  const invalidate = useInvalidateWealth();
  const kind = plan?.kind ?? planKindFor(holding) ?? "CONTRIBUTION";
  const pf = holding.assetClass === "EPF";
  const [f, setF] = useState({
    amount: plan?.amount?.toString() ?? "", employerAmount: plan?.employerAmount?.toString() ?? "",
    dayOfMonth: String(plan?.dayOfMonth ?? 5), accountId: plan?.accountId ?? "", endDate: plan?.endDate ?? "",
  });
  const [busy, setBusy] = useState(false);
  const set = (p: Partial<typeof f>) => setF({ ...f, ...p });
  const total = Number(f.amount || 0) + Number(f.employerAmount || 0);

  async function save() {
    setBusy(true);
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
      <DialogContent title={`${plan ? "Change" : "Add"} ${kind === "SIP" ? "SIP" : "monthly contribution"}: ${holding.name}`}
        description={kind === "SIP"
          ? "On each SIP date Arth adds the units at that day's NAV and takes the amount out of the bank account you pick."
          : pf ? "Your PF grows each month by your contribution plus your employer's. It comes out of your salary, so no bank account is needed."
            : "On each date Arth adds the amount to this investment's value and, if you pick a bank account, takes it out of that account."}>
        <div className="grid gap-4 sm:grid-cols-2">
          <Field label={pf ? "Your contribution" : "Amount"} htmlFor="plan-amount" hint={pf ? "Employee share, from your payslip" : undefined}>
            <Input id="plan-amount" inputMode="decimal" className="num" value={f.amount} onChange={(e) => set({ amount: num(e.target.value) })} autoFocus />
          </Field>
          {pf && (
            <Field label="Employer's contribution" htmlFor="plan-employer" hint="The part your employer adds to EPF">
              <Input id="plan-employer" inputMode="decimal" className="num" value={f.employerAmount} onChange={(e) => set({ employerAmount: num(e.target.value) })} />
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
            ? "The units you entered should already include your past SIPs: Arth adds SIPs from the next date on."
            : "The balance you entered should already include past contributions: Arth adds them from the next date on."}
        </p>
        <Button className="mt-4 w-full" disabled={busy || !f.amount} onClick={save}>{plan ? "Save changes" : kind === "SIP" ? "Add SIP" : "Add monthly contribution"}</Button>
      </DialogContent>
    </Dialog>
  );
}

/** One step for PF: the balance from your passbook plus the monthly contributions. */
export function PfDialog({ open, onOpenChange }: { open: boolean; onOpenChange: (o: boolean) => void }) {
  const invalidate = useInvalidateWealth();
  const [f, setF] = useState({ name: "EPF", balance: "", amount: "", employerAmount: "", dayOfMonth: "1" });
  const [busy, setBusy] = useState(false);
  const set = (p: Partial<typeof f>) => setF({ ...f, ...p });

  async function save() {
    setBusy(true);
    try {
      const h = await api.post<Holding>("/networth/holdings", { assetClass: "EPF", name: f.name || "EPF", manualValue: f.balance, investedAmount: f.balance });
      if (f.amount) {
        await api.post("/networth/plans", { holdingId: h.id, kind: "CONTRIBUTION", amount: f.amount, employerAmount: f.employerAmount || "0", dayOfMonth: Number(f.dayOfMonth) });
      }
      invalidate();
      toast.success("PF added to your investments and net worth");
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
            <Input id="pf-balance" inputMode="decimal" className="num" value={f.balance} onChange={(e) => set({ balance: num(e.target.value) })} autoFocus />
          </Field>
          <Field label="Your monthly contribution (optional)" htmlFor="pf-amount" hint="Usually 12% of basic pay">
            <Input id="pf-amount" inputMode="decimal" className="num" value={f.amount} onChange={(e) => set({ amount: num(e.target.value) })} />
          </Field>
          <Field label="Employer's monthly contribution" htmlFor="pf-employer" hint="The EPF part (excluding pension)">
            <Input id="pf-employer" inputMode="decimal" className="num" value={f.employerAmount} onChange={(e) => set({ employerAmount: num(e.target.value) })} />
          </Field>
          <Field label="Added on" htmlFor="pf-day" hint="Day your salary is credited">
            <Select id="pf-day" value={f.dayOfMonth} onChange={(e) => set({ dayOfMonth: e.target.value })}>
              {Array.from({ length: 28 }, (_, i) => i + 1).map((d) => <option key={d} value={d}>{ordinal(d)} of each month</option>)}
            </Select>
          </Field>
        </div>
        <p className="mt-4 rounded-2xl bg-surface-2 p-3 text-xs text-ink-2">
          Interest is credited once a year; update the balance from your passbook when it is (⋯ → Update value).
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
