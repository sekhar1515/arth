"use client";

import { ChevronRight } from "lucide-react";
import { useMemo, useState } from "react";
import type { Category, CategoryKind } from "@/types/api";
import { cn } from "@/lib/utils";
import { CategoryIcon } from "./category-icon";

/**
 * Two-level chip picker. Tap a parent to select it; parents with subcategories reveal them in a second row.
 * Number keys 1–9 pick the first nine visible parents when `hotkeys` is on.
 */
export function CategoryPicker({
  categories, kind, value, onChange, suggestedId, compact,
}: {
  categories: Category[];
  kind: CategoryKind | "ANY";
  value: string | null;
  onChange: (id: string) => void;
  suggestedId?: string | null;
  compact?: boolean;
}) {
  const parents = useMemo(() => categories.filter((c) => !c.archived && (kind === "ANY" || c.kind === kind)), [categories, kind]);
  const parentOf = (id: string | null) => parents.find((p) => p.id === id || p.children.some((ch) => ch.id === id)) ?? null;
  const [expanded, setExpanded] = useState<string | null>(() => parentOf(value ?? suggestedId ?? null)?.id ?? null);
  const open = parents.find((p) => p.id === expanded);

  return (
    <div>
      <div className={cn("flex flex-wrap gap-1.5", compact && "max-h-40 overflow-y-auto")} role="listbox" aria-label="Category">
        {parents.map((p, i) => {
          const selected = value === p.id || p.children.some((c) => c.id === value);
          return (
            <button
              key={p.id}
              type="button"
              role="option"
              aria-selected={selected}
              onClick={() => {
                onChange(p.id);
                setExpanded(p.children.length ? p.id : null);
              }}
              className={cn(
                "inline-flex items-center gap-1.5 rounded-full border py-1 pl-1 pr-3 text-[13px] font-medium transition-colors",
                selected ? "border-peacock bg-peacock text-white dark:text-[#06201f]" : "border-line bg-surface text-ink hover:border-peacock-2/60",
                !selected && suggestedId && (suggestedId === p.id || p.children.some((c) => c.id === suggestedId)) && "border-peacock-2 border-dashed",
              )}
            >
              <CategoryIcon icon={p.icon} color={selected ? "#ffffff" : p.color} size="sm" className={cn("size-6 rounded-full", selected && "!bg-white/20")} />
              {p.name}
              {p.children.length > 0 && <ChevronRight className={cn("size-3 opacity-60 transition-transform", expanded === p.id && "rotate-90")} />}
              {i < 9 && !compact && <kbd className="sr-only">{i + 1}</kbd>}
            </button>
          );
        })}
      </div>
      {open && open.children.filter((c) => !c.archived).length > 0 && (
        <div className="mt-2 flex flex-wrap gap-1.5 rounded-2xl bg-surface-2 p-2" aria-label={`${open.name} subcategories`}>
          {open.children.filter((c) => !c.archived).map((c) => (
            <button
              key={c.id}
              type="button"
              onClick={() => onChange(c.id)}
              aria-pressed={value === c.id}
              className={cn(
                "rounded-full px-3 py-1 text-[13px] font-medium",
                value === c.id ? "bg-peacock text-white dark:text-[#06201f]" : "bg-surface text-ink-2 hover:text-ink",
                value !== c.id && suggestedId === c.id && "ring-1 ring-dashed ring-peacock-2",
              )}
            >
              {c.name}
            </button>
          ))}
        </div>
      )}
    </div>
  );
}

export function findCategory(tree: Category[], id: string | null | undefined): Category | null {
  if (!id) return null;
  for (const p of tree) {
    if (p.id === id) return p;
    const c = p.children.find((ch) => ch.id === id);
    if (c) return c;
  }
  return null;
}
