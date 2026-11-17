"use client";

import { ArrowLeftRight, Ban, Briefcase, Copy, FolderPlus, Scissors, StickyNote } from "lucide-react";
import { useEffect, useMemo, useState } from "react";
import { toast } from "sonner";
import { ApiError } from "@/lib/api";
import { formatDateTime, initials, merchantName, METHOD_LABEL, signed, SOURCE_LABEL, inr } from "@/lib/format";
import { cn } from "@/lib/utils";
import type { Account, Category, RememberScope, Transaction } from "@/types/api";
import { useCategorise, useExclude, useResolveDuplicate } from "@/hooks/queries";
import { Button } from "@/components/ui/button";
import { Pill } from "@/components/ui/states";
import { CategoryPicker, findCategory } from "./category-picker";
import { NewCategoryDialog, SplitDialog, TransferDialog } from "./dialogs";

const MONOGRAM_COLORS = ["#0F5E63", "#7C3AED", "#B45309", "#BE185D", "#1D4ED8", "#047857", "#9333EA", "#C2410C"];

function monogramColor(name: string) {
  let h = 0;
  for (const ch of name) h = (h * 31 + ch.charCodeAt(0)) >>> 0;
  return MONOGRAM_COLORS[h % MONOGRAM_COLORS.length];
}

export function Monogram({ name, className }: { name: string; className?: string }) {
  const color = monogramColor(name);
  return (
    <span className={cn("grid size-11 shrink-0 place-items-center rounded-2xl font-display text-[15px] font-bold", className)}
      style={{ backgroundColor: color + "1f", color }} aria-hidden>
      {initials(name)}
    </span>
  );
}

const REMEMBER_OPTIONS: { value: RememberScope; label: (m: string, note: string) => string; needs: "merchant" | "note" | null }[] = [
  { value: "ONLY_THIS", label: () => "Only this one", needs: null },
  { value: "NOTE", label: (_m, note) => `Always when my note says "${note}"`, needs: "note" },
  { value: "MERCHANT", label: (m) => `Always for ${m}`, needs: "merchant" },
  { value: "APPLY_HISTORICAL", label: () => "Always for this payee, and update past ones", needs: "merchant" },
  { value: "DESCRIPTION", label: () => "Match this description", needs: null },
];

function shortNote(note: string | null) {
  if (!note) return "";
  const n = note.trim();
  return n.length > 24 ? n.slice(0, 24) + "…" : n;
}

/**
 * The approval slip: one pending transaction, its suggestion, and every action the user can take on it.
 * Keyboard: Enter confirms, 1–9 pick a category, S split, T transfer, X exclude.
 */
export function TransactionApprovalCard({
  txn, categories, accounts, onDone, autoFocus,
}: {
  txn: Transaction; categories: Category[]; accounts: Account[]; onDone?: () => void; autoFocus?: boolean;
}) {
  const name = merchantName(txn);
  const kind = txn.direction === "DEBIT" ? "EXPENSE" : "ANY";
  const [selected, setSelected] = useState<string | null>(txn.suggestedCategory?.id ?? null);
  const [remember, setRemember] = useState<RememberScope>(txn.notes ? "NOTE" : txn.merchant ? "MERCHANT" : "ONLY_THIS");
  const [note, setNote] = useState("");
  const [showNote, setShowNote] = useState(false);
  const [business, setBusiness] = useState(txn.business);
  const [leaving, setLeaving] = useState(false);
  const [dialog, setDialog] = useState<null | "split" | "transfer" | "category">(null);
  const categorise = useCategorise();
  const exclude = useExclude();
  const resolve = useResolveDuplicate();
  const selectedCat = findCategory(categories, selected);
  const isDuplicate = txn.duplicateStatus === "SUSPECTED";
  const transferHint = !txn.suggestedCategory && !!txn.suggestionReason && /transfer|credit card/i.test(txn.suggestionReason);
  const visibleParents = useMemo(
    () => categories.filter((c) => !c.archived && (kind === "ANY" || c.kind === kind)),
    [categories, kind],
  );

  function finish(message: string) {
    setLeaving(true);
    toast.success(message);
    setTimeout(() => onDone?.(), 200);
  }

  function confirm() {
    if (!selected) return;
    categorise.mutate(
      { id: txn.id, categoryId: selected, remember, note: note || undefined, business,
        descriptionPattern: remember === "NOTE" && txn.notes ? txn.notes : undefined },
      {
        onSuccess: (r) => {
          const extra = r.appliedToOthers > 0 ? ` and ${r.appliedToOthers} past transaction${r.appliedToOthers > 1 ? "s" : ""}` : "";
          finish(`${name} → ${selectedCat?.path ?? "categorised"}${extra}${r.ruleSaved ? ". Rule saved" : ""}`);
        },
        onError: (e) => toast.error(e instanceof ApiError ? e.message : "Couldn't save"),
      },
    );
  }

  useEffect(() => {
    if (!autoFocus || dialog) return;
    function onKey(e: KeyboardEvent) {
      const target = e.target as HTMLElement;
      if (["INPUT", "TEXTAREA", "SELECT"].includes(target.tagName) || e.metaKey || e.ctrlKey || e.altKey) return;
      if (e.key === "Enter" && selected && !isDuplicate) { e.preventDefault(); confirm(); }
      else if (/^[1-9]$/.test(e.key)) { const p = visibleParents[Number(e.key) - 1]; if (p) setSelected(p.id); }
      else if (e.key.toLowerCase() === "s" && !isDuplicate) setDialog("split");
      else if (e.key.toLowerCase() === "t" && !isDuplicate) setDialog("transfer");
      else if (e.key.toLowerCase() === "x" && !isDuplicate) exclude.mutate({ id: txn.id }, { onSuccess: () => finish("Excluded from totals") });
    }
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  });

  const confidence = txn.suggestionConfidence ?? 0;

  return (
    <article
      className={cn("relative overflow-hidden rounded-3xl border bg-surface shadow-lift", isDuplicate ? "border-marigold/60" : "border-line", leaving && "slip-out")}
      aria-label={`${name}, ${signed(txn.amount, txn.direction)}`}
    >
      {/* perforated edge: this is a slip waiting to be filed */}
      <div className="absolute inset-y-0 left-0 w-1.5 bg-[radial-gradient(circle,var(--paper)_2px,transparent_2.5px)] bg-[length:6px_10px]" aria-hidden />
      <div className="p-5 pl-7 sm:p-6 sm:pl-8">
        <header className="flex items-start gap-3.5">
          <Monogram name={name} />
          <div className="min-w-0 flex-1">
            <h3 className="truncate font-display text-lg font-semibold leading-tight">{name}</h3>
            <p className="mt-0.5 flex flex-wrap gap-x-3 text-[13px] text-ink-2">
              <span>{formatDateTime(txn.txnAt, txn.timeKnown)}</span>
              <span>{METHOD_LABEL[txn.paymentMethod]}{txn.paymentProvider ? ` via ${txn.paymentProvider}` : ""}</span>
              <span>{txn.account?.name}</span>
            </p>
          </div>
          <div className="text-right">
            <p className={cn("num text-2xl font-semibold sm:text-[28px]", txn.direction === "DEBIT" ? "text-ink" : "text-in")}>
              {signed(txn.amount, txn.direction)}
            </p>
            <p className="mt-0.5 text-xs text-ink-3">{txn.sources.map((s) => SOURCE_LABEL[s]).join(" + ")}</p>
          </div>
        </header>

        {txn.notes && (
          <p className="mt-4 inline-flex max-w-full items-center gap-2 rounded-2xl bg-peacock-soft px-3 py-1.5 text-sm text-peacock">
            <StickyNote className="size-4 shrink-0" />
            <span className="truncate">Your note: <strong>{txn.notes}</strong></span>
          </p>
        )}
        {txn.description && txn.description !== name && txn.description !== txn.notes && (
          <p className="mt-3 truncate rounded-xl bg-surface-2 px-3 py-1.5 font-mono text-[12px] text-ink-3" title={txn.description}>{txn.description}</p>
        )}

        {isDuplicate ? (
          <div className="mt-5 rounded-2xl bg-marigold-soft p-4">
            <p className="flex items-center gap-2 text-sm font-semibold text-marigold-ink"><Copy className="size-4" /> This looks like a transaction Arth already has</p>
            <p className="mt-1 text-sm text-marigold-ink/90">
              Same account, same amount, around the same time. It&apos;s left out of your totals until you decide.
            </p>
            <div className="mt-4 flex flex-wrap gap-2">
              <Button variant="attention" disabled={resolve.isPending}
                onClick={() => resolve.mutate({ id: txn.id, action: "MERGE" }, { onSuccess: () => finish("Merged into the existing transaction") })}>
                It&apos;s the same: merge
              </Button>
              <Button variant="outline" disabled={resolve.isPending}
                onClick={() => resolve.mutate({ id: txn.id, action: "KEEP_BOTH" }, { onSuccess: () => finish("Kept as a separate transaction") })}>
                They&apos;re different: keep both
              </Button>
            </div>
          </div>
        ) : (
          <>
            {txn.suggestionReason && (
              <div className="mt-5 flex items-center gap-3">
                <div className="h-1.5 w-16 overflow-hidden rounded-full bg-surface-2" aria-hidden>
                  <div className="h-full rounded-full bg-peacock-2" style={{ width: `${Math.round(confidence * 100)}%` }} />
                </div>
                <p className="text-[13px] text-ink-2">
                  {txn.suggestedCategory ? <>Suggested <strong className="text-ink">{txn.suggestedCategory.path}</strong>. </> : null}
                  {txn.suggestionReason}
                </p>
              </div>
            )}
            {transferHint && (
              <Button variant="attention" className="mt-3" onClick={() => setDialog("transfer")}>
                <ArrowLeftRight /> Mark as transfer
              </Button>
            )}

            <div className="mt-4">
              <CategoryPicker categories={categories} kind={kind} value={selected} suggestedId={txn.suggestedCategory?.id} onChange={setSelected} />
            </div>

            {selected && (
              <fieldset className="mt-4">
                <legend className="mb-2 text-[13px] font-semibold text-ink-2">Remember this choice?</legend>
                <div className="flex flex-wrap gap-1.5">
                  {REMEMBER_OPTIONS.filter((o) => o.needs === null || (o.needs === "merchant" && txn.merchant) || (o.needs === "note" && txn.notes)).map((o) => (
                    <label key={o.value}
                      className={cn("cursor-pointer rounded-full border px-3 py-1 text-[13px] transition-colors",
                        remember === o.value ? "border-peacock bg-peacock-soft font-semibold text-peacock" : "border-line text-ink-2 hover:text-ink")}>
                      <input type="radio" name={`remember-${txn.id}`} className="sr-only" checked={remember === o.value} onChange={() => setRemember(o.value)} />
                      {o.label(name, shortNote(txn.notes))}
                    </label>
                  ))}
                </div>
              </fieldset>
            )}

            {showNote && (
              <textarea value={note} onChange={(e) => setNote(e.target.value)} placeholder={txn.notes ? "Replace your note" : "Add a note"} maxLength={1000} rows={2}
                className="mt-4 w-full rounded-xl border border-line bg-surface p-3 text-sm focus:border-peacock-2 focus:outline-none" />
            )}

            <div className="mt-5 flex flex-wrap items-center gap-2">
              <Button size="lg" disabled={!selected || categorise.isPending} onClick={confirm} className="min-w-44">
                {selectedCat ? `Confirm ${selectedCat.name}` : "Choose a category"}
              </Button>
              <div className="flex flex-wrap gap-1">
                <Button variant="ghost" size="sm" onClick={() => setDialog("split")} title="Split (S)"><Scissors /> Split</Button>
                {!transferHint && <Button variant="ghost" size="sm" onClick={() => setDialog("transfer")} title="Transfer (T)"><ArrowLeftRight /> Transfer</Button>}
                <Button variant="ghost" size="sm" onClick={() => setShowNote((v) => !v)}><StickyNote /> Note</Button>
                <Button variant="ghost" size="sm" aria-pressed={business} onClick={() => setBusiness((v) => !v)} className={business ? "text-peacock" : undefined}>
                  <Briefcase /> {business ? "Business" : "Personal"}
                </Button>
                <Button variant="ghost" size="sm" onClick={() => setDialog("category")}><FolderPlus /> New category</Button>
                <Button variant="ghost" size="sm" title="Exclude (X)"
                  onClick={() => exclude.mutate({ id: txn.id }, { onSuccess: () => finish("Excluded from totals"), onError: () => toast.error("Couldn't exclude") })}>
                  <Ban /> Exclude
                </Button>
              </div>
            </div>
          </>
        )}
        {txn.status === "PENDING" && <Pill tone="attention" className="mt-3">Bank shows this as pending</Pill>}
      </div>

      {dialog === "split" && <SplitDialog txn={txn} categories={categories} open onOpenChange={(o) => !o && setDialog(null)} onDone={() => finish(`Split ${inr(txn.amount)}`)} />}
      {dialog === "transfer" && <TransferDialog txn={txn} accounts={accounts} open onOpenChange={(o) => !o && setDialog(null)} onDone={() => { setLeaving(true); setTimeout(() => onDone?.(), 200); }} />}
      {dialog === "category" && (
        <NewCategoryDialog categories={categories} kind={txn.direction === "DEBIT" ? "EXPENSE" : "INCOME"} open
          onOpenChange={(o) => !o && setDialog(null)} onCreated={(c) => setSelected(c.id)} />
      )}
    </article>
  );
}
