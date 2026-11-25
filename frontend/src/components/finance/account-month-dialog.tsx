"use client";

import { ChevronLeft, ChevronRight, ListOrdered } from "lucide-react";
import Link from "next/link";
import { useState } from "react";
import { TransactionDetailDialog } from "@/components/finance/transaction-detail";
import { TransactionCards, TransactionTable } from "@/components/finance/transaction-list";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent } from "@/components/ui/dialog";
import { EmptyState, ErrorState, Skeleton } from "@/components/ui/states";
import { useAccounts, useCategories, useTransactions } from "@/hooks/queries";
import { useToday } from "@/hooks/use-today";
import { formatDate, inr } from "@/lib/format";
import type { Transaction } from "@/types/api";

const PAGE = 200;
const pad = (n: number) => String(n).padStart(2, "0");

/** First and last day of a "YYYY-MM" month, the last capped at today. */
function monthRange(month: string, today: string) {
  const [y, m] = month.split("-").map(Number);
  const last = new Date(Date.UTC(y, m, 0)).getUTCDate();
  const end = `${month}-${pad(last)}`;
  return { from: `${month}-01`, to: end > today ? today : end };
}
function shiftMonth(month: string, by: number) {
  const [y, m] = month.split("-").map(Number);
  const d = new Date(Date.UTC(y, m - 1 + by, 1));
  return `${d.getUTCFullYear()}-${pad(d.getUTCMonth() + 1)}`;
}

/** An account's or card's transactions for one month, with money in and out, and arrows to move between months. */
export function AccountMonthDialog({ accountId, name, isCard, onClose }: { accountId: string; name: string; isCard?: boolean; onClose: () => void }) {
  const today = useToday();
  const [month, setMonth] = useState(today.slice(0, 7));
  const [open, setOpen] = useState<Transaction | null>(null);
  const { from, to } = monthRange(month, today);
  const txns = useTransactions({ accountId, from, to, page: 0, size: PAGE });
  const accounts = useAccounts();
  const categories = useCategories();
  const rows = txns.data?.content ?? [];
  const moneyOut = rows.filter((t) => t.direction === "DEBIT").reduce((s, t) => s + t.amount, 0);
  const moneyIn = rows.filter((t) => t.direction === "CREDIT").reduce((s, t) => s + t.amount, 0);
  const isCurrent = month === today.slice(0, 7);
  const monthName = formatDate(`${month}-15T12:00:00+05:30`, { month: "long", year: "numeric" });

  return (
    <>
      <Dialog open onOpenChange={(o) => !o && onClose()}>
        <DialogContent title={name} description={isCard ? "Card spends and payments for the month." : "Money in and out of this account for the month."} wide>
          <div className="flex items-center justify-between gap-2">
            <Button variant="ghost" size="icon" aria-label="Previous month" onClick={() => setMonth((m) => shiftMonth(m, -1))}><ChevronLeft /></Button>
            <p className="font-display text-base font-semibold">{monthName}{isCurrent && <span className="ml-1 text-xs font-normal text-ink-3">so far</span>}</p>
            <Button variant="ghost" size="icon" aria-label="Next month" disabled={isCurrent} onClick={() => setMonth((m) => shiftMonth(m, 1))}><ChevronRight /></Button>
          </div>

          <div className="mt-3 grid grid-cols-2 gap-2 text-sm">
            <div className="rounded-2xl bg-surface-2 px-3 py-2">
              <p className="text-xs text-ink-2">{isCard ? "Spent" : "Money out"}</p>
              <p className="num font-semibold text-out">{inr(moneyOut, { paise: moneyOut % 1 !== 0 })}</p>
            </div>
            <div className="rounded-2xl bg-surface-2 px-3 py-2">
              <p className="text-xs text-ink-2">{isCard ? "Paid / refunds" : "Money in"}</p>
              <p className="num font-semibold text-in">{inr(moneyIn, { paise: moneyIn % 1 !== 0 })}</p>
            </div>
          </div>

          <div className="mt-4 max-h-[55vh] overflow-y-auto">
            {txns.isError ? <ErrorState error={txns.error} retry={() => txns.refetch()} />
              : !txns.data ? <Skeleton className="h-48" />
                : rows.length === 0 ? <EmptyState icon={<ListOrdered />} title={`No transactions in ${monthName}`} body="Use the arrows to look at another month." />
                  : (
                    <>
                      <TransactionTable rows={rows} onOpen={setOpen} />
                      <TransactionCards rows={rows} onOpen={setOpen} />
                    </>
                  )}
          </div>

          <div className="mt-4 flex flex-wrap items-center justify-between gap-2 text-xs text-ink-3">
            <span>{txns.data ? `${txns.data.totalElements} transaction${txns.data.totalElements === 1 ? "" : "s"}${txns.data.totalElements > PAGE ? `, first ${PAGE} shown` : ""}` : ""}</span>
            <Link href={`/transactions?accountId=${accountId}&from=${from}&to=${to}`} className="font-semibold text-peacock hover:underline">Open in Transactions →</Link>
          </div>
        </DialogContent>
      </Dialog>
      {open && <TransactionDetailDialog key={open.id} txn={open} categories={categories.data ?? []} accounts={accounts.data ?? []} onClose={() => setOpen(null)} />}
    </>
  );
}
