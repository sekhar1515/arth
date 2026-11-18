"use client";

import { useEffect, useState } from "react";
import { toast } from "sonner";
import { useQueryClient } from "@tanstack/react-query";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Panel } from "@/components/ui/states";
import { useNotificationPrefs } from "@/hooks/planning";
import { api, ApiError } from "@/lib/api";
import type { NotificationPrefs } from "@/types/api";

const TOPICS: { key: keyof NotificationPrefs; title: string; body: string }[] = [
  { key: "budgetAlerts", title: "Budget alerts", body: "When a budget passes 50, 75, 90 and 100%" },
  { key: "billReminders", title: "Bills coming up", body: "A few days before a confirmed bill, EMI or rent is due, and if one looks missed" },
  { key: "subscriptionAlerts", title: "Subscription changes", body: "Price changes, charges after you cancelled, and ones you haven't reviewed in a while" },
  { key: "cardReminders", title: "Credit card due dates", body: "Before the due date, when overdue, and when you use more than 30% of the limit" },
  { key: "unusualSpending", title: "Large payments", body: "Any single payment at or above the amount below" },
  { key: "reviewReminders", title: "Review reminder", body: "Once a week when five or more transactions need a category" },
];

export function NotificationSettings() {
  const prefs = useNotificationPrefs();
  const qc = useQueryClient();
  const [draft, setDraft] = useState<NotificationPrefs | null>(null);
  const [permission, setPermission] = useState<string>("default");
  useEffect(() => { if (prefs.data) setDraft(prefs.data); }, [prefs.data]);
  useEffect(() => { if (typeof Notification !== "undefined") setPermission(Notification.permission); }, []);

  async function save(patch: Partial<NotificationPrefs>) {
    if (!draft) return;
    const next = { ...draft, ...patch };
    setDraft(next);
    try {
      await api.put("/notifications/preferences", next);
      qc.invalidateQueries({ queryKey: ["notification-prefs"] });
      toast.success("Saved");
    } catch (e) {
      setDraft(draft);
      toast.error(e instanceof ApiError ? e.message : "Couldn't save");
    }
  }

  async function allowBrowser() {
    if (typeof Notification === "undefined") return toast.error("This browser doesn't support notifications");
    const p = await Notification.requestPermission();
    setPermission(p);
    if (p === "granted") save({ browserEnabled: true });
  }

  if (!draft) return null;
  return (
    <Panel title="Notifications">
      <div id="notifications" className="-mt-20 pt-20" />
      <div className="space-y-3">
        <p className="text-[13px] text-ink-2">Every notification appears on the Notifications page. Choose which ones you want and where else they go.</p>
        <ul className="divide-y divide-line rounded-2xl border border-line">
          {TOPICS.map((t) => (
            <li key={t.key}>
              <label className="flex cursor-pointer items-start gap-3 px-4 py-3">
                <input type="checkbox" className="mt-1 size-4 accent-[var(--peacock)]" checked={Boolean(draft[t.key])} onChange={(e) => save({ [t.key]: e.target.checked })} />
                <span><span className="block text-sm font-semibold">{t.title}</span><span className="block text-[13px] text-ink-2">{t.body}</span></span>
              </label>
            </li>
          ))}
        </ul>
        <div className="flex flex-wrap items-center gap-2 text-sm">
          <span>Large payment means</span>
          <Input className="w-32" inputMode="decimal" defaultValue={String(draft.largeTxnThreshold)} aria-label="Large payment amount"
            onBlur={(e) => { const v = Number(e.target.value); if (v > 0 && v !== draft.largeTxnThreshold) save({ largeTxnThreshold: v }); }} />
          <span>rupees or more</span>
        </div>
        <div className="grid gap-3 border-t border-line pt-4 sm:grid-cols-2">
          <div>
            <p className="text-sm font-semibold">In this browser</p>
            <p className="text-[13px] text-ink-2">Pop-up notifications while Arth is open in a tab.</p>
            {permission === "granted" ? (
              <label className="mt-2 flex items-center gap-2 text-sm"><input type="checkbox" className="size-4 accent-[var(--peacock)]" checked={draft.browserEnabled} onChange={(e) => save({ browserEnabled: e.target.checked })} /> Show pop-ups</label>
            ) : permission === "denied" ? (
              <p className="mt-2 text-[13px] text-marigold-ink">Blocked in your browser settings.</p>
            ) : <Button className="mt-2" size="sm" variant="outline" onClick={allowBrowser}>Allow pop-ups</Button>}
          </div>
          <div>
            <p className="text-sm font-semibold">By e-mail</p>
            <p className="text-[13px] text-ink-2">{draft.emailAvailable ? "Sent to your sign-in address." : "This server doesn't have e-mail set up. Whoever runs Arth can add SMTP settings (see README)."}</p>
            <label className="mt-2 flex items-center gap-2 text-sm">
              <input type="checkbox" className="size-4 accent-[var(--peacock)]" disabled={!draft.emailAvailable} checked={draft.emailEnabled} onChange={(e) => save({ emailEnabled: e.target.checked })} /> Send e-mails
            </label>
          </div>
        </div>
      </div>
    </Panel>
  );
}
