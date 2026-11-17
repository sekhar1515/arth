"use client";

import { useEffect, useState } from "react";
import { toast } from "sonner";
import { useQueryClient } from "@tanstack/react-query";
import { PageHeader } from "@/components/layout/app-shell";
import { Button } from "@/components/ui/button";
import { ErrorState, Panel, Skeleton } from "@/components/ui/states";
import { useMe, usePreferences } from "@/hooks/queries";
import { SecurityPanel } from "@/components/settings/security";
import { NotificationSettings } from "@/components/settings/notifications";
import { DataPanel } from "@/components/settings/data";
import { api, ApiError } from "@/lib/api";
import { applyTheme } from "@/lib/theme";
import { cn } from "@/lib/utils";
import type { CategorisationMode, Preferences } from "@/types/api";

const MODES: { value: CategorisationMode; title: string; body: string }[] = [
  { value: "ALWAYS_ASK", title: "Always ask", body: "Every new transaction waits for you. Arth still shows its best guess." },
  { value: "SMART_SUGGESTION", title: "Smart suggestions", body: "Suggestions learned from your past choices. Nothing is filed until you confirm." },
  { value: "SEMI_AUTOMATIC", title: "Semi-automatic", body: "Files transactions Arth is confident about, and asks about the rest." },
  { value: "FULL_AUTOMATIC", title: "Fully automatic", body: "Files everything it has a suggestion for. You can still change any of them later." },
];

export default function SettingsPage() {
  const prefs = usePreferences();
  const me = useMe();
  const demo = me.data?.demo ?? false;
  const qc = useQueryClient();
  const [draft, setDraft] = useState<Preferences | null>(null);
  useEffect(() => { if (prefs.data) setDraft(prefs.data); }, [prefs.data]);

  async function save(patch: Partial<Preferences>) {
    if (!draft) return;
    const next = { ...draft, ...patch };
    setDraft(next);
    try {
      await api.put("/preferences", patch);
      qc.invalidateQueries({ queryKey: ["preferences"] });
      if (patch.theme) applyTheme(patch.theme);
      toast.success("Settings saved");
    } catch (e) {
      toast.error(e instanceof ApiError ? e.message : "Couldn't save");
      setDraft(draft);
    }
  }

  return (
    <>
      <PageHeader title="Settings" />
      {prefs.isError ? <ErrorState error={prefs.error} retry={() => prefs.refetch()} /> : !draft ? <Skeleton className="h-96" /> : (
        <div className="max-w-3xl space-y-6">
          <Panel title="How new transactions are categorised">
            <div className="grid gap-3 sm:grid-cols-2" role="radiogroup" aria-label="Categorisation mode">
              {MODES.map((m) => (
                <button key={m.value} role="radio" aria-checked={draft.categorisationMode === m.value} onClick={() => save({ categorisationMode: m.value })}
                  className={cn("rounded-2xl border p-4 text-left transition-colors", draft.categorisationMode === m.value ? "border-peacock bg-peacock-soft" : "border-line hover:border-peacock-2/50")}>
                  <p className="font-semibold">{m.title}{m.value === "ALWAYS_ASK" && <span className="ml-2 text-xs font-normal text-ink-3">Default</span>}</p>
                  <p className="mt-1 text-[13px] text-ink-2">{m.body}</p>
                </button>
              ))}
            </div>
            {draft.categorisationMode === "SEMI_AUTOMATIC" && (
              <div className="mt-5">
                <label htmlFor="threshold" className="text-sm font-semibold">File automatically when Arth is at least <span className="num">{Math.round(draft.autoConfidenceThreshold * 100)}%</span> sure</label>
                <input id="threshold" type="range" min={0.5} max={0.99} step={0.01} value={draft.autoConfidenceThreshold}
                  onChange={(e) => setDraft({ ...draft, autoConfidenceThreshold: Number(e.target.value) })}
                  onMouseUp={() => save({ autoConfidenceThreshold: draft.autoConfidenceThreshold })}
                  onKeyUp={() => save({ autoConfidenceThreshold: draft.autoConfidenceThreshold })}
                  className="mt-2 w-full accent-[var(--peacock)]" />
                <p className="text-xs text-ink-3">Your own &ldquo;always&rdquo; rules count as 99% sure; Arth&apos;s built-in merchant knowledge is 65–80%.</p>
              </div>
            )}
          </Panel>

          <Panel title="Appearance">
            <div className="flex rounded-full border border-line p-0.5 sm:w-fit" role="radiogroup" aria-label="Theme">
              {(["light", "dark", "system"] as const).map((t) => (
                <button key={t} role="radio" aria-checked={draft.theme === t} onClick={() => save({ theme: t })}
                  className={cn("flex-1 rounded-full px-4 py-1.5 text-sm font-semibold capitalize", draft.theme === t ? "bg-peacock text-white dark:text-[#06201f]" : "text-ink-2")}>
                  {t === "system" ? "Match device" : t}
                </button>
              ))}
            </div>
          </Panel>

          <Panel title="Privacy">
            <label className="flex items-start gap-3">
              <input type="checkbox" className="mt-1 size-4 accent-[var(--peacock)]" checked={draft.externalAiConsent} onChange={(e) => save({ externalAiConsent: e.target.checked })} />
              <span>
                <span className="block text-sm font-semibold">Allow transaction details to be sent to an external AI provider</span>
                <span className="block text-[13px] text-ink-2">Off by default. Today the assistant runs entirely on this server and never sends anything out; this switch records your choice so no future feature can send data without it.</span>
              </span>
            </label>
            <div className="mt-5 border-t border-line pt-4 text-[13px] text-ink-2">
              <p>Arth never asks for or stores UPI PINs, banking passwords or OTPs. Ingest tokens are stored only as fingerprints, and two-step sign-in secrets are encrypted.</p>
            </div>
          </Panel>

          <NotificationSettings />
          <SecurityPanel demo={demo} />
          <DataPanel demo={demo} />
        </div>
      )}
    </>
  );
}
