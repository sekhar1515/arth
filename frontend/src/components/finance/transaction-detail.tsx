"use client";

import { Trash2 } from "lucide-react";
import { useState } from "react";
import { toast } from "sonner";
import { api, ApiError } from "@/lib/api";
import { formatDateTime, inr, merchantName, METHOD_LABEL, relativeTime, signed, SOURCE_LABEL, UPI_APPS } from "@/lib/format";
import type { Account, Category, Transaction } from "@/types/api";
import { useInvalidateMoney, useTransaction, useUpdateTransaction } from "@/hooks/queries";
import { useConfirm } from "@/components/ui/confirm";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent } from "@/components/ui/dialog";
import { Field, Input, Select } from "@/components/ui/input";
import { Skeleton } from "@/components/ui/states";
import { TransactionApprovalCard } from "./approval-card";
import { CategoryCell } from "./transaction-list";

const ACTION_LABEL: Record<string, string> = {
  CATEGORISED: "Categorised", BULK_CONFIRMED: "Accepted suggestion", AUTO_CATEGORISED: "Categorised automatically", SPLIT: "Split",
  MARKED_TRANSFER: "Marked as transfer", EXCLUDED: "Excluded", MARKED_REFUND: "Marked as refund", APPLIED_FROM_RULE: "Updated by your rule",
  DUPLICATE_KEPT: "Kept as separate", DUPLICATE_MERGED: "Duplicate merged in", TRANSFER_UNLINKED: "Transfer unlinked",
};

export function TransactionDetailDialog({ txn, categories, accounts, onClose }: {
  txn: Transaction; categories: Category[]; accounts: Account[]; onClose: () => void;
}) {
  const detail = useTransaction(txn.id);
  const update = useUpdateTransaction();
  const [editing, setEditing] = useState(false);
  const [recat, setRecat] = useState(false);
  const t = detail.data?.transaction ?? txn;
  const [form, setForm] = useState({ notes: t.notes ?? "", tags: t.tags.join(", "), paymentProvider: t.paymentProvider ?? "", merchant: "" });
  const { confirm, dialog } = useConfirm();
  const invalidate = useInvalidateMoney();
  const imported = (detail.data?.sources ?? []).some((s) => s.type !== "MANUAL");

  async function remove() {
    const ok = await confirm({
      title: "Delete this transaction?",
      body: (
        <>
          <p>{merchantName(t)}, {signed(t.amount, t.direction)} on {formatDateTime(t.txnAt, t.timeKnown)}.</p>
          <p className="mt-2">
            {imported ? "It came from an import, so Arth remembers you deleted it and won't add it back if you import the same statement again. " : ""}
            {t.kind === "TRANSFER" ? "The other side of this transfer goes back to Needs Review. " : ""}
            This can&apos;t be undone.
          </p>
        </>
      ),
      confirmLabel: "Delete", danger: true,
    });
    if (!ok) return;
    try {
      await api.del(`/transactions/${t.id}`);
      toast.success("Transaction deleted");
      invalidate();
      onClose();
    } catch (e) {
      toast.error(e instanceof ApiError ? e.message : "Couldn't delete");
    }
  }

  function save() {
    update.mutate(
      {
        id: t.id, version: t.version, notes: form.notes, paymentProvider: form.paymentProvider,
        tags: form.tags.split(",").map((x) => x.trim()).filter(Boolean), ...(form.merchant ? { merchant: form.merchant } : {}),
      },
      { onSuccess: () => { toast.success("Saved"); setEditing(false); }, onError: (e) => toast.error(e instanceof ApiError ? e.message : "Couldn't save") },
    );
  }

  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent title={merchantName(t)} description={`${formatDateTime(t.txnAt, t.timeKnown)}, ${t.account?.name ?? ""}`} wide>
        {recat ? (
          <TransactionApprovalCard txn={{ ...t, suggestedCategory: t.category ?? t.suggestedCategory, duplicateStatus: "NONE" }}
            categories={categories} accounts={accounts} onDone={onClose} />
        ) : (
          <>
            <div className="flex items-end justify-between gap-4 rounded-2xl bg-surface-2 p-4">
              <div>
                <p className="num text-3xl font-semibold">{signed(t.amount, t.direction)}</p>
                <div className="mt-2"><CategoryCell t={t} /></div>
              </div>
              <Button variant="outline" size="sm" onClick={() => setRecat(true)}>Change category</Button>
            </div>

            {t.splits.length > 0 && (
              <ul className="mt-4 space-y-1 text-sm">
                {t.splits.map((s) => (
                  <li key={s.id} className="flex justify-between"><span>{s.category.path}</span><span className="num">{inr(s.amount, { paise: true })}</span></li>
                ))}
              </ul>
            )}

            <dl className="mt-5 grid grid-cols-2 gap-x-6 gap-y-3 text-sm">
              <Detail label="Paid with" value={`${METHOD_LABEL[t.paymentMethod]}${t.paymentProvider ? ` via ${t.paymentProvider}` : ""}`} />
              <Detail label="Reference" value={t.referenceNumber ?? "None"} mono />
              <Detail label="Status" value={t.status === "POSTED" ? "Posted" : t.status.toLowerCase()} />
              <Detail label="Personal or business" value={t.business ? "Business" : "Personal"} />
              {t.notes && <Detail label="Note" value={t.notes} wide />}
              {t.tags.length > 0 && <Detail label="Tags" value={t.tags.join(", ")} wide />}
              {t.description && <Detail label="Bank description" value={t.description} mono wide />}
            </dl>

            {editing ? (
              <div className="mt-5 grid gap-3 rounded-2xl border border-line p-4 sm:grid-cols-2">
                <Field label="Note" htmlFor="d-note"><Input id="d-note" value={form.notes} onChange={(e) => setForm({ ...form, notes: e.target.value })} /></Field>
                <Field label="Tags" htmlFor="d-tags" hint="Comma separated"><Input id="d-tags" value={form.tags} onChange={(e) => setForm({ ...form, tags: e.target.value })} /></Field>
                <Field label="Payment app" htmlFor="d-app">
                  <Select id="d-app" value={form.paymentProvider} onChange={(e) => setForm({ ...form, paymentProvider: e.target.value })}>
                    <option value="">Not known</option>
                    {UPI_APPS.map((p) => <option key={p}>{p}</option>)}
                  </Select>
                </Field>
                <Field label="Rename merchant" htmlFor="d-merchant"><Input id="d-merchant" placeholder={merchantName(t)} value={form.merchant} onChange={(e) => setForm({ ...form, merchant: e.target.value })} /></Field>
                <div className="flex gap-2 sm:col-span-2">
                  <Button onClick={save} disabled={update.isPending}>Save changes</Button>
                  <Button variant="ghost" onClick={() => setEditing(false)}>Cancel</Button>
                </div>
              </div>
            ) : (
              <div className="mt-4 flex flex-wrap gap-2">
                <Button variant="secondary" size="sm" onClick={() => setEditing(true)}>Edit note, tags or payment app</Button>
                <Button variant="ghost" size="sm" className="text-out hover:bg-out-soft hover:text-out" onClick={remove}><Trash2 /> Delete transaction</Button>
              </div>
            )}

            <div className="mt-6 grid gap-6 sm:grid-cols-2">
              <div>
                <h3 className="mb-2 text-[13px] font-semibold text-ink-2">Where it came from</h3>
                {detail.isPending ? <Skeleton className="h-12" /> : (
                  <ul className="space-y-2 text-sm">
                    {detail.data?.sources.map((s) => (
                      <li key={s.id}>
                        <p className="font-medium">{SOURCE_LABEL[s.type]}</p>
                        <p className="text-xs text-ink-3">Imported {relativeTime(s.importedAt)}</p>
                      </li>
                    ))}
                  </ul>
                )}
              </div>
              <div>
                <h3 className="mb-2 text-[13px] font-semibold text-ink-2">History</h3>
                {detail.data?.history.length === 0 && <p className="text-sm text-ink-3">No decisions yet.</p>}
                <ol className="space-y-2 text-sm">
                  {detail.data?.history.map((h, i) => (
                    <li key={i}>
                      <p className="font-medium">{ACTION_LABEL[h.action] ?? h.action}{h.to ? `: ${h.to.path}` : ""}</p>
                      <p className="text-xs text-ink-3">{relativeTime(h.at)}{h.from ? `, was ${h.from.path}` : ""}</p>
                    </li>
                  ))}
                </ol>
              </div>
            </div>
          </>
        )}
        {dialog}
      </DialogContent>
    </Dialog>
  );
}

function Detail({ label, value, mono, wide }: { label: string; value: string; mono?: boolean; wide?: boolean }) {
  return (
    <div className={wide ? "col-span-2" : undefined}>
      <dt className="text-xs text-ink-3">{label}</dt>
      <dd className={mono ? "break-all font-mono text-[12px]" : "font-medium"}>{value}</dd>
    </div>
  );
}
