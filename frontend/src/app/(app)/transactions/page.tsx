"use client";

import { CheckSquare, Download, ListOrdered, Plus, Search, Trash2, X } from "lucide-react";
import { toast } from "sonner";
import { useSearchParams } from "next/navigation";
import { Suspense, useEffect, useMemo, useState } from "react";
import { PageHeader } from "@/components/layout/app-shell";
import { ManualTransactionDialog } from "@/components/finance/dialogs";
import { TransactionCards, TransactionTable, type Selection } from "@/components/finance/transaction-list";
import { useConfirm } from "@/components/ui/confirm";
import { TransactionDetailDialog } from "@/components/finance/transaction-detail";
import { Button } from "@/components/ui/button";
import { Input, Select } from "@/components/ui/input";
import { EmptyState, ErrorState, Skeleton } from "@/components/ui/states";
import { useAccounts, useCategories, useInvalidateMoney, useTransactions } from "@/hooks/queries";
import { api, ApiError, qs } from "@/lib/api";
import { inr, UPI_APPS } from "@/lib/format";
import type { DeleteResult, Transaction } from "@/types/api";

function useDebounced<T>(value: T, ms = 300) {
  const [v, setV] = useState(value);
  useEffect(() => {
    const id = setTimeout(() => setV(value), ms);
    return () => clearTimeout(id);
  }, [value, ms]);
  return v;
}

function TransactionsInner() {
  const params = useSearchParams();
  const [filters, setFilters] = useState<Record<string, string>>(() => {
    const init: Record<string, string> = {};
    for (const k of ["categoryId", "merchantId", "accountId", "from", "to", "direction"]) {
      const v = params.get(k);
      if (v) init[k] = v;
    }
    return init;
  });
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [selecting, setSelecting] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const { confirm, dialog } = useConfirm();
  const invalidate = useInvalidateMoney();
  const [q, setQ] = useState("");
  const [page, setPage] = useState(0);
  const [open, setOpen] = useState<Transaction | null>(null);
  const [adding, setAdding] = useState(false);
  const debouncedQ = useDebounced(q);
  const effective = useMemo(() => ({ ...filters, q: debouncedQ, page, size: 50 }), [filters, debouncedQ, page]);
  const txns = useTransactions(effective);
  const accounts = useAccounts();
  const categories = useCategories();
  const flat = (categories.data ?? []).flatMap((c) => [c, ...c.children]);

  const set = (k: string, v: string) => { setFilters((f) => ({ ...f, [k]: v })); setPage(0); };
  const selection: Selection = {
    selected,
    toggle: (id) => setSelected((s) => { const n = new Set(s); if (n.has(id)) n.delete(id); else n.add(id); return n; }),
    toggleAll: (ids) => setSelected((s) => (ids.every((i) => s.has(i)) ? new Set([...s].filter((i) => !ids.includes(i))) : new Set([...s, ...ids]))),
  };

  async function deleteSelected() {
    const ids = [...selected];
    const ok = await confirm({
      title: `Delete ${ids.length} transaction${ids.length === 1 ? "" : "s"}?`,
      body: <>They&apos;re removed from every total. Imported ones won&apos;t come back if you import the same statement again (you can undo that in Settings, Data). If one side of a transfer is deleted, the other side goes back to Needs Review.</>,
      confirmLabel: "Delete", danger: true,
    });
    if (!ok) return;
    setDeleting(true);
    try {
      const r = await api.post<DeleteResult>("/transactions/bulk-delete", { ids });
      toast.success(`Deleted ${r.deleted} transaction${r.deleted === 1 ? "" : "s"}`);
      setSelected(new Set());
      setSelecting(false);
      invalidate();
    } catch (e) {
      toast.error(e instanceof ApiError ? e.message : "Couldn't delete");
    } finally {
      setDeleting(false);
    }
  }
  const data = txns.data;
  const pageOut = data?.content.filter((t) => t.direction === "DEBIT" && t.kind === "EXPENSE" && t.duplicateStatus === "NONE").reduce((a, t) => a + t.amount, 0) ?? 0;

  return (
    <>
      <PageHeader
        title="Transactions"
        subtitle={data ? `${data.totalElements.toLocaleString("en-IN")} transactions match` : undefined}
        actions={
          <>
            <Button variant="outline" size="sm" asChild>
              <a href={`/api/transactions/export.csv${qs({ from: filters.from, to: filters.to, accountId: filters.accountId, categoryId: filters.categoryId })}`}>
                <Download /> Export CSV
              </a>
            </Button>
            <Button size="sm" onClick={() => setAdding(true)} disabled={!accounts.data}><Plus /> Add transaction</Button>
          </>
        }
      />

      <div className="mb-5 space-y-3">
        <div className="relative">
          <Search className="pointer-events-none absolute left-3.5 top-1/2 size-4 -translate-y-1/2 text-ink-3" />
          <Input className="h-11 rounded-full pl-10" placeholder="Search merchant, description, note or reference" value={q}
            onChange={(e) => { setQ(e.target.value); setPage(0); }} aria-label="Search transactions" />
        </div>
        <div className="grid grid-cols-2 gap-2 sm:grid-cols-4 lg:grid-cols-8">
          <Input type="date" aria-label="From date" value={filters.from ?? ""} onChange={(e) => set("from", e.target.value)} />
          <Input type="date" aria-label="To date" value={filters.to ?? ""} onChange={(e) => set("to", e.target.value)} />
          <Select aria-label="Account" value={filters.accountId ?? ""} onChange={(e) => set("accountId", e.target.value)}>
            <option value="">All accounts</option>
            {accounts.data?.map((a) => <option key={a.id} value={a.id}>{a.name}</option>)}
          </Select>
          <Select aria-label="Category" value={filters.categoryId ?? ""} onChange={(e) => set("categoryId", e.target.value)}>
            <option value="">All categories</option>
            {flat.map((c) => <option key={c.id} value={c.id}>{c.parentId ? `  ${c.name}` : c.name}</option>)}
          </Select>
          <Select aria-label="Type" value={filters.direction ?? ""} onChange={(e) => set("direction", e.target.value)}>
            <option value="">In and out</option>
            <option value="DEBIT">Money out</option>
            <option value="CREDIT">Money in</option>
          </Select>
          <Select aria-label="Payment method" value={filters.paymentMethod ?? ""} onChange={(e) => set("paymentMethod", e.target.value)}>
            <option value="">Any method</option>
            {["UPI", "CARD", "IMPS", "NEFT", "ATM", "CASH", "NACH"].map((m) => <option key={m} value={m}>{m === "NACH" ? "Auto-debit" : m}</option>)}
          </Select>
          <Select aria-label="Payment app" value={filters.provider ?? ""} onChange={(e) => set("provider", e.target.value)}>
            <option value="">Any app</option>
            {UPI_APPS.map((p) => <option key={p}>{p}</option>)}
          </Select>
          <Select aria-label="Categorisation" value={filters.categorisationStatus ?? ""} onChange={(e) => set("categorisationStatus", e.target.value)}>
            <option value="">Any status</option>
            <option value="PENDING">Needs review</option>
            <option value="CONFIRMED">Confirmed by you</option>
            <option value="AUTO">Automatic</option>
          </Select>
        </div>
      </div>

      {txns.isError ? (
        <ErrorState error={txns.error} retry={() => txns.refetch()} />
      ) : !data ? (
        <Skeleton className="h-96" />
      ) : data.content.length === 0 ? (
        <div className="rounded-3xl border border-line bg-surface">
          <EmptyState icon={<ListOrdered />} title={Object.values(filters).some(Boolean) || q ? "Nothing matches these filters" : "No transactions yet"}
            body={Object.values(filters).some(Boolean) || q ? "Try a wider date range or clear a filter." : "Import a bank statement or connect SMS alerts from Integrations."} />
        </div>
      ) : (
        <>
          <div className="mb-3 flex min-h-9 items-center gap-2">
            <Button variant="outline" size="sm" className="md:hidden" onClick={() => { setSelecting(!selecting); if (selecting) setSelected(new Set()); }}>
              {selecting ? <><X /> Done</> : <><CheckSquare /> Select</>}
            </Button>
            {selected.size > 0 && (
              <>
                <span className="text-sm text-ink-2"><span className="num font-semibold text-ink">{selected.size}</span> selected</span>
                <Button variant="danger" size="sm" onClick={deleteSelected} disabled={deleting}><Trash2 /> Delete</Button>
                <Button variant="ghost" size="sm" onClick={() => setSelected(new Set())}>Clear</Button>
              </>
            )}
            {filters.merchantId && (
              <Button variant="ghost" size="sm" onClick={() => set("merchantId", "")}><X /> Merchant filter</Button>
            )}
          </div>
          <TransactionTable rows={data.content} onOpen={setOpen} selection={selection} />
          <TransactionCards rows={data.content} onOpen={setOpen} selection={selection} selecting={selecting} />
          <div className="mt-4 flex items-center justify-between text-sm text-ink-2">
            <span>Spending on this page: <span className="num font-semibold text-ink">{inr(pageOut)}</span></span>
            <div className="flex items-center gap-2">
              <Button variant="outline" size="sm" disabled={page === 0} onClick={() => setPage(page - 1)}>Previous</Button>
              <span className="num">{page + 1} / {Math.max(data.totalPages, 1)}</span>
              <Button variant="outline" size="sm" disabled={page + 1 >= data.totalPages} onClick={() => setPage(page + 1)}>Next</Button>
            </div>
          </div>
        </>
      )}

      {open && <TransactionDetailDialog key={open.id} txn={open} categories={categories.data ?? []} accounts={accounts.data ?? []} onClose={() => setOpen(null)} />}
      {dialog}
      {adding && accounts.data && <ManualTransactionDialog accounts={accounts.data} categories={categories.data ?? []} open onOpenChange={setAdding} />}
    </>
  );
}

export default function TransactionsPage() {
  return (
    <Suspense fallback={<Skeleton className="h-96" />}>
      <TransactionsInner />
    </Suspense>
  );
}
