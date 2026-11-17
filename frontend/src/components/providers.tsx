"use client";

import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { Toaster } from "sonner";
import { useState } from "react";
import { ApiError } from "@/lib/api";

export function Providers({ children }: { children: React.ReactNode }) {
  const [client] = useState(
    () =>
      new QueryClient({
        defaultOptions: {
          queries: {
            staleTime: 30_000,
            refetchOnWindowFocus: true,
            retry: (count, err) => !(err instanceof ApiError && err.status < 500) && count < 2,
          },
        },
      }),
  );
  return (
    <QueryClientProvider client={client}>
      {children}
      <Toaster position="bottom-center" toastOptions={{ className: "!rounded-2xl !border-line !bg-surface !text-ink" }} />
    </QueryClientProvider>
  );
}
