"use client";

import { ArrowLeftRight, Ban, Copy, Scissors } from "lucide-react";
import { formatDate, formatTime, merchantName, METHOD_LABEL, signed } from "@/lib/format";
import { cn } from "@/lib/utils";
import type { Transaction } from "@/types/api";
import { Pill } from "@/components/ui/states";
import { CategoryIcon } from "./category-icon";
import { Monogram } from "./approval-card";

export function CategoryCell({ t }: { t: Transaction }) {
  if (t.duplicateStatus === "SUSPECTED") return <Pill tone="attention"><Copy className="size-3" /> Possible duplicate</Pill>;
  if (t.kind === "TRANSFER") return <Pill tone="brand"><ArrowLeftRight className="size-3" /> Transfer</Pill>;
  if (t.kind === "EXCLUDED") return <Pill><Ban className="size-3" /> {t.status === "FAILED" ? "Failed" : "Excluded"}</Pill>;
  if (t.splits.length > 0) return <Pill tone="brand"><Scissors className="size-3" /> Split into {t.splits.length}</Pill>;
  if (t.category) {
    return (
      <span className="inline-flex items-center gap-2 text-sm">
        <CategoryIcon icon={t.category.icon} color={t.category.color} size="sm" />
        <span className="truncate">{t.category.name}</span>
        {t.kind === "REFUND" && <Pill tone="in">Refund</Pill>}
        {t.categorisationStatus === "AUTO" && <span className="text-[11px] text-ink-3" title="Categorised automatically">auto</span>}
      </span>
    );
  }
  return <Pill tone="attention">Needs review</Pill>;
}

export interface Selection {
  selected: Set<string>;
  toggle: (id: string) => void;
  toggleAll: (ids: string[]) => void;
}

function Check({ checked, onChange, label }: { checked: boolean; onChange: () => void; label: string }) {
  return (
    <input type="checkbox" checked={checked} onChange={onChange} onClick={(e) => e.stopPropagation()} aria-label={label}
      className="size-4 cursor-pointer rounded border-line accent-[var(--peacock)]" />
  );
}

export function TransactionTable({ rows, onOpen, selection }: { rows: Transaction[]; onOpen: (t: Transaction) => void; selection?: Selection }) {
  const allSelected = !!selection && rows.length > 0 && rows.every((r) => selection.selected.has(r.id));
  return (
    <div className="hidden overflow-hidden rounded-3xl border border-line bg-surface md:block">
      <table className="w-full text-sm">
        <thead className="border-b border-line bg-surface-2/60 text-left text-[13px] text-ink-2">
          <tr>
            {selection && (
              <th className="w-10 py-2.5 pl-4">
                <Check checked={allSelected} onChange={() => selection.toggleAll(rows.map((r) => r.id))} label="Select all on this page" />
              </th>
            )}
            <th className="px-4 py-2.5 font-semibold">Date</th>
            <th className="px-4 py-2.5 font-semibold">Merchant</th>
            <th className="px-4 py-2.5 font-semibold">Category</th>
            <th className="px-4 py-2.5 font-semibold">Account</th>
            <th className="px-4 py-2.5 text-right font-semibold">Amount</th>
          </tr>
        </thead>
        <tbody className="divide-y divide-line">
          {rows.map((t) => (
            <tr key={t.id} onClick={() => onOpen(t)} className={cn("cursor-pointer hover:bg-surface-2/70", (t.kind === "EXCLUDED" || t.duplicateStatus === "SUSPECTED") && "opacity-60",
              selection?.selected.has(t.id) && "bg-peacock-soft/60 opacity-100")}>
              {selection && (
                <td className="py-3 pl-4"><Check checked={selection.selected.has(t.id)} onChange={() => selection.toggle(t.id)} label={`Select ${merchantName(t)}`} /></td>
              )}
              <td className="whitespace-nowrap px-4 py-3 text-ink-2">
                {formatDate(t.txnAt, { day: "numeric", month: "short" })}
                {t.timeKnown && <span className="ml-1.5 text-xs text-ink-3">{formatTime(t.txnAt)}</span>}
              </td>
              <td className="max-w-[260px] px-4 py-3">
                <button className="flex w-full items-center gap-2.5 text-left" onClick={(e) => { e.stopPropagation(); onOpen(t); }}>
                  <Monogram name={merchantName(t)} className="size-8 rounded-xl text-[11px]" />
                  <span className="min-w-0">
                    <span className="block truncate font-semibold">{merchantName(t)}</span>
                    <span className="block truncate text-xs text-ink-3">
                      {METHOD_LABEL[t.paymentMethod]}{t.paymentProvider ? ` via ${t.paymentProvider}` : ""}{t.notes ? `, ${t.notes}` : ""}
                    </span>
                  </span>
                </button>
              </td>
              <td className="px-4 py-3"><CategoryCell t={t} /></td>
              <td className="px-4 py-3 text-ink-2">{t.account?.name}</td>
              <td className={cn("num whitespace-nowrap px-4 py-3 text-right font-semibold", t.direction === "CREDIT" && "text-in")}>{signed(t.amount, t.direction)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

export function TransactionCards({ rows, onOpen, selection, selecting }: { rows: Transaction[]; onOpen: (t: Transaction) => void; selection?: Selection; selecting?: boolean }) {
  let lastDay = "";
  return (
    <div className="md:hidden">
      {rows.map((t) => {
        const day = formatDate(t.txnAt, { weekday: "short", day: "numeric", month: "short" });
        const header = day !== lastDay;
        lastDay = day;
        return (
          <div key={t.id}>
            {header && <h3 className="mb-1.5 mt-4 px-1 text-[13px] font-semibold text-ink-2 first:mt-0">{day}</h3>}
            <button onClick={() => (selecting && selection ? selection.toggle(t.id) : onOpen(t))}
              className={cn("mb-1.5 flex w-full items-center gap-3 rounded-2xl border border-line bg-surface p-3 text-left", t.kind === "EXCLUDED" && "opacity-60",
                selecting && selection?.selected.has(t.id) && "border-peacock bg-peacock-soft/60 opacity-100")}>
              {selecting && selection && <Check checked={selection.selected.has(t.id)} onChange={() => selection.toggle(t.id)} label={`Select ${merchantName(t)}`} />}
              <Monogram name={merchantName(t)} className="size-10" />
              <span className="min-w-0 flex-1">
                <span className="block truncate text-sm font-semibold">{merchantName(t)}</span>
                <span className="mt-0.5 block"><CategoryCell t={t} /></span>
              </span>
              <span className={cn("num text-[15px] font-semibold", t.direction === "CREDIT" && "text-in")}>{signed(t.amount, t.direction)}</span>
            </button>
          </div>
        );
      })}
    </div>
  );
}
