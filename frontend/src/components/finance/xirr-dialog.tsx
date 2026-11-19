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

const num = (v: string) => v.replace(/[^0-9.]/g, "");

/** XIRR needs to know when the money went in: asks for the start date (and confirms the amount put in). */
export function XirrDialog({ holding, open, onOpenChange }: { holding: Holding; open: boolean; onOpenChange: (o: boolean) => void }) {
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
        description="XIRR is your yearly return, taking into account when each rupee went in. Tell Arth when you started, and it works it out.">
        <div className="grid gap-4 sm:grid-cols-2">
          <Field label="Investing since" htmlFor="xirr-since" hint="Your first purchase or SIP. A rough month is fine.">
            <Input id="xirr-since" type="date" value={since} max={isoDay(new Date())} onChange={(e) => setSince(e.target.value)} autoFocus />
          </Field>
          <Field label="Total you've put in" htmlFor="xirr-invested" hint="Everything invested so far, not today's value">
            <Input id="xirr-invested" inputMode="decimal" className="num" value={invested} onChange={(e) => setInvested(num(e.target.value))} />
          </Field>
        </div>
        <p className="mt-4 rounded-2xl bg-surface-2 p-3 text-xs text-ink-2">
          Arth counts {invested ? <strong className="num text-ink">{inr(Number(invested))}</strong> : "what you put in"} from that date
          {" "}(spread month by month if this has a SIP), adds each SIP Arth records on its date, and compares with today&apos;s value.
          It&apos;s marked &ldquo;est.&rdquo; when it relies on that spread; for exact XIRR you&apos;d need every purchase date.
        </p>
        <Button className="mt-4 w-full" disabled={busy || !since} onClick={save}>Show XIRR</Button>
      </DialogContent>
    </Dialog>
  );
}
