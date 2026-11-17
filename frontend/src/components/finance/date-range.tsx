"use client";

import { isoDay } from "@/lib/format";
import { cn } from "@/lib/utils";

export type Range = { from: string; to: string; preset: Preset };
export type Preset = "this-month" | "last-month" | "3-months" | "year" | "custom";

function istParts(d = new Date()) {
  const [y, m, day] = isoDay(d).split("-").map(Number);
  return { y, m, day };
}

const pad = (n: number) => String(n).padStart(2, "0");
const lastDay = (y: number, m: number) => new Date(Date.UTC(y, m, 0)).getUTCDate();

export function presetRange(p: Preset): Range {
  const { y, m } = istParts();
  const today = isoDay(new Date());
  switch (p) {
    case "last-month": {
      const ly = m === 1 ? y - 1 : y;
      const lm = m === 1 ? 12 : m - 1;
      return { from: `${ly}-${pad(lm)}-01`, to: `${ly}-${pad(lm)}-${pad(lastDay(ly, lm))}`, preset: p };
    }
    case "3-months": {
      const d = new Date(Date.UTC(y, m - 3, 1));
      return { from: `${d.getUTCFullYear()}-${pad(d.getUTCMonth() + 1)}-01`, to: today, preset: p };
    }
    case "year": {
      const fyStart = m >= 4 ? y : y - 1; // Indian financial year starts 1 April
      return { from: `${fyStart}-04-01`, to: today, preset: p };
    }
    default:
      return { from: `${y}-${pad(m)}-01`, to: today, preset: "this-month" };
  }
}

const PRESETS: { value: Preset; label: string }[] = [
  { value: "this-month", label: "This month" },
  { value: "last-month", label: "Last month" },
  { value: "3-months", label: "3 months" },
  { value: "year", label: "This FY" },
];

export function DateRangePicker({ value, onChange }: { value: Range; onChange: (r: Range) => void }) {
  return (
    <div className="flex flex-wrap items-center gap-2">
      <div className="flex rounded-full border border-line bg-surface p-0.5" role="radiogroup" aria-label="Period">
        {PRESETS.map((p) => (
          <button key={p.value} role="radio" aria-checked={value.preset === p.value} onClick={() => onChange(presetRange(p.value))}
            className={cn("rounded-full px-3 py-1.5 text-[13px] font-semibold", value.preset === p.value ? "bg-peacock text-white dark:text-[#06201f]" : "text-ink-2 hover:text-ink")}>
            {p.label}
          </button>
        ))}
      </div>
      <div className="flex items-center gap-1 text-[13px]">
        <input type="date" aria-label="From" value={value.from} max={value.to}
          onChange={(e) => e.target.value && onChange({ ...value, from: e.target.value, preset: "custom" })}
          className="h-9 rounded-full border border-line bg-surface px-3 text-ink" />
        <span className="text-ink-3">to</span>
        <input type="date" aria-label="To" value={value.to} min={value.from}
          onChange={(e) => e.target.value && onChange({ ...value, to: e.target.value, preset: "custom" })}
          className="h-9 rounded-full border border-line bg-surface px-3 text-ink" />
      </div>
    </div>
  );
}
