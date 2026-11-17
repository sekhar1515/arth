"use client";

import { Bell } from "lucide-react";
import Link from "next/link";
import { useEffect, useRef } from "react";
import { useNotificationPrefs, useNotifications, useUnreadCount } from "@/hooks/planning";
import { cn } from "@/lib/utils";

/**
 * Unread badge. When browser notifications are on and permission is granted, new unread items also appear as
 * system notifications while Arth is open in a tab (there is no background push service).
 */
export function NotificationBell({ className }: { className?: string }) {
  const unread = useUnreadCount();
  const prefs = useNotificationPrefs();
  const list = useNotifications(true);
  const seen = useRef<Set<string> | null>(null);
  const count = unread.data?.count ?? 0;

  useEffect(() => {
    if (!list.data) return;
    const ids = new Set(list.data.map((n) => n.id));
    if (seen.current === null) {
      seen.current = ids; // don't pop up everything that was already unread on page load
      return;
    }
    const fresh = list.data.filter((n) => !seen.current!.has(n.id));
    seen.current = ids;
    if (!prefs.data?.browserEnabled || typeof Notification === "undefined" || Notification.permission !== "granted") return;
    for (const n of fresh.slice(0, 3)) new Notification(n.title, { body: n.body, tag: n.id });
  }, [list.data, prefs.data?.browserEnabled]);

  useEffect(() => {
    if (count > 0) list.refetch();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [count]);

  return (
    <Link href="/notifications" className={cn("relative rounded-full p-2 text-ink-2 hover:bg-surface-2", className)} aria-label={count ? `${count} unread notifications` : "Notifications"}>
      <Bell className="size-5" />
      {count > 0 && <span className="num absolute right-0.5 top-0.5 min-w-4 rounded-full bg-marigold px-1 text-center text-[10px] font-bold leading-4 text-[#3d2900]">{count > 99 ? "99+" : count}</span>}
    </Link>
  );
}
