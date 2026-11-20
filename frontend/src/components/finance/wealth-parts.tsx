"use client";

import { History, MoreHorizontal, Pencil, Percent, RefreshCw, Repeat, Trash2, TrendingDown, TrendingUp } from "lucide-react";
import Link from "next/link";
import { SourceBadge } from "@/components/finance/networth-dialogs";
import { Button } from "@/components/ui/button";
import { Menu, MenuItem } from "@/components/ui/menu";
import { inr } from "@/lib/format";
import { cn } from "@/lib/utils";
import type { AllocationBucket, NetWorthGroup, NetWorthItem } from "@/types/api";

export const BUCKET_COLOR: Record<AllocationBucket, string> = {
  EQUITY: "#0F5E63", DEBT: "#6366F1", HYBRID: "#8B5CF6", GOLD: "#E8A317", REAL_ESTATE: "#B45309", CASH: "#5FB3B0", OTHER: "#94A3B8",
};

export function Gain({ value, pct, className }: { value: number | null; pct: number | null; className?: string }) {
  if (value === null) return null;
  const up = value >= 0;
  return (
    <span className={cn("num inline-flex items-center gap-1 text-xs font-semibold", up ? "text-in" : "text-out", className)}>
      {up ? <TrendingUp className="size-3" /> : <TrendingDown className="size-3" />}
      {up ? "+" : "−"}{inr(Math.abs(value))}{pct !== null && ` (${up ? "+" : ""}${pct}%)`}
    </span>
  );
}

/** "1D +₹1,240 (+0.5%)": the move on the latest NAV/close day. */
export function DayChange({ value, pct, on, className }: { value: number | null; pct?: number | null; on?: string | null; className?: string }) {
  if (value === null || value === undefined) return null;
  const up = value >= 0;
  return (
    <span className={cn("num inline-flex items-center gap-1 text-[11px] font-medium", up ? "text-in" : "text-out", className)}
      title={on ? `Change on ${new Date(on + "T12:00:00+05:30").toLocaleDateString("en-IN", { day: "numeric", month: "short" })} vs the previous NAV/close` : undefined}>
      <span className="rounded bg-surface-2 px-1 text-[10px] font-semibold text-ink-3">1D</span>
      {up ? "+" : "−"}{inr(Math.abs(value))}{pct != null && ` (${up ? "+" : ""}${pct}%)`}
    </span>
  );
}

export type ItemAction = "update" | "edit" | "remove" | "plan" | "xirr" | "lots";

export function XirrBadge({ value, estimated, className }: { value: number | null; estimated?: boolean; className?: string }) {
  if (value === null || value === undefined) return null;
  return (
    <span className={cn("num inline-flex items-center gap-1 text-[11px] font-semibold", value >= 0 ? "text-in" : "text-out", className)}
      title="XIRR: yearly return, counting when the money went in">
      <span className="rounded bg-surface-2 px-1 text-[10px] font-semibold text-ink-3">XIRR</span>{value}%{estimated ? <span className="font-normal text-ink-3"> est.</span> : null}
    </span>
  );
}

/**
 * A card of items (bank accounts, funds, loans…). Pass {@code onAction} for an actions menu; {@code manageHref} instead
 * shows the rows read-only with a link to where they are managed.
 */
export function GroupCard({ g, negative, onAction, fetchable, planLabel, manageHref }: {
  g: NetWorthGroup; negative?: boolean; onAction?: (item: NetWorthItem, action: ItemAction) => void;
  fetchable?: (id: string) => boolean; planLabel?: (id: string) => string | null; manageHref?: string;
}) {
  return (
    <section className="overflow-hidden rounded-3xl border border-line bg-surface">
      <header className="flex flex-wrap items-baseline justify-between gap-x-3 gap-y-1 border-b border-line px-5 py-3.5">
        <h3 className="font-semibold">{g.label}</h3>
        <div className="text-right">
          <span className={cn("num font-semibold", negative && "text-out")}>{inr(g.value)}</span>
          {g.gain !== null && <Gain value={g.gain} pct={g.gainPct} className="ml-2" />}
          {g.dayChange != null && <DayChange value={g.dayChange} className="ml-2" />}
          {g.xirr != null && <XirrBadge value={g.xirr} className="ml-2" />}
        </div>
      </header>
      <ul className="divide-y divide-line">
        {g.items.map((i) => {
          const plan = planLabel?.(i.id) ?? null;
          return (
            <li key={i.id} className="flex items-center gap-3 px-5 py-3">
              <div className="min-w-0 flex-1">
                <p className="truncate text-sm font-medium">{i.kind === "ACCOUNT" ? <Link href="/accounts" className="hover:underline">{i.name}</Link> : i.name}</p>
                <p className="mt-0.5 flex flex-wrap items-center gap-x-2 gap-y-1 text-xs text-ink-3">
                  {i.detail && <span>{i.detail}</span>}
                  {i.kind === "HOLDING" && <SourceBadge source={i.source} estimated={i.estimated} />}
                </p>
              </div>
              <div className="text-right">
                <p className="num text-sm font-semibold">{inr(i.value)}</p>
                {i.gain !== null && <Gain value={i.gain} pct={i.gainPct} />}
                {i.dayChange != null && <div><DayChange value={i.dayChange} pct={i.dayChangePct} on={i.dayChangeOn} /></div>}
                {i.kind === "HOLDING" && (i.xirr != null
                  ? <div><button type="button" onClick={() => onAction?.(i, "lots")} className="hover:underline" title="See purchases & returns"><XirrBadge value={i.xirr} estimated={i.xirrEstimated} /></button></div>
                  : onAction && i.invested != null && <div><button type="button" className="text-[11px] font-medium text-peacock hover:underline" onClick={() => onAction(i, "xirr")}>Show XIRR</button></div>)}
              </div>
              {onAction && i.kind !== "ACCOUNT" && (
                <Menu trigger={<Button variant="ghost" size="icon" aria-label={`Actions for ${i.name}`}><MoreHorizontal /></Button>}>
                  {i.kind === "HOLDING" && !i.source.startsWith("Estimated") && (
                    <MenuItem onSelect={() => onAction(i, "update")}><RefreshCw className="size-4 text-ink-3" /> {fetchable?.(i.id) ? "Get latest price" : "Update value"}</MenuItem>
                  )}
                  {plan && <MenuItem onSelect={() => onAction(i, "plan")}><Repeat className="size-4 text-ink-3" /> {plan}</MenuItem>}
                  {i.kind === "HOLDING" && (
                    <MenuItem onSelect={() => onAction(i, "lots")}><History className="size-4 text-ink-3" /> Purchases &amp; returns</MenuItem>
                  )}
                  {i.kind === "HOLDING" && i.invested != null && i.xirr == null && (
                    <MenuItem onSelect={() => onAction(i, "xirr")}><Percent className="size-4 text-ink-3" /> Show XIRR</MenuItem>
                  )}
                  <MenuItem onSelect={() => onAction(i, "edit")}><Pencil className="size-4 text-ink-3" /> Edit</MenuItem>
                  <MenuItem danger onSelect={() => onAction(i, "remove")}><Trash2 className="size-4" /> Delete</MenuItem>
                </Menu>
              )}
            </li>
          );
        })}
      </ul>
      {manageHref && (
        <Link href={manageHref} className="block border-t border-line px-5 py-2.5 text-sm font-medium text-peacock hover:bg-surface-2">Manage in Investments →</Link>
      )}
    </section>
  );
}
