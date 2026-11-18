"use client";

import { Bell, BellRing, CheckCheck, RefreshCw, Settings2, Trash2 } from "lucide-react";
import Link from "next/link";
import { useState } from "react";
import { toast } from "sonner";
import { PageHeader } from "@/components/layout/app-shell";
import { Button } from "@/components/ui/button";
import { EmptyState, ErrorState, Skeleton } from "@/components/ui/states";
import { useInvalidatePlanning, useNotifications } from "@/hooks/planning";
import { api, ApiError } from "@/lib/api";
import { relativeTime } from "@/lib/format";
import { cn } from "@/lib/utils";
import type { AppNotification } from "@/types/api";

export default function NotificationsPage() {
  const [unreadOnly, setUnreadOnly] = useState(false);
  const list = useNotifications(unreadOnly);
  const invalidate = useInvalidatePlanning();
  const [checking, setChecking] = useState(false);

  async function act(fn: () => Promise<unknown>, ok?: string) {
    try {
      await fn();
      invalidate();
      if (ok) toast.success(ok);
    } catch (e) {
      toast.error(e instanceof ApiError ? e.message : "Something went wrong");
    }
  }

  async function check() {
    setChecking(true);
    await act(async () => {
      const r = await api.post<{ created: number }>("/notifications/check");
      toast.success(r.created ? `${r.created} new` : "Nothing new");
    });
    setChecking(false);
  }

  function open(n: AppNotification) {
    if (!n.read) act(() => api.post(`/notifications/${n.id}/read`));
  }

  return (
    <>
      <PageHeader title="Notifications" subtitle="Budget alerts, bills coming up, card due dates, and price changes. Arth checks every hour."
        actions={<>
          <Button variant="outline" size="sm" onClick={check} disabled={checking}><RefreshCw className={checking ? "animate-spin" : undefined} /> Check now</Button>
          <Button variant="outline" size="sm" onClick={() => act(() => api.post("/notifications/read-all"), "All marked read")}><CheckCheck /> Mark all read</Button>
          <Button variant="ghost" size="sm" asChild><Link href="/settings#notifications"><Settings2 /> Settings</Link></Button>
        </>} />
      <div className="mb-4 flex items-center gap-3">
        <div className="flex rounded-full border border-line bg-surface p-0.5" role="radiogroup" aria-label="Show">
          {[false, true].map((u) => (
            <button key={String(u)} role="radio" aria-checked={unreadOnly === u} onClick={() => setUnreadOnly(u)}
              className={cn("rounded-full px-4 py-1.5 text-sm font-semibold", unreadOnly === u ? "bg-peacock text-white dark:text-[#06201f]" : "text-ink-2")}>{u ? "Unread" : "All"}</button>
          ))}
        </div>
        <Button variant="ghost" size="sm" className="ml-auto" onClick={() => act(() => api.del("/notifications/read"), "Read notifications cleared")}><Trash2 /> Clear read</Button>
      </div>
      {list.isError ? <ErrorState error={list.error} retry={() => list.refetch()} /> : !list.data ? <Skeleton className="h-64" /> : list.data.length === 0 ? (
        <div className="rounded-3xl border border-line bg-surface"><EmptyState icon={<Bell />} title={unreadOnly ? "You're all caught up" : "No notifications yet"} body="Set a budget or confirm a subscription and Arth will let you know when something needs attention." /></div>
      ) : (
        <ul className="divide-y divide-line overflow-hidden rounded-3xl border border-line bg-surface">
          {list.data.map((n) => (
            <li key={n.id} className={cn("flex items-start gap-3 px-4 py-3.5", !n.read && "bg-peacock-soft/40")}>
              <span className={cn("mt-0.5 grid size-8 shrink-0 place-items-center rounded-xl",
                n.severity === "CRITICAL" ? "bg-out-soft text-out" : n.severity === "WARNING" ? "bg-marigold-soft text-marigold-ink" : "bg-surface-2 text-ink-2")}>
                <BellRing className="size-4" />
              </span>
              <div className="min-w-0 flex-1">
                {n.link ? <Link href={n.link} onClick={() => open(n)} className="font-semibold hover:underline">{n.title}</Link> : <p className="font-semibold">{n.title}</p>}
                <p className="text-sm text-ink-2">{n.body}</p>
                <p className="mt-0.5 text-xs text-ink-3">{relativeTime(n.createdAt)}</p>
              </div>
              {!n.read && <Button variant="ghost" size="sm" onClick={() => open(n)}>Mark read</Button>}
              <Button variant="ghost" size="icon" aria-label="Delete notification" onClick={() => act(() => api.del(`/notifications/${n.id}`))}><Trash2 /></Button>
            </li>
          ))}
        </ul>
      )}
    </>
  );
}
