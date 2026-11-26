"use client";

import { Plus, Trash2 } from "lucide-react";
import { useState } from "react";
import { toast } from "sonner";
import { api, ApiError } from "@/lib/api";
import { inr, isoDay, UPI_APPS } from "@/lib/format";
import type { Account, Category, CategoryKind, Transaction } from "@/types/api";
import { useCreateTransaction, useInvalidateMoney, useMarkTransfer, useSplit } from "@/hooks/queries";
import { useQueryClient } from "@tanstack/react-query";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent } from "@/components/ui/dialog";
import { Field, Input, Select } from "@/components/ui/input";
import { CategoryPicker } from "./category-picker";
import { CATEGORY_ICON_NAMES, CategoryIcon } from "./category-icon";
import { AmountInput } from "@/components/ui/amount-input";

function errorText(e: unknown) {
  return e instanceof ApiError ? [e.message, ...e.details].join(". ") : "Something went wrong.";
}

export function SplitDialog({ txn, categories, open, onOpenChange, onDone }: {
  txn: Transaction; categories: Category[]; open: boolean; onOpenChange: (o: boolean) => void; onDone?: () => void;
}) {
  const kind: CategoryKind = txn.direction === "DEBIT" ? "EXPENSE" : "INCOME";
  const half = (Math.floor((txn.amount / 2) * 100) / 100).toFixed(2);
  const [lines, setLines] = useState([
    { categoryId: "", amount: half },
    { categoryId: "", amount: (txn.amount - Number(half)).toFixed(2) },
  ]);
  const [active, setActive] = useState(0);
  const split = useSplit();
  const total = lines.reduce((s, l) => s + Math.round(Number(l.amount || 0) * 100), 0) / 100;
  const remaining = Math.round((txn.amount - total) * 100) / 100;
  const valid = remaining === 0 && lines.every((l) => l.categoryId && Number(l.amount) > 0);
  const flat = categories.flatMap((c) => [c, ...c.children]);

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent title="Split transaction" description={`Divide ${inr(txn.amount, { paise: true })} across categories. Parts must add up exactly.`} wide>
        <div className="space-y-3">
          {lines.map((l, i) => (
            <div key={i} className={`rounded-2xl border p-3 ${active === i ? "border-peacock-2" : "border-line"}`} onClick={() => setActive(i)}>
              <div className="flex items-center gap-2">
                <span className="flex-1 text-sm font-semibold">{flat.find((c) => c.id === l.categoryId)?.path ?? "Choose a category"}</span>
                <div className="w-36">
                  <AmountInput
                    aria-label={`Amount for part ${i + 1}`}
                    className="num text-right"
                    value={l.amount}
                    onValueChange={(v) => setLines((prev) => prev.map((x, j) => (j === i ? { ...x, amount: v } : x)))}
                  />
                </div>
                {lines.length > 2 && (
                  <Button variant="ghost" size="icon" aria-label="Remove part" onClick={() => setLines(lines.filter((_, j) => j !== i))}>
                    <Trash2 />
                  </Button>
                )}
              </div>
              {active === i && (
                <div className="mt-3">
                  <CategoryPicker categories={categories} kind={kind} value={l.categoryId || null} compact
                    onChange={(id) => setLines(lines.map((x, j) => (j === i ? { ...x, categoryId: id } : x)))} />
                </div>
              )}
            </div>
          ))}
        </div>
        <div className="mt-4 flex items-center justify-between">
          <Button variant="ghost" size="sm" onClick={() => { setLines([...lines, { categoryId: "", amount: "0.00" }]); setActive(lines.length); }}>
            <Plus /> Add part
          </Button>
          <span className={`num text-sm ${remaining === 0 ? "text-in" : "text-out"}`}>
            {remaining === 0 ? "Adds up" : `${inr(Math.abs(remaining), { paise: true })} ${remaining > 0 ? "left to assign" : "over"}`}
          </span>
        </div>
        <Button className="mt-5 w-full" disabled={!valid || split.isPending}
          onClick={() => split.mutate({ id: txn.id, lines }, {
            onSuccess: () => { toast.success("Split saved"); onOpenChange(false); onDone?.(); },
            onError: (e) => toast.error(errorText(e)),
          })}>
          Save split
        </Button>
      </DialogContent>
    </Dialog>
  );
}

export function TransferDialog({ txn, accounts, open, onOpenChange, onDone }: {
  txn: Transaction; accounts: Account[]; open: boolean; onOpenChange: (o: boolean) => void; onDone?: () => void;
}) {
  const others = accounts.filter((a) => !a.archived && a.id !== txn.account?.id);
  const [target, setTarget] = useState<string>(others.find((a) => a.type === "CREDIT_CARD" && txn.suggestionReason?.includes("credit card"))?.id ?? "");
  const mark = useMarkTransfer();
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent title="Mark as transfer"
        description="Money moving between your own accounts isn't income or spending. Pick the other account so both balances stay right.">
        <Field label={txn.direction === "DEBIT" ? "Money went to" : "Money came from"} htmlFor="transfer-target">
          <Select id="transfer-target" value={target} onChange={(e) => setTarget(e.target.value)}>
            <option value="">An account I don&apos;t track in Arth</option>
            {others.map((a) => (
              <option key={a.id} value={a.id}>{a.name}{a.last4 ? ` ••${a.last4}` : ""}</option>
            ))}
          </Select>
        </Field>
        <p className="mt-2 text-xs text-ink-3">
          If the other side is already imported, Arth pairs them. Otherwise it records the other side for you.
        </p>
        <Button className="mt-5 w-full" disabled={mark.isPending}
          onClick={() => mark.mutate({ id: txn.id, counterpartAccountId: target || null }, {
            onSuccess: (r) => { toast.success(r.counterpartCreated ? "Marked as transfer, other side recorded" : "Marked as transfer"); onOpenChange(false); onDone?.(); },
            onError: (e) => toast.error(errorText(e)),
          })}>
          Mark as transfer
        </Button>
      </DialogContent>
    </Dialog>
  );
}

export function NewCategoryDialog({ categories, kind, open, onOpenChange, onCreated }: {
  categories: Category[]; kind: CategoryKind; open: boolean; onOpenChange: (o: boolean) => void; onCreated?: (c: Category) => void;
}) {
  const qc = useQueryClient();
  const [name, setName] = useState("");
  const [parentId, setParentId] = useState("");
  const [color, setColor] = useState("#0F766E");
  const [icon, setIcon] = useState("tag");
  const [busy, setBusy] = useState(false);
  const parents = categories.filter((c) => c.kind === kind && !c.archived);

  async function create() {
    setBusy(true);
    try {
      const c = await api.post<Category>("/categories", { name, parentId: parentId || null, kind, color, icon });
      await qc.invalidateQueries({ queryKey: ["categories"] });
      toast.success(`Created ${c.path}`);
      onCreated?.(c);
      onOpenChange(false);
      setName("");
    } catch (e) {
      toast.error(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent title={kind === "EXPENSE" ? "New spending category" : "New income category"}>
        <div className="space-y-4">
          <Field label="Name" htmlFor="cat-name">
            <Input id="cat-name" value={name} maxLength={60} onChange={(e) => setName(e.target.value)} placeholder="e.g. Pet care" />
          </Field>
          <Field label="Inside" htmlFor="cat-parent" hint="Choose a parent to create a subcategory.">
            <Select id="cat-parent" value={parentId} onChange={(e) => setParentId(e.target.value)}>
              <option value="">Top level</option>
              {parents.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
            </Select>
          </Field>
          {!parentId && (
            <div className="grid grid-cols-[auto_1fr] items-end gap-4">
              <Field label="Colour" htmlFor="cat-color">
                <input id="cat-color" type="color" value={color} onChange={(e) => setColor(e.target.value)} className="h-10 w-14 cursor-pointer rounded-xl border border-line bg-surface p-1" />
              </Field>
              <div>
                <span className="mb-1.5 block text-[13px] font-semibold text-ink-2">Icon</span>
                <div className="flex max-h-24 flex-wrap gap-1 overflow-y-auto">
                  {CATEGORY_ICON_NAMES.map((n) => (
                    <button key={n} type="button" aria-label={n} aria-pressed={icon === n} onClick={() => setIcon(n)}
                      className={`rounded-xl p-0.5 ${icon === n ? "ring-2 ring-peacock-2" : ""}`}>
                      <CategoryIcon icon={n} color={color} size="sm" />
                    </button>
                  ))}
                </div>
              </div>
            </div>
          )}
          <Button className="w-full" disabled={!name.trim() || busy} onClick={create}>Create category</Button>
        </div>
      </DialogContent>
    </Dialog>
  );
}

export function ManualTransactionDialog({ accounts, categories, open, onOpenChange }: {
  accounts: Account[]; categories: Category[]; open: boolean; onOpenChange: (o: boolean) => void;
}) {
  const create = useCreateTransaction();
  const active = accounts.filter((a) => !a.archived);
  const [form, setForm] = useState({
    accountId: active.find((a) => a.type === "CASH")?.id ?? active[0]?.id ?? "",
    direction: "DEBIT" as "DEBIT" | "CREDIT",
    amount: "",
    date: isoDay(new Date()),
    time: "",
    merchant: "",
    paymentMethod: "CASH",
    paymentProvider: "",
    notes: "",
    categoryId: "",
  });
  const set = (patch: Partial<typeof form>) => setForm({ ...form, ...patch });
  const [transfer, setTransfer] = useState(false);

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent title={transfer ? "Move money between your accounts" : "Add a transaction"}
        description={transfer ? "Money out of one account and into another. It's a transfer, so it isn't counted as spending or income." : "For cash and anything your bank alerts or statements don't cover."} wide>
        <div className="mb-4 flex rounded-full bg-surface-2 p-1" role="radiogroup" aria-label="Type">
          {(["DEBIT", "CREDIT", "TRANSFER"] as const).map((d) => {
            const on = d === "TRANSFER" ? transfer : !transfer && form.direction === d;
            return (
              <button key={d} role="radio" aria-checked={on}
                onClick={() => { if (d === "TRANSFER") setTransfer(true); else { setTransfer(false); set({ direction: d, categoryId: "" }); } }}
                className={`flex-1 rounded-full py-1.5 text-sm font-semibold ${on ? "bg-surface shadow-lift" : "text-ink-2"}`}>
                {d === "DEBIT" ? "Money out" : d === "CREDIT" ? "Money in" : "Transfer"}
              </button>
            );
          })}
        </div>
        {transfer ? <TransferFields accounts={active} defaultFrom={form.accountId} onDone={() => onOpenChange(false)} /> : (<>
        <div className="grid gap-4 sm:grid-cols-2">
          <Field label="Amount (₹)" htmlFor="m-amount">
            <AmountInput id="m-amount" className="num text-lg" value={form.amount} placeholder="0.00 or a sum, e.g. 450+120"
              onValueChange={(v) => setForm((f) => ({ ...f, amount: v }))} />
          </Field>
          <Field label="Account" htmlFor="m-account">
            <Select id="m-account" value={form.accountId} onChange={(e) => set({ accountId: e.target.value })}>
              {active.map((a) => <option key={a.id} value={a.id}>{a.name}</option>)}
            </Select>
          </Field>
          <Field label={form.direction === "DEBIT" ? "Paid to" : "Received from"} htmlFor="m-merchant">
            <Input id="m-merchant" value={form.merchant} onChange={(e) => set({ merchant: e.target.value })} placeholder="e.g. Chai stall" />
          </Field>
          <Field label={form.direction === "DEBIT" ? "Paid with" : "Received via"} htmlFor="m-method">
            <Select id="m-method" value={form.paymentProvider ? `UPI:${form.paymentProvider}` : form.paymentMethod}
              onChange={(e) => {
                const v = e.target.value;
                if (v.startsWith("UPI:")) set({ paymentMethod: "UPI", paymentProvider: v.slice(4) });
                else set({ paymentMethod: v, paymentProvider: "" });
              }}>
              <option value="CASH">Cash</option>
              <optgroup label="UPI">
                <option value="UPI">UPI (any app)</option>
                {UPI_APPS.map((app) => <option key={app} value={`UPI:${app}`}>{app}</option>)}
              </optgroup>
              <option value="CARD">Card</option>
              <option value="IMPS">IMPS</option>
              <option value="NEFT">NEFT</option>
              <option value="OTHER">Other</option>
            </Select>
          </Field>
          <Field label="Date" htmlFor="m-date">
            <Input id="m-date" type="date" value={form.date} max={isoDay(new Date())} onChange={(e) => set({ date: e.target.value })} />
          </Field>
          <Field label="Time (optional)" htmlFor="m-time">
            <Input id="m-time" type="time" value={form.time} onChange={(e) => set({ time: e.target.value })} />
          </Field>
          <div className="sm:col-span-2">
            <span className="mb-1.5 block text-[13px] font-semibold text-ink-2">Category (optional: leave empty to review later)</span>
            <CategoryPicker categories={categories} kind={form.direction === "DEBIT" ? "EXPENSE" : "INCOME"} value={form.categoryId || null} compact
              onChange={(id) => set({ categoryId: id === form.categoryId ? "" : id })} />
          </div>
          <Field label="Note" htmlFor="m-note">
            <Input id="m-note" value={form.notes} onChange={(e) => set({ notes: e.target.value })} />
          </Field>
        </div>
        <Button className="mt-5 w-full" disabled={!form.amount || Number(form.amount) <= 0 || !form.accountId || create.isPending}
          onClick={() => create.mutate({
            ...form, time: form.time ? form.time + ":00" : null, categoryId: form.categoryId || null, merchant: form.merchant || null, notes: form.notes || null,
            paymentProvider: form.paymentProvider || null,
          }, {
            onSuccess: () => { toast.success("Transaction added"); onOpenChange(false); set({ amount: "", merchant: "", notes: "", categoryId: "" }); },
            onError: (e) => toast.error(errorText(e)),
          })}>
          Add transaction
        </Button>
        </>)}
      </DialogContent>
    </Dialog>
  );
}

/** Transfer between two of your own accounts: both sides are recorded and linked in one step. */
function TransferFields({ accounts, defaultFrom, onDone }: { accounts: Account[]; defaultFrom: string; onDone: () => void }) {
  const invalidate = useInvalidateMoney();
  const banks = accounts;
  const firstOther = (id: string) => banks.find((a) => a.id !== id)?.id ?? "";
  const from0 = banks.some((a) => a.id === defaultFrom) && banks.find((a) => a.id === defaultFrom)?.type !== "CASH" ? defaultFrom : banks[0]?.id ?? "";
  const [f, setF] = useState({ from: from0, to: firstOther(from0), amount: "", date: isoDay(new Date()), time: "", method: "UPI", notes: "" });
  const [busy, setBusy] = useState(false);
  const set = (p: Partial<typeof f>) => setF((x) => ({ ...x, ...p }));
  const same = f.from === f.to;

  if (banks.length < 2) {
    return <p className="rounded-2xl bg-surface-2 p-4 text-sm text-ink-2">Add a second account first (Accounts), then you can move money between them.</p>;
  }

  async function save() {
    setBusy(true);
    try {
      const r = await api.post<{ matchedExisting: boolean }>("/transactions/transfer", {
        fromAccountId: f.from, toAccountId: f.to, amount: f.amount, date: f.date, time: f.time ? f.time + ":00" : null,
        paymentMethod: f.method, notes: f.notes || null,
      });
      invalidate();
      toast.success(r.matchedExisting ? "Transfer recorded and matched with the money-in already in the other account" : "Transfer recorded");
      onDone();
    } catch (e) {
      toast.error(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  const name = (id: string) => banks.find((a) => a.id === id)?.name ?? "";
  return (
    <>
      <div className="grid gap-4 sm:grid-cols-2">
        <Field label="Amount (₹)" htmlFor="t-amount">
          <AmountInput id="t-amount" className="num text-lg" value={f.amount} placeholder="0.00" onValueChange={(v) => set({ amount: v })} />
        </Field>
        <Field label="Sent via" htmlFor="t-method">
          <Select id="t-method" value={f.method} onChange={(e) => set({ method: e.target.value })}>
            {[["UPI", "UPI"], ["IMPS", "IMPS"], ["NEFT", "NEFT"], ["RTGS", "RTGS"], ["CASH", "Cash (withdrawn or deposited)"], ["OTHER", "Other"]].map(([v, l]) => <option key={v} value={v}>{l}</option>)}
          </Select>
        </Field>
        <Field label="From" htmlFor="t-from">
          <Select id="t-from" value={f.from} onChange={(e) => set({ from: e.target.value, to: e.target.value === f.to ? firstOther(e.target.value) : f.to })}>
            {banks.map((a) => <option key={a.id} value={a.id}>{a.name}</option>)}
          </Select>
        </Field>
        <Field label="To" htmlFor="t-to">
          <Select id="t-to" value={f.to} onChange={(e) => set({ to: e.target.value })}>
            {banks.map((a) => <option key={a.id} value={a.id} disabled={a.id === f.from}>{a.name}</option>)}
          </Select>
        </Field>
        <Field label="Date" htmlFor="t-date">
          <Input id="t-date" type="date" value={f.date} max={isoDay(new Date())} onChange={(e) => set({ date: e.target.value })} />
        </Field>
        <Field label="Time (optional)" htmlFor="t-time">
          <Input id="t-time" type="time" value={f.time} onChange={(e) => set({ time: e.target.value })} />
        </Field>
        <div className="sm:col-span-2">
          <Field label="Note (optional)" htmlFor="t-note">
            <Input id="t-note" value={f.notes} onChange={(e) => set({ notes: e.target.value })} placeholder="e.g. to savings, card bill" />
          </Field>
        </div>
      </div>
      {f.amount && !same && (
        <p className="mt-4 rounded-2xl bg-surface-2 px-4 py-3 text-sm text-ink-2">
          <span className="num font-semibold text-out">−{inr(Number(f.amount))}</span> from {name(f.from)}, <span className="num font-semibold text-in">+{inr(Number(f.amount))}</span> to {name(f.to)}
        </p>
      )}
      <Button className="mt-5 w-full" disabled={busy || !f.amount || Number(f.amount) <= 0 || same || !f.from || !f.to} onClick={save}>
        {busy ? "Saving…" : "Record transfer"}
      </Button>
    </>
  );
}
