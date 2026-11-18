"use client";

import { Download, RotateCcw, Trash2 } from "lucide-react";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { toast } from "sonner";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Button } from "@/components/ui/button";
import { useConfirm } from "@/components/ui/confirm";
import { Dialog, DialogContent } from "@/components/ui/dialog";
import { Field, Input } from "@/components/ui/input";
import { Panel } from "@/components/ui/states";
import { api, ApiError } from "@/lib/api";

export function DataPanel({ demo }: { demo: boolean }) {
  const qc = useQueryClient();
  const deleted = useQuery({ queryKey: ["deleted-imports"], queryFn: () => api.get<{ count: number }>("/privacy/deleted-imports") });
  const { confirm, dialog } = useConfirm();
  const [deleting, setDeleting] = useState(false);
  const n = deleted.data?.count ?? 0;

  async function forget() {
    const ok = await confirm({
      title: `Let ${n} deleted transaction${n === 1 ? "" : "s"} come back?`,
      body: "Arth will stop skipping them, so importing the same statement again adds them back. Nothing changes until you import.",
      confirmLabel: "Allow re-import",
    });
    if (!ok) return;
    try {
      await api.del("/privacy/deleted-imports");
      qc.invalidateQueries({ queryKey: ["deleted-imports"] });
      toast.success("Done. Re-import the statement to bring them back.");
    } catch (e) {
      toast.error(e instanceof ApiError ? e.message : "Couldn't do that");
    }
  }

  return (
    <Panel title="Your data">
      <div className="space-y-5 text-sm">
        <div className="flex flex-wrap items-center gap-3">
          <div className="min-w-0 flex-1">
            <p className="font-semibold">Download everything</p>
            <p className="text-[13px] text-ink-2">Accounts, transactions, categories, rules, budgets, investments and settings as one JSON file. Your password is never included.</p>
          </div>
          <Button asChild variant="outline" size="sm"><a href="/api/privacy/export" download><Download /> JSON</a></Button>
          <Button asChild variant="outline" size="sm"><a href="/api/transactions/export.csv"><Download /> Transactions CSV</a></Button>
        </div>
        <div className="flex flex-wrap items-center gap-3 border-t border-line pt-5">
          <div className="min-w-0 flex-1">
            <p className="font-semibold">Deleted imported transactions</p>
            <p className="text-[13px] text-ink-2">{n === 0 ? "None. When you delete an imported transaction, Arth remembers it so re-importing doesn't bring it back." : `${n} remembered, so re-importing the same statement skips them.`}</p>
          </div>
          {n > 0 && <Button variant="outline" size="sm" onClick={forget}><RotateCcw /> Allow them back</Button>}
        </div>
        <div className="flex flex-wrap items-center gap-3 border-t border-line pt-5">
          <div className="min-w-0 flex-1">
            <p className="font-semibold text-out">Delete my account</p>
            <p className="text-[13px] text-ink-2">Permanently removes your account and every piece of data in it. Download a copy first if you want to keep anything.</p>
          </div>
          <Button variant="danger" size="sm" disabled={demo} onClick={() => setDeleting(true)}><Trash2 /> Delete account</Button>
        </div>
      </div>
      {deleting && <DeleteAccountDialog onClose={() => setDeleting(false)} />}
      {dialog}
    </Panel>
  );
}

function DeleteAccountDialog({ onClose }: { onClose: () => void }) {
  const router = useRouter();
  const qc = useQueryClient();
  const [password, setPassword] = useState("");
  const [confirmation, setConfirmation] = useState("");
  const [busy, setBusy] = useState(false);
  async function go() {
    setBusy(true);
    try {
      await api.post("/privacy/delete-account", { password, confirmation });
      qc.clear();
      router.replace("/login");
    } catch (e) {
      toast.error(e instanceof ApiError ? e.message : "Couldn't delete");
      setBusy(false);
    }
  }
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent title="Delete your Arth account?" description="Everything goes: transactions, accounts, investments and settings. This can't be undone.">
        <div className="space-y-4">
          <Field label="Password" htmlFor="del-pw"><Input id="del-pw" type="password" autoComplete="current-password" value={password} onChange={(e) => setPassword(e.target.value)} /></Field>
          <Field label="Type DELETE to confirm" htmlFor="del-confirm"><Input id="del-confirm" value={confirmation} onChange={(e) => setConfirmation(e.target.value)} /></Field>
          <Button variant="danger" className="w-full" disabled={busy || !password || confirmation !== "DELETE"} onClick={go}>Delete everything</Button>
        </div>
      </DialogContent>
    </Dialog>
  );
}
