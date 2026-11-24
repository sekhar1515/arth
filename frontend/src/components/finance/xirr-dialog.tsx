"use client";

import { useState } from "react";
import { toast } from "sonner";
import { api, ApiError } from "@/lib/api";
import { inr, isoDay } from "@/lib/format";
import type { Holding } from "@/types/api";
import { useInvalidateWealth } from "@/hooks/queries";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent } from "@/components/ui/dialog";
import { Field, Input } from "@/components/ui/input";
import { AmountInput } from "@/components/ui/amount-input";

const num = (v: string) => v.replace(/[^0-9.]/g, "");

/** XIRR needs to know when the money went in: asks for the start date (and confirms the amount put in). */
export function XirrDialog({ holding, open, onOpenChange, onPurchases }: { holding: Holding; open: boolean; onOpenChange: (o: boolean) => void; onPurchases: () => void }) {
  const invalidate = useInvalidateWealth();
  const [since, setSince] = useState(holding.startDate ?? "");
  const [invested, setInvested] = useState(holding.investedAmount?.toString() ?? "");
  const [busy, setBusy] = useState(false);

  async function save() {
    setBusy(true);
    try {
      await api.put(`/networth/holdings/${holding.id}`, { startDate: since, investedAmount: invested || null, version: holding.version });
      invalidate();
      toast.success("XIRR worked out");
      onOpenChange(false);
    } catch (e) {
      toast.error(e instanceof ApiError ? e.message : "Couldn't save");
    } finally {
      setBusy(false);
    }
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent title={`XIRR for ${holding.name}`}
        description="XIRR is your average yearly return, taking into account when each rupee went in and at what price.">
        <button type="button" onClick={onPurchases}
          className="mb-4 w-full rounded-2xl border border-peacock-2/50 bg-peacock-soft/40 p-4 text-left hover:bg-peacock-soft">
          <span className="block text-sm font-semibold text-peacock">Bought on different dates? Add each purchase (exact)</span>
          <span className="mt-0.5 block text-xs text-ink-2">Date and amount of each buy (plus units or price if you have them). Also shows how each purchase and each year&apos;s buying has done.</span>
        </button>
        <p className="mb-2 text-xs font-semibold uppercase tracking-wide text-ink-3">Or a quick estimate from one start date</p>
        <div className="grid gap-4 sm:grid-cols-2">
          <Field label="Investing since" htmlFor="xirr-since" hint="Your first purchase or SIP. A rough month is fine.">
            <Input id="xirr-since" type="date" value={since} max={isoDay(new Date())} onChange={(e) => setSince(e.target.value)} autoFocus />
          </Field>
          <Field label="Total you've put in" htmlFor="xirr-invested" hint="Everything invested so far, not today's value">
            <AmountInput id="xirr-invested" className="num" value={invested} onValueChange={(v) => setInvested(v)} />
          </Field>
        </div>
        <p className="mt-4 rounded-2xl bg-surface-2 p-3 text-xs text-ink-2">
          Arth counts {invested ? <strong className="num text-ink">{inr(Number(invested))}</strong> : "what you put in"} from that date
          {" "}(spread month by month if this has a SIP), adds each SIP Arth records on its date, and compares with today&apos;s value.
          It&apos;s marked &ldquo;est.&rdquo; when it relies on that spread; for exact XIRR you&apos;d need every purchase date.
        </p>
        <Button className="mt-4 w-full" variant="outline" disabled={busy || !since} onClick={save}>Show estimated XIRR</Button>
      </DialogContent>
    </Dialog>
  );
}
