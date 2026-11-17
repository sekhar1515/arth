"use client";

import { Archive, GitMerge, Plus, RotateCcw, Trash2 } from "lucide-react";
import { useState } from "react";
import { toast } from "sonner";
import { useQueryClient } from "@tanstack/react-query";
import { PageHeader } from "@/components/layout/app-shell";
import { CategoryIcon } from "@/components/finance/category-icon";
import { NewCategoryDialog } from "@/components/finance/dialogs";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent } from "@/components/ui/dialog";
import { Field, Input, Select } from "@/components/ui/input";
import { ErrorState, Panel, Pill, Skeleton } from "@/components/ui/states";
import { useCategories } from "@/hooks/queries";
import { api, ApiError } from "@/lib/api";
import type { Category, CategoryKind } from "@/types/api";
import { useQuery } from "@tanstack/react-query";

interface Rule { id: string; matchType: "MERCHANT" | "DESCRIPTION_CONTAINS" | "NOTE_KEYWORD"; merchantName: string | null; pattern: string | null; categoryId: string; hitCount: number }

export default function CategoriesPage() {
  const qc = useQueryClient();
  const cats = useCategories(true);
  const rules = useQuery({ queryKey: ["rules"], queryFn: () => api.get<Rule[]>("/rules") });
  const [kind, setKind] = useState<CategoryKind>("EXPENSE");
  const [adding, setAdding] = useState(false);
  const [edit, setEdit] = useState<Category | null>(null);
  const [merge, setMerge] = useState<Category | null>(null);
  const [del, setDel] = useState<Category | null>(null);
  const tree = (cats.data ?? []).filter((c) => c.kind === kind);
  const flat = (cats.data ?? []).flatMap((c) => [c, ...c.children]);
  const groups = [...new Set(tree.map((c) => c.group ?? "Custom"))];

  async function run(fn: () => Promise<unknown>, ok: string) {
    try {
      await fn();
      await qc.invalidateQueries({ queryKey: ["categories"] });
      qc.invalidateQueries({ queryKey: ["transactions"] });
      qc.invalidateQueries({ queryKey: ["summary"] });
      qc.invalidateQueries({ queryKey: ["rules"] });
      qc.invalidateQueries({ queryKey: ["review"] });
      qc.invalidateQueries({ queryKey: ["review-count"] });
      qc.invalidateQueries({ queryKey: ["budgets"] });
      toast.success(ok);
    } catch (e) {
      toast.error(e instanceof ApiError ? e.message : "Couldn't save");
    }
  }

  return (
    <>
      <PageHeader title="Categories" subtitle="Rename, recolour, nest, merge, archive or delete anything. Archived categories keep their history; deleted ones are gone."
        actions={<Button size="sm" onClick={() => setAdding(true)}><Plus /> New category</Button>} />
      <div className="mb-5 flex rounded-full border border-line bg-surface p-0.5 sm:w-fit" role="radiogroup" aria-label="Category type">
        {(["EXPENSE", "INCOME"] as const).map((k) => (
          <button key={k} role="radio" aria-checked={kind === k} onClick={() => setKind(k)}
            className={`flex-1 rounded-full px-4 py-1.5 text-sm font-semibold ${kind === k ? "bg-peacock text-white dark:text-[#06201f]" : "text-ink-2"}`}>
            {k === "EXPENSE" ? "Spending" : "Income"}
          </button>
        ))}
      </div>
      {cats.isError ? <ErrorState error={cats.error} retry={() => cats.refetch()} /> : !cats.data ? <Skeleton className="h-96" /> : (
        <div className="grid gap-6 lg:grid-cols-[1fr_340px]">
          <div className="space-y-6">
            {groups.map((g) => (
              <section key={g}>
                <h2 className="mb-2 font-display text-sm font-semibold text-ink-2">{g}</h2>
                <ul className="divide-y divide-line overflow-hidden rounded-3xl border border-line bg-surface">
                  {tree.filter((c) => (c.group ?? "Custom") === g).map((c) => (
                    <li key={c.id} className={c.archived ? "opacity-50" : undefined}>
                      <div className="flex items-center gap-3 px-4 py-3">
                        <CategoryIcon icon={c.icon} color={c.color} />
                        <button className="flex-1 text-left text-sm font-semibold hover:underline" onClick={() => setEdit(c)}>{c.name}</button>
                        {c.investment && <Pill tone="brand">Counts as saved</Pill>}
                        {c.archived && <Pill>Archived</Pill>}
                        <CatActions c={c} onMerge={() => setMerge(c)} onDelete={() => setDel(c)} run={run} />
                      </div>
                      {c.children.length > 0 && (
                        <div className="flex flex-wrap gap-1.5 px-4 pb-3 pl-16">
                          {c.children.map((ch) => (
                            <span key={ch.id} className={`group inline-flex items-center gap-1 rounded-full bg-surface-2 py-1 pl-3 pr-1 text-[13px] ${ch.archived ? "line-through opacity-60" : ""}`}>
                              <button onClick={() => setEdit(ch)} className="hover:underline">{ch.name}</button>
                              <button aria-label={`${ch.archived ? "Restore" : "Archive"} ${ch.name}`} className="rounded-full p-1 text-ink-3 hover:bg-line"
                                onClick={() => run(() => api.post(`/categories/${ch.id}/${ch.archived ? "restore" : "archive"}`), ch.archived ? "Restored" : "Archived")}>
                                {ch.archived ? <RotateCcw className="size-3" /> : <Trash2 className="size-3" />}
                              </button>
                            </span>
                          ))}
                        </div>
                      )}
                    </li>
                  ))}
                </ul>
              </section>
            ))}
          </div>
          <Panel title="Your rules" className="h-fit">
            <p className="mb-3 text-[13px] text-ink-2">Created when you choose &ldquo;Always…&rdquo; while categorising. Note rules are checked first, then payee rules; both beat Arth&apos;s own guesses.</p>
            {rules.data?.length === 0 && <p className="text-sm text-ink-3">No rules yet.</p>}
            <ul className="space-y-2">
              {rules.data?.map((r) => (
                <li key={r.id} className="flex items-center gap-2 text-sm">
                  <span className="min-w-0 flex-1">
                    <span className="block truncate font-semibold">{r.matchType === "MERCHANT" ? `Paid to ${r.merchantName}` : r.matchType === "NOTE_KEYWORD" ? `Note says "${r.pattern}"` : `Text contains "${r.pattern}"`}</span>
                    <span className="block truncate text-xs text-ink-3">{flat.find((c) => c.id === r.categoryId)?.path}, used {r.hitCount}×</span>
                  </span>
                  <Button variant="ghost" size="icon" aria-label="Delete rule" onClick={() => run(() => api.del(`/rules/${r.id}`), "Rule deleted")}><Trash2 /></Button>
                </li>
              ))}
            </ul>
          </Panel>
        </div>
      )}
      {adding && cats.data && <NewCategoryDialog categories={cats.data} kind={kind} open onOpenChange={setAdding} />}
      {edit && <EditDialog c={edit} onClose={() => setEdit(null)} run={run} onDelete={() => { setDel(edit); setEdit(null); }} />}
      {del && <DeleteCategoryDialog c={del} all={flat} onClose={() => setDel(null)} run={run} />}
      {merge && <MergeDialog c={merge} all={flat} onClose={() => setMerge(null)} run={run} />}
    </>
  );
}

function CatActions({ c, onMerge, onDelete, run }: { c: Category; onMerge: () => void; onDelete: () => void; run: (fn: () => Promise<unknown>, ok: string) => void }) {
  return (
    <div className="flex">
      <Button variant="ghost" size="icon" aria-label={`Merge ${c.name}`} title="Merge into another category" onClick={onMerge}><GitMerge /></Button>
      <Button variant="ghost" size="icon" aria-label={`${c.archived ? "Restore" : "Archive"} ${c.name}`} title={c.archived ? "Restore" : "Archive"}
        onClick={() => run(() => api.post(`/categories/${c.id}/${c.archived ? "restore" : "archive"}`), c.archived ? "Restored" : "Archived")}>
        {c.archived ? <RotateCcw /> : <Archive />}
      </Button>
      <Button variant="ghost" size="icon" aria-label={`Delete ${c.name}`} title="Delete permanently" className="text-out hover:bg-out-soft hover:text-out" onClick={onDelete}><Trash2 /></Button>
    </div>
  );
}

function EditDialog({ c, onClose, run, onDelete }: { c: Category; onClose: () => void; run: (fn: () => Promise<unknown>, ok: string) => Promise<void> | void; onDelete: () => void }) {
  const [name, setName] = useState(c.name);
  const [color, setColor] = useState(c.color ?? "#64748B");
  const [investment, setInvestment] = useState(c.investment);
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent title={`Edit ${c.path}`}>
        <div className="space-y-4">
          <Field label="Name" htmlFor="e-name"><Input id="e-name" value={name} maxLength={60} onChange={(e) => setName(e.target.value)} /></Field>
          <Field label="Colour" htmlFor="e-color"><input id="e-color" type="color" value={color} onChange={(e) => setColor(e.target.value)} className="h-10 w-14 rounded-xl border border-line bg-surface p-1" /></Field>
          {c.parentId === null && c.kind === "EXPENSE" && (
            <label className="flex items-start gap-3 text-sm">
              <input type="checkbox" className="mt-1 size-4 accent-[var(--peacock)]" checked={investment} onChange={(e) => setInvestment(e.target.checked)} />
              <span><span className="block font-semibold">Money here is invested, not spent</span>
                <span className="block text-[13px] text-ink-2">Counted as savings on the dashboard (like SIPs, PPF, FD deposits).</span></span>
            </label>
          )}
          <Button className="w-full" onClick={async () => { await run(() => api.put(`/categories/${c.id}`, { name, color, investment }), "Category saved"); onClose(); }}>Save changes</Button>
          <Button variant="ghost" className="w-full text-out hover:bg-out-soft hover:text-out" onClick={onDelete}><Trash2 /> Delete this category</Button>
        </div>
      </DialogContent>
    </Dialog>
  );
}

function MergeDialog({ c, all, onClose, run }: { c: Category; all: Category[]; onClose: () => void; run: (fn: () => Promise<unknown>, ok: string) => Promise<void> | void }) {
  const [target, setTarget] = useState("");
  const options = all.filter((x) => x.id !== c.id && x.kind === c.kind && !x.archived && x.parentId !== c.id);
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent title={`Merge ${c.name}`} description="Every transaction, split and rule moves to the category you pick. Then this one is archived.">
        <Field label="Merge into" htmlFor="m-target">
          <Select id="m-target" value={target} onChange={(e) => setTarget(e.target.value)}>
            <option value="">Choose a category</option>
            {options.map((o) => <option key={o.id} value={o.id}>{o.path}</option>)}
          </Select>
        </Field>
        <Button className="mt-5 w-full" disabled={!target} onClick={async () => { await run(() => api.post(`/categories/${c.id}/merge-into/${target}`), "Categories merged"); onClose(); }}>
          Merge categories
        </Button>
      </DialogContent>
    </Dialog>
  );
}

function DeleteCategoryDialog({ c, all, onClose, run }: { c: Category; all: Category[]; onClose: () => void; run: (fn: () => Promise<unknown>, ok: string) => Promise<void> | void }) {
  const [mode, setMode] = useState<"review" | "move">("review");
  const [target, setTarget] = useState("");
  const inside = new Set([c.id, ...c.children.map((ch) => ch.id)]);
  const options = all.filter((x) => !inside.has(x.id) && x.kind === c.kind && !x.archived);
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent title={`Delete ${c.path}?`}
        description={c.children.length > 0 ? `Its ${c.children.length} subcategories are deleted too. Rules and budgets for them are removed.` : "Rules and budgets for it are removed."}>
        <fieldset className="space-y-2 text-sm">
          <legend className="mb-2 font-semibold">What should happen to its transactions?</legend>
          <label className="flex items-start gap-3 rounded-2xl border border-line p-3">
            <input type="radio" name="mode" className="mt-1 accent-[var(--peacock)]" checked={mode === "review"} onChange={() => setMode("review")} />
            <span><span className="block font-semibold">Send them back to Needs Review</span><span className="block text-[13px] text-ink-2">Split transactions that use it lose their split too.</span></span>
          </label>
          <label className="flex items-start gap-3 rounded-2xl border border-line p-3">
            <input type="radio" name="mode" className="mt-1 accent-[var(--peacock)]" checked={mode === "move"} onChange={() => setMode("move")} />
            <span className="flex-1"><span className="block font-semibold">Move them to another category</span>
              {mode === "move" && (
                <Select className="mt-2" aria-label="Move to" value={target} onChange={(e) => setTarget(e.target.value)}>
                  <option value="">Choose a category</option>
                  {options.map((o) => <option key={o.id} value={o.id}>{o.path}</option>)}
                </Select>
              )}
            </span>
          </label>
        </fieldset>
        <div className="mt-6 flex justify-end gap-2">
          <Button variant="ghost" onClick={onClose}>Cancel</Button>
          <Button variant="danger" disabled={mode === "move" && !target}
            onClick={async () => { await run(() => api.del(`/categories/${c.id}${mode === "move" ? `?moveTo=${target}` : ""}`), "Category deleted"); onClose(); }}>
            Delete category
          </Button>
        </div>
      </DialogContent>
    </Dialog>
  );
}
