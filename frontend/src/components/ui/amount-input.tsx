"use client";

import { Calculator, Delete } from "lucide-react";
import * as React from "react";
import { amountString, evaluate, isExpression } from "@/lib/calc";
import { inr } from "@/lib/format";
import { cn } from "@/lib/utils";

type Props = Omit<React.InputHTMLAttributes<HTMLInputElement>, "value" | "onChange" | "type"> & {
  value: string;
  /** The worked-out amount ("" while a sum is incomplete), ready to save. */
  onValueChange: (value: string) => void;
  allowNegative?: boolean;
};

const ALLOWED = /[^0-9.,+\-*/×÷xX−()\s]/g;
const KEYS = ["7", "8", "9", "÷", "4", "5", "6", "×", "1", "2", "3", "−", "0", ".", "⌫", "+", "C", "(", ")", "="] as const;

/**
 * An amount box that is also a calculator: type "450+120*2" and it saves 690 (shown as "= ₹690" while you type and
 * filled in when you leave the box or press Enter). The calculator button opens a keypad.
 */
export const AmountInput = React.forwardRef<HTMLInputElement, Props>(function AmountInput(
  { value, onValueChange, allowNegative, className, onBlur, onKeyDown, disabled, ...rest }, ref,
) {
  const [text, setText] = React.useState(value);
  const [pad, setPad] = React.useState(false);
  const emitted = React.useRef(value);
  const inner = React.useRef<HTMLInputElement | null>(null);
  const box = React.useRef<HTMLDivElement | null>(null);
  React.useImperativeHandle(ref, () => inner.current as HTMLInputElement);

  // the form changed the value itself (e.g. reset after saving): show that
  React.useEffect(() => {
    if (value !== emitted.current) {
      emitted.current = value;
      setText(value);
    }
  }, [value]);

  const sum = isExpression(text);
  const result = sum ? evaluate(text) : null;
  const negative = result !== null && result < 0 && !allowNegative;

  function emit(next: string) {
    let out: string;
    if (isExpression(next)) {
      const r = evaluate(next);
      out = r === null || (r < 0 && !allowNegative) ? "" : amountString(r);
    } else {
      out = next.replace(/,/g, "").replace(/[^0-9.-]/g, "");
      if (!allowNegative) out = out.replace(/-/g, "");
    }
    emitted.current = out;
    onValueChange(out);
  }

  function change(next: string) {
    const clean = next.replace(ALLOWED, "");
    setText(clean);
    emit(clean);
  }

  /** Replaces the sum with its answer. Returns true if there was one. */
  function settle(): boolean {
    if (!sum || result === null || negative) return false;
    const s = amountString(result);
    setText(s);
    emitted.current = s;
    onValueChange(s);
    return true;
  }

  function press(k: (typeof KEYS)[number]) {
    if (k === "C") return change("");
    if (k === "⌫") return change(text.slice(0, -1));
    if (k === "=") { settle(); setPad(false); inner.current?.focus(); return; }
    change(text + k);
  }

  React.useEffect(() => {
    if (!pad) return;
    const close = (e: MouseEvent) => { if (box.current && !box.current.contains(e.target as Node)) setPad(false); };
    const esc = (e: KeyboardEvent) => { if (e.key === "Escape") { e.stopPropagation(); setPad(false); } };
    document.addEventListener("mousedown", close);
    document.addEventListener("keydown", esc, true);
    return () => { document.removeEventListener("mousedown", close); document.removeEventListener("keydown", esc, true); };
  }, [pad]);

  return (
    <div ref={box} className="relative">
      <input
        ref={inner}
        type="text"
        inputMode="decimal"
        autoComplete="off"
        disabled={disabled}
        value={text}
        onChange={(e) => change(e.target.value)}
        onBlur={(e) => { settle(); onBlur?.(e); }}
        onKeyDown={(e) => {
          if (e.key === "Enter" && sum) { e.preventDefault(); settle(); }
          else if (e.key === "=") { e.preventDefault(); settle(); }
          onKeyDown?.(e);
        }}
        className={cn(
          "h-10 w-full rounded-xl border border-line bg-surface pl-3 pr-10 text-sm text-ink placeholder:text-ink-3 focus:border-peacock-2 focus:outline-none focus:ring-2 focus:ring-peacock-2/25 disabled:opacity-60",
          className,
        )}
        {...rest}
      />
      <button type="button" tabIndex={-1} disabled={disabled} aria-label="Calculator" aria-expanded={pad} title="Calculator"
        onMouseDown={(e) => e.preventDefault()} onClick={() => { setPad((p) => !p); inner.current?.focus(); }}
        className={cn("absolute right-1.5 top-1/2 grid size-7 -translate-y-1/2 place-items-center rounded-lg text-ink-3 hover:bg-surface-2 hover:text-ink [&_svg]:size-4",
          pad && "bg-surface-2 text-peacock")}>
        <Calculator />
      </button>
      {sum && (
        <p className={cn("num mt-1 text-xs", result === null || negative ? "text-ink-3" : "font-semibold text-peacock")} aria-live="polite">
          {result === null ? "Finish the sum…" : negative ? "Comes to less than zero" : `= ${inr(result, { paise: result % 1 !== 0 })}`}
        </p>
      )}
      {pad && (
        <div role="group" aria-label="Calculator keypad"
          className="absolute right-0 z-50 mt-1 grid w-56 grid-cols-4 gap-1.5 rounded-2xl border border-line bg-surface p-2 shadow-lg">
          {KEYS.map((k) => (
            <button key={k} type="button" onMouseDown={(e) => e.preventDefault()} onClick={() => press(k)}
              aria-label={k === "⌫" ? "Delete" : k === "C" ? "Clear" : k}
              className={cn("num h-10 rounded-xl text-sm font-semibold",
                k === "=" ? "bg-peacock text-white dark:text-[#06201f]"
                  : "÷×−+".includes(k) ? "bg-peacock-soft text-peacock"
                    : k === "C" ? "bg-out-soft text-out" : "bg-surface-2 text-ink hover:brightness-95")}>
              {k === "⌫" ? <Delete className="mx-auto size-4" /> : k}
            </button>
          ))}
        </div>
      )}
    </div>
  );
});
