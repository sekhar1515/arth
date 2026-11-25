"use client";

import { CheckCheck, Inbox, SlidersHorizontal } from "lucide-react";
import Link from "next/link";
import { useMemo, useState } from "react";
import { toast } from "sonner";
import { PageHeader } from "@/components/layout/app-shell";
import { Monogram, TransactionApprovalCard } from "@/components/finance/approval-card";
import { Button } from "@/components/ui/button";
import { Input, Select } from "@/components/ui/input";
import { EmptyState, ErrorState, Pill, Skeleton } from "@/components/ui/states";
import { useAccounts, useBulkConfirm, useCategories, useReview } from "@/hooks/queries";
import { formatDate, merchantName, signed, UPI_APPS } from "@/lib/format";
import { cn } from "@/lib/utils";

export default function ReviewPage() {
  const [filters, setFilters] = useState<Record<string, string>>({});
  const [showFilters, setShowFilters] = useState(false);
  const [focusId, setFocusId] = useState<string | null>(null);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const review = useReview(filters);
  const categories = useCategories();
  const accounts = useAccounts();
  const bulk = useBulkConfirm();

  const items = useMemo(() => review.data?.content ?? [], [review.data]);
  const current = items.find((t) => t.id === focusId) ?? items[0];
  const rest = items.filter((t) => t.id !== current?.id);
  const withSuggestion = items.filter((t) => t.suggestedCategory && t.duplicateStatus === "NONE");

  function toggle(id: string) {
    const next = new Set(selected);
    if (next.has(id)) next.delete(id);
    else next.add(id);
    setSelected(next);
  }

  function bulkConfirm(ids: string[]) {
    bulk.mutate(ids, {
      onSuccess: (r) => {
        toast.success(`Confirmed ${r.confirmed}${r.skipped ? `, skipped ${r.skipped} without a suggestion` : ""}`);
        setSelected(new Set());
      },
      onError: () => toast.error("Couldn't confirm. Try again."),
    });
  }

  const set = (k: string, v: string) => setFilters((f) => ({ ...f, [k]: v }));
  const total = review.data?.totalElements ?? 0;

  return (
    <>
      <PageHeader
        title="Needs Review"
        subtitle={total > 0
          ? `${total} transaction${total === 1 ? "" : "s"} waiting. They already count in your totals; categorising them puts them in the right place in your spending breakdown.`
          : "New transactions land here so you decide where they belong."}
        actions={
          <>
            <Button variant="outline" size="sm" onClick={() => setShowFilters((v) => !v)} aria-expanded={showFilters}>
              <SlidersHorizontal /> Filters
            </Button>
            {withSuggestion.length > 1 && (
              <Button variant="secondary" size="sm" disabled={bulk.isPending} onClick={() => bulkConfirm(withSuggestion.map((t) => t.id))}>
                <CheckCheck /> Accept all {withSuggestion.length} suggestions
              </Button>
            )}
          </>
        }
      />

      {showFilters && (
        <div className="mb-6 grid gap-3 rounded-3xl border border-line bg-surface p-4 sm:grid-cols-3 lg:grid-cols-6">
          <Input type="date" aria-label="From date" value={filters.from ?? ""} onChange={(e) => set("from", e.target.value)} />
          <Input type="date" aria-label="To date" value={filters.to ?? ""} onChange={(e) => set("to", e.target.value)} />
          <Input placeholder="Merchant or text" aria-label="Merchant" value={filters.q ?? ""} onChange={(e) => set("q", e.target.value)} />
          <Select aria-label="Payment app" value={filters.provider ?? ""} onChange={(e) => set("provider", e.target.value)}>
            <option value="">Any payment app</option>
            {UPI_APPS.map((p) => <option key={p}>{p}</option>)}
          </Select>
          <Select aria-label="Type" value={filters.direction ?? ""} onChange={(e) => set("direction", e.target.value)}>
            <option value="">Money in and out</option>
            <option value="DEBIT">Money out</option>
            <option value="CREDIT">Money in</option>
          </Select>
          <div className="flex gap-2">
            <Input inputMode="decimal" placeholder="Min ₹" aria-label="Minimum amount" value={filters.minAmount ?? ""} onChange={(e) => set("minAmount", e.target.value)} />
            <Input inputMode="decimal" placeholder="Max ₹" aria-label="Maximum amount" value={filters.maxAmount ?? ""} onChange={(e) => set("maxAmount", e.target.value)} />
          </div>
        </div>
      )}

      {review.isError ? (
        <ErrorState error={review.error} retry={() => review.refetch()} />
      ) : review.isPending || categories.isPending || accounts.isPending ? (
        <Skeleton className="h-80" />
      ) : !current ? (
        <div className="rounded-3xl border border-line bg-surface">
          <EmptyState icon={<Inbox />} title="You're all caught up"
            body="Every transaction has a category. New ones from your bank alerts and statements will appear here."
            action={<Button asChild variant="outline"><Link href="/transactions">See all transactions</Link></Button>} />
        </div>
      ) : (
        <div className="grid grid-cols-[minmax(0,1fr)] gap-6 xl:grid-cols-[minmax(0,1fr)_340px]">
          <div className="min-w-0">
            <TransactionApprovalCard key={current.id} txn={current} categories={categories.data ?? []} accounts={accounts.data ?? []}
              autoFocus onDone={() => setFocusId(rest[0]?.id ?? null)} />
            <p className="mt-3 hidden text-xs text-ink-3 sm:block">
              Keyboard: <kbd className="rounded bg-surface-2 px-1">1</kbd>–<kbd className="rounded bg-surface-2 px-1">9</kbd> pick a category,{" "}
              <kbd className="rounded bg-surface-2 px-1">Enter</kbd> confirm, <kbd className="rounded bg-surface-2 px-1">S</kbd> split,{" "}
              <kbd className="rounded bg-surface-2 px-1">T</kbd> transfer, <kbd className="rounded bg-surface-2 px-1">X</kbd> exclude.
            </p>
          </div>

          {rest.length > 0 && (
            <aside aria-label="Up next">
              <div className="mb-2 flex items-center justify-between">
                <h2 className="font-display text-sm font-semibold text-ink-2">Up next</h2>
                {selected.size > 0 && (
                  <Button size="sm" variant="secondary" disabled={bulk.isPending} onClick={() => bulkConfirm([...selected])}>
                    Accept {selected.size} selected
                  </Button>
                )}
              </div>
              <ul className="divide-y divide-line overflow-hidden rounded-3xl border border-line bg-surface">
                {rest.map((t) => (
                  <li key={t.id} className="flex items-center gap-3 px-3 py-2.5 hover:bg-surface-2">
                    <input type="checkbox" aria-label={`Select ${merchantName(t)}`} checked={selected.has(t.id)} onChange={() => toggle(t.id)}
                      className="size-4 accent-[var(--peacock)]" disabled={!t.suggestedCategory || t.duplicateStatus === "SUSPECTED"} />
                    <button className="flex min-w-0 flex-1 items-center gap-3 text-left" onClick={() => setFocusId(t.id)}>
                      <Monogram name={merchantName(t)} className="size-8 rounded-xl text-[11px]" />
                      <span className="min-w-0 flex-1">
                        <span className="block truncate text-sm font-semibold">{merchantName(t)}</span>
                        <span className="block truncate text-xs text-ink-3">
                          {t.duplicateStatus === "SUSPECTED" ? <Pill tone="attention">Possible duplicate</Pill> : t.suggestedCategory?.path ?? "No suggestion"}
                        </span>
                      </span>
                      <span className="text-right">
                        <span className={cn("num block text-sm font-semibold", t.direction === "CREDIT" && "text-in")}>{signed(t.amount, t.direction)}</span>
                        <span className="block text-xs text-ink-3">{formatDate(t.txnAt, { day: "numeric", month: "short" })}</span>
                      </span>
                    </button>
                  </li>
                ))}
              </ul>
            </aside>
          )}
        </div>
      )}
    </>
  );
}
