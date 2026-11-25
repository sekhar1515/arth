"use client";

import { Loader2 } from "lucide-react";
import { useEffect, useState } from "react";
import { serverWake } from "@/lib/api";

/** Shown while the free-hosted server is waking up, so a slow first load doesn't look broken. */
export function WakeBanner() {
  const [show, setShow] = useState(false);
  const [since, setSince] = useState(0);
  const [, tick] = useState(0);
  useEffect(() => {
    let timer: ReturnType<typeof setTimeout> | undefined;
    const unsubscribe = serverWake.subscribe((waking) => {
      clearTimeout(timer);
      if (waking) { setSince(Date.now()); timer = setTimeout(() => setShow(true), 1200); }
      else setShow(false);
    });
    return () => { clearTimeout(timer); unsubscribe(); };
  }, []);
  useEffect(() => {
    if (!show) return;
    const id = setInterval(() => tick((n) => n + 1), 1000);
    return () => clearInterval(id);
  }, [show]);
  const secs = Math.max(0, Math.round((Date.now() - since) / 1000));
  if (!show) return null;
  return (
    <div role="status" aria-live="polite"
      className="fixed inset-x-0 top-0 z-[60] flex items-center justify-center gap-2 bg-marigold-soft px-4 py-2 text-center text-[13px] text-marigold-ink shadow-sm">
      <Loader2 className="size-4 shrink-0 animate-spin" />
      <span><strong>Waking up the server.</strong> It sleeps when nobody has used it for a while; this usually takes 2–3 minutes ({secs}s so far). Keep this page open; you&apos;ll be signed in as soon as it&apos;s ready.</span>
    </div>
  );
}
