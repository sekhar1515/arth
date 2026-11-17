import { cn } from "@/lib/utils";

/**
 * Horizontal usage bar. Colour follows meaning: peacock while comfortable, marigold near the limit,
 * sindoor once over. Optional tick marks show alert thresholds; an optional ghost segment shows the forecast.
 */
export function Meter({ pct, ticks = [], forecastPct, className, label }: {
  pct: number; ticks?: number[]; forecastPct?: number | null; className?: string; label: string;
}) {
  const shown = Math.max(0, Math.min(100, pct));
  const tone = pct >= 100 ? "bg-out" : pct >= 90 ? "bg-marigold" : "bg-peacock-2";
  return (
    <div className={cn("relative h-2.5 w-full overflow-visible rounded-full bg-surface-2", className)} role="meter" aria-valuemin={0}
      aria-valuemax={100} aria-valuenow={Math.round(pct)} aria-label={label}>
      {forecastPct != null && forecastPct > shown && (
        <div className="absolute inset-y-0 left-0 rounded-full bg-marigold/25" style={{ width: `${Math.min(100, forecastPct)}%` }} />
      )}
      <div className={cn("absolute inset-y-0 left-0 rounded-full transition-[width]", tone)} style={{ width: `${shown}%` }} />
      {ticks.filter((t) => t < 100).map((t) => (
        <span key={t} className="absolute -top-0.5 h-3.5 w-px bg-ink-3/50" style={{ left: `${t}%` }} aria-hidden />
      ))}
    </div>
  );
}

export function Stat({ label, value, tone, hint }: { label: string; value: string; tone?: "out" | "in" | "attention"; hint?: string }) {
  return (
    <div>
      <p className="text-[13px] text-ink-2">{label}</p>
      <p className={cn("num text-2xl font-semibold", tone === "out" && "text-out", tone === "in" && "text-in", tone === "attention" && "text-marigold-ink")}>{value}</p>
      {hint && <p className="text-xs text-ink-3">{hint}</p>}
    </div>
  );
}
