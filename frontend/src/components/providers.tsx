"use client";

import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { Toaster } from "sonner";
import { useState } from "react";
import { ApiError } from "@/lib/api";
import { WakeBanner } from "@/components/layout/wake-banner";

export function Providers({ children }: { children: React.ReactNode }) {
  const [client] = useState(
    () =>
      new QueryClient({
        defaultOptions: {
          queries: {
            staleTime: 30_000,
            refetchOnWindowFocus: true,
            // the API layer already waits out a sleeping server, so don't pile more retries on top of that
            retry: (count, err) => !(err instanceof ApiError && (err.status < 500 || err.code === "SERVER_WAKING")) && count < 2,
          },
        },
      }),
  );
  return (
    <QueryClientProvider client={client}>
      <WakeBanner />
      {children}
      <Toaster position="bottom-center" toastOptions={{ className: "!rounded-2xl !border-line !bg-surface !text-ink" }} />
    </QueryClientProvider>
  );
}
