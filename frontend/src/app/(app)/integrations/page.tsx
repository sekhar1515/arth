"use client";

import { Check, Copy, FileUp, KeyRound, Plug, RefreshCw, Undo2, Unplug } from "lucide-react";
import { useConfirm } from "@/components/ui/confirm";
import { useRef, useState } from "react";
import { toast } from "sonner";
import { useQueryClient } from "@tanstack/react-query";
import { PageHeader } from "@/components/layout/app-shell";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent } from "@/components/ui/dialog";
import { Field, Select } from "@/components/ui/input";
import { ErrorState, Panel, Pill, Skeleton } from "@/components/ui/states";
import { useAccounts, useImportCsv, useIntegrations, useInvalidateMoney, useSyncHistory } from "@/hooks/queries";
import { api, ApiError } from "@/lib/api";
import { formatDateTime, relativeTime } from "@/lib/format";
import { cn } from "@/lib/utils";
import type { Account, CatalogueEntry, DeleteResult, ImportResult, SyncRecord } from "@/types/api";

type ConnectResult = { connection: { id: string }; ingestToken: string; ingestPath: string };

function Facts({ e }: { e: CatalogueEntry }) {
  const p = e.provider;
  const yesNo = (v: boolean) => (v ? "Yes" : "No");
  return (
    <dl className="mt-4 grid grid-cols-2 gap-x-4 gap-y-2 text-[13px] sm:grid-cols-3">
      <div><dt className="text-ink-3">Data</dt><dd>{p.dataRetrieved}</dd></div>
      <div><dt className="text-ink-3">How often</dt><dd>{p.syncFrequency}</dd></div>
      <div><dt className="text-ink-3">Needs your consent</dt><dd>{yesNo(p.requiresUserConsent)}</dd></div>
      <div><dt className="text-ink-3">Commercial partnership</dt><dd>{yesNo(p.requiresCommercialPartnership)}</dd></div>
      <div><dt className="text-ink-3">Regulatory approval</dt><dd>{yesNo(p.requiresRegulatoryApproval)}</dd></div>
    </dl>
  );
}

function CsvImport({ accounts }: { accounts: Account[] }) {
  const usable = accounts.filter((a) => !a.archived && a.type !== "CASH");
  const [accountId, setAccountId] = useState(usable[0]?.id ?? "");
  const [result, setResult] = useState<ImportResult | null>(null);
  const fileRef = useRef<HTMLInputElement>(null);
  const imp = useImportCsv();
  function onFile(file: File | undefined) {
    if (!file || !accountId) return;
    imp.mutate({ accountId, file }, {
      onSuccess: (r) => { setResult(r); toast.success(`Imported ${r.imported} new transaction${r.imported === 1 ? "" : "s"}`); },
      onError: (e) => toast.error(e instanceof ApiError ? e.message : "Import failed"),
    });
    if (fileRef.current) fileRef.current.value = "";
  }
  if (usable.length === 0) return <p className="mt-4 text-sm text-ink-2">Add a bank account or card first, so Arth knows which account the statement belongs to.</p>;
  return (
    <div className="mt-4">
      <div className="mb-4 rounded-2xl border border-peacock-2/30 bg-peacock-soft/60 p-4 text-[13px] text-ink-2">
        <p className="font-semibold text-peacock">Tell Arth what each payment was for</p>
        <p className="mt-1">
          Upload your bank&apos;s statement as Excel (.xlsx or .xls) or CSV, or your own sheet with <strong>Date, Description, Amount, Type</strong>: write what each payment was for in Description (&ldquo;maid salary&rdquo;, &ldquo;petrol for bike&rdquo;).
          Next to a bank narration, add a <strong>Note</strong> column (&ldquo;sabzi&rdquo;, &ldquo;rent nov&rdquo;, &ldquo;petrol&rdquo;, &ldquo;maid salary&rdquo;) and Arth files each payment from it, even when you pay the same person for different things.
          Add a <strong>Category</strong> column to file a row directly. The note you type in PhonePe or Google Pay is also read from the bank narration when your bank keeps it.
          Already imported a statement? Add notes to it and import it again: existing rows get updated, not duplicated.
        </p>
        <a href="/templates/arth-transactions-template.csv" download className="mt-2 inline-block font-semibold text-peacock hover:underline">Download a sample sheet</a>
      </div>
      <div className="flex flex-wrap items-end gap-3">
        <div className="w-64"><Field label="Statement for" htmlFor="csv-acc">
          <Select id="csv-acc" value={accountId} onChange={(e) => setAccountId(e.target.value)}>
            {usable.map((a) => <option key={a.id} value={a.id}>{a.name}{a.last4 ? ` ••${a.last4}` : ""}</option>)}
          </Select>
        </Field></div>
        <input ref={fileRef} type="file" accept=".csv,.txt,.xlsx,.xls,text/csv,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet,application/vnd.ms-excel" className="sr-only" id="csv-file" onChange={(e) => onFile(e.target.files?.[0])} />
        <Button asChild disabled={imp.isPending}><label htmlFor="csv-file" className="cursor-pointer"><FileUp /> {imp.isPending ? "Importing…" : "Choose Excel or CSV file"}</label></Button>
      </div>
      {result && (
        <div className="mt-4 rounded-2xl bg-surface-2 p-4 text-sm">
          <p className="font-semibold">Read {result.rows} rows ({result.layout})</p>
          <ul className="mt-2 grid grid-cols-2 gap-1 sm:grid-cols-4">
            <li><span className="num font-semibold">{result.imported}</span> new</li>
            <li><span className="num font-semibold">{result.updatedWithNotes}</span> updated with your notes</li>
            <li><span className="num font-semibold">{result.alreadyImported}</span> already imported</li>
            {result.previouslyDeleted > 0 && <li><span className="num font-semibold">{result.previouslyDeleted}</span> skipped because you deleted them before</li>}
            <li><span className="num font-semibold">{result.suspectedDuplicates}</span> possible duplicates</li>
            <li><span className="num font-semibold">{result.withNotes}</span> had a note</li>
            <li><span className="num font-semibold">{result.categorisedFromFile}</span> filed from your Category column</li>
            <li><span className="num font-semibold">{result.autoCategorised}</span> filed automatically</li>
            <li><span className="num font-semibold">{result.needsReview}</span> waiting in Needs Review</li>
          </ul>
          {result.needsReview > 0 && <a href="/review" className="mt-2 inline-block font-semibold text-peacock hover:underline">Review them now</a>}
          {result.warnings.length > 0 && (
            <ul className="mt-2 list-disc pl-5 text-xs text-marigold-ink">{result.warnings.slice(0, 10).map((w) => <li key={w}>{w}</li>)}</ul>
          )}
          {result.errors.length > 0 && (
            <details className="mt-2 text-out"><summary className="cursor-pointer">{result.errors.length} rows couldn&apos;t be read</summary>
              <ul className="mt-1 list-disc pl-5 text-xs">{result.errors.slice(0, 20).map((e) => <li key={e}>{e}</li>)}</ul>
            </details>
          )}
        </div>
      )}
    </div>
  );
}

function TokenDialog({ result, onClose }: { result: ConnectResult; onClose: () => void }) {
  const [copied, setCopied] = useState(false);
  const url = typeof window !== "undefined" ? `${window.location.origin}${result.ingestPath}` : result.ingestPath;
  const curl = `curl -X POST ${url} \\\n  -H "Content-Type: application/json" \\\n  -H "X-Ingest-Token: ${result.ingestToken}" \\\n  -d '{"text": "Rs.450.00 debited from A/c XX1234 to VPA swiggy@icici UPI Ref 431212345678"}'`;
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent title="Your ingest token" description="Shown only once. Arth stores just a fingerprint of it. If you lose it, rotate it." wide>
        <div className="flex items-center gap-2 rounded-2xl bg-surface-2 p-3">
          <code className="flex-1 break-all text-[13px]">{result.ingestToken}</code>
          <Button size="sm" variant="outline" onClick={() => { navigator.clipboard.writeText(result.ingestToken); setCopied(true); }}>
            {copied ? <Check /> : <Copy />} {copied ? "Copied" : "Copy"}
          </Button>
        </div>
        <h3 className="mt-5 text-sm font-semibold">Send alerts to</h3>
        <p className="text-[13px] text-ink-2">POST <code>{url}</code> with header <code>X-Ingest-Token</code> and JSON <code>{`{"text": "<alert text>"}`}</code>. Your phone or script must be able to reach this address (for a home server, use a tunnel such as Cloudflare Tunnel or Tailscale).</p>
        <pre className="mt-3 overflow-x-auto rounded-2xl bg-[#0b1e20] p-4 text-[12px] leading-relaxed text-[#cfe9e7]">{curl}</pre>
        <p className="mt-3 text-[13px] text-ink-2">Only send transaction alerts. Arth throws away anything that looks like an OTP, but it&apos;s best to filter by your bank&apos;s sender ID on the phone too.</p>
        <Button className="mt-5 w-full" onClick={onClose}>I&apos;ve saved the token</Button>
      </DialogContent>
    </Dialog>
  );
}

function ProviderCard({ e, accounts, onToken }: { e: CatalogueEntry; accounts: Account[]; onToken: (r: ConnectResult) => void }) {
  const qc = useQueryClient();
  const p = e.provider;
  const c = e.connection;
  const [busy, setBusy] = useState(false);
  async function act(fn: () => Promise<unknown>, ok?: string) {
    setBusy(true);
    try {
      const r = await fn();
      if (r && typeof r === "object" && "ingestToken" in r) onToken(r as ConnectResult);
      else if (ok) toast.success(ok);
      qc.invalidateQueries({ queryKey: ["integrations"] });
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : "Something went wrong");
    } finally {
      setBusy(false);
    }
  }
  const status = c ? (c.lastError ? "error" : "connected") : p.availability === "AVAILABLE" ? (p.connectionMode === "WEBHOOK_TOKEN" ? "ready" : "ready") : p.availability.toLowerCase();

  return (
    <article className={cn("rounded-3xl border bg-surface p-5 sm:p-6", c ? "border-peacock-2/50" : "border-line", p.availability === "UNAVAILABLE" && "bg-surface/60")}>
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="max-w-2xl">
          <h3 className="font-display text-lg font-semibold">{p.name}</h3>
          <p className="mt-1 text-sm text-ink-2">{p.description}</p>
        </div>
        {status === "connected" && <Pill tone="in"><Check className="size-3" /> Connected</Pill>}
        {status === "error" && <Pill tone="out">Needs attention</Pill>}
        {status === "planned" && <Pill>Planned</Pill>}
        {status === "unavailable" && <Pill tone="neutral">Not available</Pill>}
      </div>

      {p.limitation && <p className={cn("mt-3 rounded-2xl px-3 py-2 text-[13px]", p.availability === "UNAVAILABLE" ? "bg-surface-2 text-ink-2" : "bg-marigold-soft text-marigold-ink")}>{p.limitation}</p>}

      {c && (
        <div className="mt-4 grid gap-3 rounded-2xl bg-surface-2 p-4 text-[13px] sm:grid-cols-4">
          <div><p className="text-ink-3">Last alert received</p><p className="font-semibold">{relativeTime(c.lastSyncAt)}</p></div>
          <div><p className="text-ink-3">Next sync</p><p className="font-semibold">{c.nextSyncAt ? formatDateTime(c.nextSyncAt) : "Real time, as alerts arrive"}</p></div>
          <div><p className="text-ink-3">Permission</p><p className="font-semibold">{c.permissions.length ? "Send transaction alerts" : "None"}</p></div>
          <div><p className="text-ink-3">Token</p><p className="num font-semibold">…{c.tokenHint}</p></div>
          {c.lastError && <p className="text-out sm:col-span-4">{c.lastError}</p>}
          <div className="sm:col-span-2">
            <Field label="If an alert has no account digits, use" htmlFor={`def-${c.id}`}>
              <Select id={`def-${c.id}`} value={c.defaultAccountId ?? ""} onChange={(ev) => act(() => api.put(`/integrations/connections/${c.id}`, { defaultAccountId: ev.target.value || null }), "Saved")}>
                <option value="">Don&apos;t guess: report it</option>
                {accounts.filter((a) => !a.archived).map((a) => <option key={a.id} value={a.id}>{a.name}</option>)}
              </Select>
            </Field>
          </div>
        </div>
      )}

      {p.connectionMode === "FILE_UPLOAD" && p.availability === "AVAILABLE" && <CsvImport accounts={accounts} />}
      {p.setupHint && p.availability === "AVAILABLE" && <p className="mt-3 text-[13px] text-ink-3">{p.setupHint}</p>}
      {e.lastSync && !c && <p className="mt-3 text-[13px] text-ink-3">Last import {relativeTime(e.lastSync.startedAt)}: {e.lastSync.imported} new, {e.lastSync.merged} matched.</p>}

      <div className="mt-4 flex flex-wrap gap-2">
        {p.connectionMode === "WEBHOOK_TOKEN" && p.availability === "AVAILABLE" && !c && (
          <Button disabled={busy} onClick={() => act(() => api.post(`/integrations/${p.key}/connect`, {}))}><Plug /> Connect</Button>
        )}
        {c && (
          <>
            <Button variant="outline" size="sm" disabled={busy} onClick={() => act(() => api.post(`/integrations/connections/${c.id}/rotate-token`))}><KeyRound /> New token</Button>
            <Button variant="ghost" size="sm" disabled={busy} onClick={() => qc.invalidateQueries({ queryKey: ["integrations"] })}><RefreshCw /> Refresh status</Button>
            <Button variant="ghost" size="sm" disabled={busy} onClick={() => confirm(`Disconnect ${p.name}? Alerts sent with the current token will be rejected.`) && act(() => api.post(`/integrations/connections/${c.id}/disconnect`), "Disconnected")}>
              <Unplug /> Disconnect
            </Button>
          </>
        )}
      </div>
      {p.availability !== "AVAILABLE" && <Facts e={e} />}
    </article>
  );
}

export default function IntegrationsPage() {
  const catalogue = useIntegrations();
  const accounts = useAccounts();
  const history = useSyncHistory();
  const [token, setToken] = useState<ConnectResult | null>(null);
  const list = catalogue.data ?? [];
  const { confirm, dialog } = useConfirm();
  const invalidate = useInvalidateMoney();

  async function undo(h: SyncRecord) {
    const ok = await confirm({
      title: "Undo this import?",
      body: <>Every transaction that came only from the statement imported on {formatDateTime(h.startedAt)} is deleted ({h.imported} new at the time; any you&apos;ve deleted since are already gone). Transactions it matched with existing ones stay. You can import the right file afterwards.</>,
      confirmLabel: "Undo import", danger: true,
    });
    if (!ok) return;
    try {
      const r = await api.post<DeleteResult>(`/imports/${h.id}/undo`);
      toast.success(`Import undone: ${r.deleted} transactions removed${r.sourcesDetached ? `, ${r.sourcesDetached} matched ones kept` : ""}`);
      invalidate();
    } catch (e) {
      toast.error(e instanceof ApiError ? e.message : "Couldn't undo");
    }
  }
  const groups: { title: string; note?: string; items: CatalogueEntry[] }[] = [
    { title: "Working now", items: list.filter((e) => e.provider.availability === "AVAILABLE" && e.provider.connectionMode !== "BUILT_IN") },
    { title: "Planned", items: list.filter((e) => e.provider.availability === "PLANNED") },
    { title: "Not available to a personal app", note: "Listed so you know why. Arth won't pretend these work.", items: list.filter((e) => e.provider.availability === "UNAVAILABLE") },
  ];

  return (
    <>
      <PageHeader title="Integrations"
        subtitle="PhonePe, Google Pay and Paytm don't offer a way to read your personal history. But every UPI payment debits your bank, and your bank alerts you. Arth collects from those alerts and from your statements." />
      {catalogue.isError ? <ErrorState error={catalogue.error} retry={() => catalogue.refetch()} /> : !catalogue.data || !accounts.data ? <Skeleton className="h-96" /> : (
        <div className="space-y-10">
          {groups.map((g) => g.items.length > 0 && (
            <section key={g.title}>
              <h2 className="font-display text-lg font-semibold">{g.title}</h2>
              {g.note && <p className="text-sm text-ink-2">{g.note}</p>}
              <div className="mt-3 space-y-4">{g.items.map((e) => <ProviderCard key={e.provider.key} e={e} accounts={accounts.data} onToken={setToken} />)}</div>
            </section>
          ))}
          <Panel title="Recent syncs and imports">
            {history.data?.length ? (
              <div className="overflow-x-auto">
                <table className="w-full text-sm">
                  <thead className="text-left text-[13px] text-ink-3"><tr><th className="py-1.5 pr-4 font-medium">When</th><th className="pr-4 font-medium">Source</th><th className="pr-4 font-medium">Result</th><th className="font-medium">Details</th><th /></tr></thead>
                  <tbody className="divide-y divide-line">
                    {history.data.map((h) => (
                      <tr key={h.id}>
                        <td className="py-2 pr-4 whitespace-nowrap text-ink-2">{formatDateTime(h.startedAt)}</td>
                        <td className="pr-4">{h.sourceType === "CSV_IMPORT" ? "Statement" : h.sourceType === "SMS" ? "SMS alert" : h.sourceType === "EMAIL" ? "E-mail alert" : h.sourceType}</td>
                        <td className="pr-4"><Pill tone={h.status === "FAILED" ? "out" : h.status === "PARTIAL" ? "attention" : "in"}>{h.status.toLowerCase()}</Pill></td>
                        <td className="text-ink-2">{h.imported} new, {h.merged} matched, {h.suspected} possible duplicates{h.error ? `. ${h.error}` : ""}</td>
                        <td className="pl-2 text-right">
                          {h.sourceType === "CSV_IMPORT" && h.imported + h.merged > 0 && (
                            <Button variant="ghost" size="sm" onClick={() => undo(h)} title="Remove the transactions this import added"><Undo2 /> Undo</Button>
                          )}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            ) : <p className="text-sm text-ink-3">Nothing synced yet.</p>}
          </Panel>
        </div>
      )}
      {dialog}
      {token && <TokenDialog result={token} onClose={() => setToken(null)} />}
    </>
  );
}
