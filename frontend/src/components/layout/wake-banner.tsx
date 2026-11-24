"use client";

import { Loader2 } from "lucide-react";
import { useEffect, useState } from "react";
import { serverWake } from "@/lib/api";

/** Shown while the free-hosted server is waking up, so a slow first load doesn't look broken. */
export function WakeBanner() {
  const [show, setShow] = useState(false);
  useEffect(() => {
    let timer: ReturnType<typeof setTimeout> | undefined;
    const unsubscribe = serverWake.subscribe((waking) => {
      clearTimeout(timer);
      if (waking) timer = setTimeout(() => setShow(true), 1200);
      else setShow(false);
    });
    return () => { clearTimeout(timer); unsubscribe(); };
  }, []);
  if (!show) return null;
  return (
    <div role="status" aria-live="polite"
      className="fixed inset-x-0 top-0 z-[60] flex items-center justify-center gap-2 bg-marigold-soft px-4 py-2 text-center text-[13px] text-marigold-ink shadow-sm">
      <Loader2 className="size-4 shrink-0 animate-spin" />
      <span><strong>Waking up the server.</strong> It sleeps when nobody has used it for a while; this can take up to 2 minutes. Keep this page open.</span>
    </div>
  );
}
