"use client";

import { ArrowUp, Calculator, ShieldCheck } from "lucide-react";
import Link from "next/link";
import { useEffect, useRef, useState } from "react";
import { PageHeader } from "@/components/layout/app-shell";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Pill } from "@/components/ui/states";
import { api, ApiError } from "@/lib/api";
import { inr } from "@/lib/format";
import { cn } from "@/lib/utils";
import type { AssistantAnswer, FactKind } from "@/types/api";

const KIND_TONE: Record<FactKind, { label: string; tone: "in" | "attention" | "neutral" }> = {
  CONFIRMED: { label: "From your records", tone: "in" }, ESTIMATE: { label: "Estimate", tone: "attention" }, PENDING: { label: "Not yet categorised", tone: "neutral" },
};

type Turn = { q: string; a?: AssistantAnswer; error?: string };

function AnswerCard({ a, onAsk }: { a: AssistantAnswer; onAsk: (q: string) => void }) {
  const [showCalc, setShowCalc] = useState(false);
  return (
    <div className="rounded-3xl rounded-tl-lg border border-line bg-surface p-4 sm:p-5">
      <p className="text-[15px] leading-relaxed">{a.answer}</p>
      {a.facts.length > 0 && (
        <ul className="mt-3 divide-y divide-line rounded-2xl bg-surface-2/70 px-3">
          {a.facts.map((f, i) => (
            <li key={i} className="flex flex-wrap items-center gap-x-3 gap-y-1 py-2 text-sm">
              <span className="min-w-0 flex-1">{f.label}{f.text && f.amount !== null && <span className="block text-xs text-ink-3">{f.text}</span>}</span>
              {f.amount !== null ? <span className="num font-semibold">{inr(f.amount)}</span> : <span className="text-ink-2">{f.text}</span>}
              <Pill tone={KIND_TONE[f.kind].tone}>{KIND_TONE[f.kind].label}</Pill>
            </li>
          ))}
        </ul>
      )}
      <div className="mt-3 flex flex-wrap items-center gap-2 text-[13px]">
        {a.calculation.filter(Boolean).length > 0 && (
          <button className="inline-flex items-center gap-1 font-semibold text-peacock hover:underline" onClick={() => setShowCalc(!showCalc)} aria-expanded={showCalc}>
            <Calculator className="size-3.5" /> {showCalc ? "Hide" : "How I worked it out"}
          </button>
        )}
        {a.links.map((l) => <Link key={l.href} href={l.href} className="text-ink-2 hover:underline">{l.label}</Link>)}
      </div>
      {showCalc && (
        <ol className="mt-2 list-decimal space-y-1 rounded-2xl bg-surface-2 p-3 pl-7 text-[13px] text-ink-2">
          {a.calculation.filter(Boolean).map((c, i) => <li key={i}>{c}</li>)}
        </ol>
      )}
      {a.followUps.length > 0 && (
        <div className="mt-3 flex flex-wrap gap-1.5">
          {a.followUps.map((f) => (
            <button key={f} onClick={() => onAsk(f)} className="rounded-full border border-line px-3 py-1 text-[13px] text-ink-2 hover:bg-surface-2">{f}</button>
          ))}
        </div>
      )}
    </div>
  );
}

export default function AssistantPage() {
  const [turns, setTurns] = useState<Turn[]>([]);
  const [q, setQ] = useState("");
  const [busy, setBusy] = useState(false);
  const [examples, setExamples] = useState<string[]>([]);
  const end = useRef<HTMLDivElement>(null);

  useEffect(() => { api.get<string[]>("/assistant/examples").then(setExamples).catch(() => undefined); }, []);
  useEffect(() => { end.current?.scrollIntoView({ behavior: "smooth", block: "end" }); }, [turns]);

  async function ask(question: string) {
    const text = question.trim();
    if (!text || busy) return;
    setQ("");
    setBusy(true);
    setTurns((t) => [...t, { q: text }]);
    try {
      const a = await api.post<AssistantAnswer>("/assistant/ask", { question: text });
      setTurns((t) => t.map((x, i) => (i === t.length - 1 ? { ...x, a } : x)));
    } catch (e) {
      setTurns((t) => t.map((x, i) => (i === t.length - 1 ? { ...x, error: e instanceof ApiError ? e.message : "Couldn't answer that" } : x)));
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <PageHeader title="Assistant" subtitle="Ask about your money in plain words. Answers come only from your own records, with the working shown." />
      <div className="mx-auto flex max-w-3xl flex-col">
        <p className="mb-5 inline-flex items-center gap-2 self-start rounded-full bg-peacock-soft px-3 py-1 text-[13px] text-peacock">
          <ShieldCheck className="size-4" /> Runs on your Arth server with fixed rules. Nothing is sent to an outside AI.
        </p>
        {turns.length === 0 && (
          <div className="rounded-3xl border border-dashed border-line p-5">
            <p className="font-display text-lg font-semibold">Try asking</p>
            <div className="mt-3 flex flex-wrap gap-2">
              {examples.map((e) => (
                <button key={e} onClick={() => ask(e)} className="rounded-full border border-line bg-surface px-3 py-1.5 text-sm hover:border-peacock-2 hover:text-peacock">{e}</button>
              ))}
            </div>
          </div>
        )}
        <div className="space-y-5" aria-live="polite">
          {turns.map((t, i) => (
            <div key={i} className="space-y-2">
              <p className="ml-auto w-fit max-w-[85%] rounded-3xl rounded-br-lg bg-peacock px-4 py-2.5 text-[15px] text-white dark:text-[#06201f]">{t.q}</p>
              {t.a ? <AnswerCard a={t.a} onAsk={ask} /> : t.error ? (
                <p className="rounded-2xl bg-out-soft p-3 text-sm text-out">{t.error}</p>
              ) : <div className="h-16 w-2/3 animate-pulse rounded-3xl bg-surface-2" />}
            </div>
          ))}
          <div ref={end} />
        </div>
        <form className={cn("sticky bottom-24 mt-6 flex gap-2 rounded-full border border-line bg-surface p-1.5 shadow-lift lg:bottom-6")}
          onSubmit={(e) => { e.preventDefault(); ask(q); }}>
          <Input className="h-10 flex-1 rounded-full border-0 focus:ring-0" placeholder="How much did I spend on food last month?" value={q} maxLength={300}
            onChange={(e) => setQ(e.target.value)} aria-label="Ask a question" />
          <Button type="submit" size="icon" className="size-10" disabled={busy || !q.trim()} aria-label="Ask"><ArrowUp /></Button>
        </form>
      </div>
    </>
  );
}
