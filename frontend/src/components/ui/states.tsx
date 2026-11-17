import { AlertTriangle } from "lucide-react";
import type * as React from "react";
import { cn } from "@/lib/utils";
import { Button } from "./button";

export function Skeleton({ className }: { className?: string }) {
  return <div className={cn("animate-pulse rounded-xl bg-surface-2", className)} />;
}

export function EmptyState({ icon, title, body, action }: { icon?: React.ReactNode; title: string; body?: string; action?: React.ReactNode }) {
  return (
    <div className="flex flex-col items-center px-6 py-14 text-center">
      {icon && <div className="mb-4 rounded-2xl bg-peacock-soft p-3 text-peacock [&_svg]:size-6">{icon}</div>}
      <h3 className="font-display text-lg font-semibold">{title}</h3>
      {body && <p className="mt-1 max-w-sm text-sm text-ink-2">{body}</p>}
      {action && <div className="mt-5">{action}</div>}
    </div>
  );
}

export function ErrorState({ error, retry }: { error: unknown; retry?: () => void }) {
  const message = error instanceof Error ? error.message : "Something went wrong.";
  return (
    <div role="alert" className="flex items-start gap-3 rounded-2xl border border-out/30 bg-out-soft p-4 text-sm">
      <AlertTriangle className="mt-0.5 size-4 shrink-0 text-out" />
      <div className="flex-1">
        <p className="font-semibold text-ink">Couldn&apos;t load this</p>
        <p className="mt-0.5 text-ink-2">{message}</p>
      </div>
      {retry && (
        <Button size="sm" variant="outline" onClick={retry}>
          Try again
        </Button>
      )}
    </div>
  );
}

export function Panel({ className, children, title, action }: { className?: string; children: React.ReactNode; title?: string; action?: React.ReactNode }) {
  return (
    <section className={cn("rounded-3xl border border-line bg-surface p-5 sm:p-6", className)}>
      {(title || action) && (
        <div className="mb-4 flex items-center justify-between gap-3">
          {title && <h2 className="font-display text-base font-semibold">{title}</h2>}
          {action}
        </div>
      )}
      {children}
    </section>
  );
}

export function Pill({ className, children, tone = "neutral" }: { className?: string; children: React.ReactNode; tone?: "neutral" | "attention" | "in" | "out" | "brand" }) {
  const tones = {
    neutral: "bg-surface-2 text-ink-2",
    attention: "bg-marigold-soft text-marigold-ink",
    in: "bg-in-soft text-in",
    out: "bg-out-soft text-out",
    brand: "bg-peacock-soft text-peacock",
  };
  return <span className={cn("inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-xs font-semibold", tones[tone], className)}>{children}</span>;
}
