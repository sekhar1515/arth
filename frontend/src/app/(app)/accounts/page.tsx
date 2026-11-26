"use client";

import { Archive, Banknote, CreditCard, Landmark, Plus, Trash2, Wallet } from "lucide-react";
import { useSearchParams } from "next/navigation";
import { Suspense, useState } from "react";
import { toast } from "sonner";
import { useQueryClient } from "@tanstack/react-query";
import { PageHeader } from "@/components/layout/app-shell";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent } from "@/components/ui/dialog";
import { Field, Input, Select } from "@/components/ui/input";
import { ErrorState, Pill, Skeleton } from "@/components/ui/states";
import { useAccounts, useInvalidateMoney } from "@/hooks/queries";
import { api, ApiError } from "@/lib/api";
import { inr } from "@/lib/format";
import { cn } from "@/lib/utils";
import type { Account, AccountDeleteResult, AccountType } from "@/types/api";
import { AmountInput } from "@/components/ui/amount-input";
import { AccountMonthDialog } from "@/components/finance/account-month-dialog";

const TYPE_META: Record<AccountType, { label: string; icon: typeof Landmark }> = {
  BANK: { label: "Bank account", icon: Landmark },
  CREDIT_CARD: { label: "Credit card", icon: CreditCard },
  CASH: { label: "Cash", icon: Banknote },
  WALLET: { label: "Wallet", icon: Wallet },
};

function AccountDialog({ account, open, onOpenChange }: { account?: Account; open: boolean; onOpenChange: (o: boolean) => void }) {
  const qc = useQueryClient();
  const [form, setForm] = useState({
    name: account?.name ?? "", type: account?.type ?? ("BANK" as AccountType), institution: account?.institution ?? "",
    last4: account?.last4 ?? "", openingBalance: account ? String(account.openingBalance) : "", openingDate: account?.openingDate ?? "",
  });
  const [busy, setBusy] = useState(false);
  async function save() {
    setBusy(true);
    try {
      const body = { ...form, openingBalance: form.openingBalance || "0", openingDate: form.openingDate || null };
      if (account) await api.put(`/accounts/${account.id}`, body);
      else await api.post("/accounts", body);
      await qc.invalidateQueries({ queryKey: ["accounts"] });
      await qc.invalidateQueries({ queryKey: ["summary"] });
      toast.success(account ? "Account updated" : "Account added");
      onOpenChange(false);
    } catch (e) {
      toast.error(e instanceof ApiError ? e.message : "Couldn't save");
    } finally {
      setBusy(false);
    }
  }
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent title={account ? "Edit account" : "Add an account"} description="Arth matches bank alerts to accounts by their last digits. Never enter a full account or card number.">
        <div className="space-y-4">
          <Field label="Name" htmlFor="a-name"><Input id="a-name" value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} placeholder="HDFC Savings" /></Field>
          <div className="grid grid-cols-2 gap-3">
            <Field label="Type" htmlFor="a-type">
              <Select id="a-type" value={form.type} onChange={(e) => setForm({ ...form, type: e.target.value as AccountType })}>
                {Object.entries(TYPE_META).map(([k, v]) => <option key={k} value={k}>{v.label}</option>)}
              </Select>
            </Field>
            <Field label="Last 4 digits" htmlFor="a-last4" hint="As shown in SMS, e.g. XX4821">
              <Input id="a-last4" inputMode="numeric" maxLength={4} value={form.last4} onChange={(e) => setForm({ ...form, last4: e.target.value.replace(/\D/g, "") })} />
            </Field>
          </div>
          <Field label="Bank or issuer" htmlFor="a-inst"><Input id="a-inst" value={form.institution} onChange={(e) => setForm({ ...form, institution: e.target.value })} placeholder="HDFC Bank" /></Field>
          <div className="grid grid-cols-2 gap-3">
            <Field label={form.type === "CREDIT_CARD" ? "Outstanding on start date (negative)" : "Balance on start date"} htmlFor="a-open">
              <AmountInput id="a-open" allowNegative className="num" value={form.openingBalance} onValueChange={(v) => setForm({ ...form, openingBalance: v })} placeholder="0.00" />
            </Field>
            <Field label="Start date" htmlFor="a-date"><Input id="a-date" type="date" value={form.openingDate} onChange={(e) => setForm({ ...form, openingDate: e.target.value })} /></Field>
          </div>
          <p className="text-xs text-ink-3">Use the balance just before the first transaction you&apos;ll import. Arth adds imported transactions to it.</p>
          <Button className="w-full" disabled={!form.name.trim() || busy} onClick={save}>{account ? "Save changes" : "Add account"}</Button>
        </div>
      </DialogContent>
    </Dialog>
  );
}

function AccountsInner() {
  const accounts = useAccounts();
  const qc = useQueryClient();
  const welcome = useSearchParams().get("welcome") === "1";
  const [editing, setEditing] = useState<Account | null>(null);
  const [viewing, setViewing] = useState<Account | null>(null);
  const [balanceFor, setBalanceFor] = useState<Account | null>(null);
  const [adding, setAdding] = useState(false);
  const active = accounts.data?.filter((a) => !a.archived) ?? [];
  const archived = accounts.data?.filter((a) => a.archived) ?? [];
  const assets = active.filter((a) => a.type !== "CREDIT_CARD" || a.balance > 0).reduce((s, a) => s + a.balance, 0);
  const owed = active.filter((a) => a.type === "CREDIT_CARD" && a.balance < 0).reduce((s, a) => s - a.balance, 0);

  const [deleting, setDeleting] = useState<Account | null>(null);

  async function archive(a: Account) {
    await api.post(`/accounts/${a.id}/${a.archived ? "restore" : "archive"}`);
    qc.invalidateQueries({ queryKey: ["accounts"] });
    toast.success(a.archived ? "Account restored" : "Account archived");
  }

  return (
    <>
      <PageHeader title="Accounts" subtitle="Balances are worked out from each account's starting balance plus imported transactions. They aren't fetched live from your bank."
        actions={<Button size="sm" onClick={() => setAdding(true)}><Plus /> Add account</Button>} />
      {welcome && (
        <div className="mb-6 rounded-3xl border border-peacock-2/40 bg-peacock-soft p-5 text-sm">
          <p className="font-display text-lg font-semibold text-peacock">Start with your accounts</p>
          <p className="mt-1 text-ink-2">Add the bank accounts and cards you use with PhonePe, Google Pay or Paytm. Then head to Integrations to import a statement or connect SMS alerts.</p>
        </div>
      )}
      {accounts.isError ? <ErrorState error={accounts.error} retry={() => accounts.refetch()} /> : !accounts.data ? <Skeleton className="h-64" /> : (
        <>
          <div className="mb-6 flex flex-wrap gap-x-10 gap-y-2">
            <div><p className="text-[13px] text-ink-2">You have</p><p className="num text-2xl font-semibold">{inr(assets)}</p></div>
            <div><p className="text-[13px] text-ink-2">You owe on cards</p><p className={cn("num text-2xl font-semibold", owed > 0 && "text-out")}>{inr(owed)}</p></div>
          </div>
          <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
            {active.map((a) => {
              const M = TYPE_META[a.type];
              const Icon = M.icon;
              return (
                <div key={a.id} className="rounded-3xl border border-line bg-surface p-5 transition-colors hover:border-peacock-2/60">
                  <button type="button" onClick={() => setViewing(a)} className="block w-full text-left" aria-label={`${a.name}: this month's transactions`}>
                  <div className="flex items-start justify-between">
                    <span className="grid size-10 place-items-center rounded-2xl bg-peacock-soft text-peacock"><Icon className="size-5" /></span>
                    {a.last4 && <span className="num text-sm text-ink-3">•• {a.last4}</span>}
                  </div>
                  <p className="mt-4 font-display text-lg font-semibold">{a.name}</p>
                  <p className="text-[13px] text-ink-3">{a.institution ?? M.label}</p>
                  <p className={cn("num mt-3 text-2xl font-semibold", a.balance < 0 && "text-out")}>
                    {a.type === "CREDIT_CARD" && a.balance < 0 ? `${inr(-a.balance)} owed` : inr(a.balance)}
                  </p>
                  <div className="mt-1 flex items-center gap-2 text-xs text-ink-3">
                    <span>{a.transactionCount} transactions</span>
                    {a.balanceSource === "OPENING_BALANCE" && <Pill>Starting balance only</Pill>}
                    <span className="ml-auto font-semibold text-peacock">This month →</span>
                  </div>
                  </button>
                  <div className="mt-4 flex gap-1">
                    <Button variant="ghost" size="sm" onClick={() => setBalanceFor(a)}>Set balance</Button>
                    <Button variant="ghost" size="sm" onClick={() => setEditing(a)}>Edit</Button>
                    <Button variant="ghost" size="sm" onClick={() => archive(a)}><Archive /> Archive</Button>
                    <Button variant="ghost" size="sm" className="ml-auto text-out hover:bg-out-soft hover:text-out" onClick={() => setDeleting(a)} aria-label={`Delete ${a.name}`}><Trash2 /></Button>
                  </div>
                </div>
              );
            })}
          </div>
          {archived.length > 0 && (
            <details className="mt-8">
              <summary className="cursor-pointer text-sm font-semibold text-ink-2">Archived accounts ({archived.length})</summary>
              <ul className="mt-3 space-y-2">
                {archived.map((a) => (
                  <li key={a.id} className="flex items-center justify-between rounded-2xl border border-line bg-surface px-4 py-2 text-sm">
                    <span>{a.name}</span>
                    <span className="flex gap-1">
                      <Button variant="ghost" size="sm" onClick={() => archive(a)}>Restore</Button>
                      <Button variant="ghost" size="sm" className="text-out hover:bg-out-soft hover:text-out" onClick={() => setDeleting(a)}>Delete</Button>
                    </span>
                  </li>
                ))}
              </ul>
            </details>
          )}
        </>
      )}
      {balanceFor && <SetBalanceDialog account={balanceFor} onClose={() => setBalanceFor(null)} />}
      {viewing && <AccountMonthDialog accountId={viewing.id} name={viewing.name} isCard={viewing.type === "CREDIT_CARD"} onClose={() => setViewing(null)} />}
      {deleting && <DeleteAccountDialog account={deleting} onClose={() => setDeleting(null)} />}
      {adding && <AccountDialog open onOpenChange={setAdding} />}
      {editing && <AccountDialog account={editing} open onOpenChange={(o) => !o && setEditing(null)} />}
    </>
  );
}

function DeleteAccountDialog({ account, onClose }: { account: Account; onClose: () => void }) {
  const invalidate = useInvalidateMoney();
  const [withTxns, setWithTxns] = useState(false);
  const [busy, setBusy] = useState(false);
  const n = account.transactionCount;
  async function go() {
    setBusy(true);
    try {
      const r = await api.del<AccountDeleteResult>(`/accounts/${account.id}${n > 0 ? "?withTransactions=true" : ""}`);
      toast.success(`Deleted ${r.account}${r.transactionsDeleted ? ` and ${r.transactionsDeleted} transactions` : ""}`);
      invalidate();
      onClose();
    } catch (e) {
      toast.error(e instanceof ApiError ? e.message : "Couldn't delete");
      setBusy(false);
    }
  }
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent title={`Delete ${account.name}?`} description="Use this for an account you added by mistake. To keep its history, archive it instead.">
        {n > 0 ? (
          <label className="flex items-start gap-3 rounded-2xl border border-out/30 bg-out-soft p-4 text-sm">
            <input type="checkbox" className="mt-1 size-4 accent-[var(--out)]" checked={withTxns} onChange={(e) => setWithTxns(e.target.checked)} />
            <span>
              <span className="block font-semibold">Also delete its {n} transaction{n === 1 ? "" : "s"}</span>
              <span className="block text-[13px] text-ink-2">They disappear from every total. Transfers to your other accounts become normal transactions there and go back to Needs Review. This can&apos;t be undone.</span>
            </span>
          </label>
        ) : (
          <p className="text-sm text-ink-2">It has no transactions. This can&apos;t be undone.</p>
        )}
        <div className="mt-6 flex justify-end gap-2">
          <Button variant="ghost" onClick={onClose}>Cancel</Button>
          <Button variant="danger" disabled={busy || (n > 0 && !withTxns)} onClick={go}>Delete account</Button>
        </div>
      </DialogContent>
    </Dialog>
  );
}

export default function AccountsPage() {
  return <Suspense fallback={<Skeleton className="h-64" />}><AccountsInner /></Suspense>;
}

/**
 * "My bank app shows X today": sets the balance by moving the starting balance by the difference, so it matches the
 * bank without adding a made-up transaction. Credit cards are entered as the amount owed.
 */
function SetBalanceDialog({ account, onClose }: { account: Account; onClose: () => void }) {
  const invalidate = useInvalidateMoney();
  const card = account.type === "CREDIT_CARD";
  const shown = card ? Math.max(0, -account.balance) : account.balance;
  const [v, setV] = useState(String(Math.round(shown * 100) / 100));
  const [busy, setBusy] = useState(false);
  const target = v === "" ? null : card ? -Number(v) : Number(v);
  const diff = target === null ? 0 : Math.round((target - account.balance) * 100) / 100;

  async function save() {
    if (target === null) return;
    setBusy(true);
    try {
      await api.post(`/accounts/${account.id}/balance`, { balance: target });
      invalidate();
      toast.success(`${account.name} balance updated`);
      onClose();
    } catch (e) {
      toast.error(e instanceof ApiError ? e.message : "Couldn't update the balance");
      setBusy(false);
    }
  }

  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent title={`Set ${account.name} balance`}
        description={card ? "Enter what you owe on this card right now, as your card app shows it." : "Enter the balance your bank app shows right now."}>
        <Field label={card ? "Amount owed now (₹)" : "Balance now (₹)"} htmlFor="sb-amount">
          <AmountInput id="sb-amount" className="num text-lg" value={v} onValueChange={setV} allowNegative={!card} autoFocus />
        </Field>
        <p className="mt-3 text-sm text-ink-2">
          Arth shows <span className="num font-semibold text-ink">{card ? `${inr(shown)} owed` : inr(account.balance)}</span> now.
          {target !== null && diff !== 0 && (
            <> It will change by <span className={cn("num font-semibold", diff > 0 ? "text-in" : "text-out")}>{diff > 0 ? "+" : "−"}{inr(Math.abs(diff), { paise: diff % 1 !== 0 })}</span>.</>
          )}
        </p>
        <p className="mt-2 text-xs text-ink-3">
          This corrects the starting balance; your transactions and spending totals stay as they are. If the difference is a payment Arth missed, add it as a transaction instead so it shows up in your spending.
        </p>
        <div className="mt-5 flex justify-end gap-2">
          <Button variant="ghost" onClick={onClose}>Cancel</Button>
          <Button disabled={busy || target === null || diff === 0} onClick={save}>{busy ? "Saving…" : "Update balance"}</Button>
        </div>
      </DialogContent>
    </Dialog>
  );
}
