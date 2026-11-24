"use client";

import { useEffect, useState } from "react";
import { isoDay } from "@/lib/format";

/**
 * Today's date in IST (YYYY-MM-DD), kept current while the app stays open: re-checked every minute and whenever the
 * tab comes back into view, so "this month" figures roll over at midnight on the 1st without a reload.
 */
export function useToday(): string {
  const [today, setToday] = useState(() => isoDay(new Date()));
  useEffect(() => {
    const check = () => setToday((prev) => {
      const now = isoDay(new Date());
      return now === prev ? prev : now;
    });
    const id = setInterval(check, 60_000);
    const onVisible = () => { if (document.visibilityState === "visible") check(); };
    document.addEventListener("visibilitychange", onVisible);
    window.addEventListener("focus", check);
    return () => {
      clearInterval(id);
      document.removeEventListener("visibilitychange", onVisible);
      window.removeEventListener("focus", check);
    };
  }, []);
  return today;
}
