"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import type {
  Account, CatalogueEntry, Category, DashboardSummary, DayPoint, Holding, ImportResult, Liability, MonthPoint, NetWorthPoint,
  NetWorthSummary, Page, Preferences, RememberScope, SyncRecord, Transaction, TransactionDetail, User,
} from "@/types/api";

export const keys = {
  me: ["me"] as const,
  accounts: ["accounts"] as const,
  categories: (archived = false) => ["categories", archived] as const,
  transactions: (f: object) => ["transactions", f] as const,
  transaction: (id: string) => ["transaction", id] as const,
  review: (f: object) => ["review", f] as const,
  reviewCount: ["review-count"] as const,
  summary: (from: string, to: string) => ["summary", from, to] as const,
  monthly: (n: number) => ["monthly", n] as const,
  daily: (from: string, to: string) => ["daily", from, to] as const,
  preferences: ["preferences"] as const,
  integrations: ["integrations"] as const,
  syncHistory: ["sync-history"] as const,
  rules: ["rules"] as const,
  netWorth: ["networth"] as const,
  netWorthHistory: (m: number) => ["networth-history", m] as const,
  holdings: ["holdings"] as const,
  liabilities: ["liabilities"] as const,
};

// re-checked every minute while open, so prices picked up in the background show up without a reload
export const useNetWorth = () => useQuery({ queryKey: keys.netWorth, queryFn: () => api.get<NetWorthSummary>("/networth"), refetchInterval: 60_000 });
export const useNetWorthHistory = (months = 12) =>
  useQuery({ queryKey: keys.netWorthHistory(months), queryFn: () => api.get<NetWorthPoint[]>("/networth/history", { months }) });
export const useHoldings = () => useQuery({ queryKey: keys.holdings, queryFn: () => api.get<Holding[]>("/networth/holdings"), refetchInterval: 60_000 });
export const useLiabilities = () => useQuery({ queryKey: keys.liabilities, queryFn: () => api.get<Liability[]>("/networth/liabilities") });

/** Any change to assets or loans refreshes every wealth view (and the dashboard's net worth). */
export function useInvalidateWealth() {
  const qc = useQueryClient();
  return () => {
    for (const k of ["networth", "networth-history", "holdings", "liabilities", "summary"]) qc.invalidateQueries({ queryKey: [k] });
  };
}

export const useMe = () => useQuery({ queryKey: keys.me, queryFn: () => api.get<User>("/auth/me"), retry: false });
export const useAccounts = () => useQuery({ queryKey: keys.accounts, queryFn: () => api.get<Account[]>("/accounts") });
export const useCategories = (includeArchived = false) =>
  useQuery({ queryKey: keys.categories(includeArchived), queryFn: () => api.get<Category[]>("/categories", { includeArchived }) });
export const usePreferences = () => useQuery({ queryKey: keys.preferences, queryFn: () => api.get<Preferences>("/preferences") });
export const useReviewCount = () =>
  useQuery({ queryKey: keys.reviewCount, queryFn: () => api.get<{ count: number }>("/review/count"), refetchInterval: 60_000 });

export type TxnFilters = Record<string, string | number | boolean | undefined>;

export const useTransactions = (filters: TxnFilters) =>
  useQuery({ queryKey: keys.transactions(filters), queryFn: () => api.get<Page<Transaction>>("/transactions", filters), placeholderData: (prev) => prev });

export const useTransaction = (id: string | null) =>
  useQuery({ queryKey: keys.transaction(id ?? ""), queryFn: () => api.get<TransactionDetail>(`/transactions/${id}`), enabled: !!id });

export const useReview = (filters: TxnFilters) =>
  useQuery({ queryKey: keys.review(filters), queryFn: () => api.get<Page<Transaction>>("/review", { size: 100, ...filters }) });

export const useSummary = (from: string, to: string) =>
  useQuery({ queryKey: keys.summary(from, to), queryFn: () => api.get<DashboardSummary>("/dashboard/summary", { from, to }) });
export const useMonthly = (months = 6) =>
  useQuery({ queryKey: keys.monthly(months), queryFn: () => api.get<MonthPoint[]>("/dashboard/monthly", { months }) });
export const useDaily = (from: string, to: string) =>
  useQuery({ queryKey: keys.daily(from, to), queryFn: () => api.get<DayPoint[]>("/dashboard/daily", { from, to }) });

export const useIntegrations = () => useQuery({ queryKey: keys.integrations, queryFn: () => api.get<CatalogueEntry[]>("/integrations") });
export const useSyncHistory = () => useQuery({ queryKey: keys.syncHistory, queryFn: () => api.get<SyncRecord[]>("/integrations/history") });

/** Anything that changes transactions invalidates every money view. */
export function useInvalidateMoney() {
  const qc = useQueryClient();
  return () => {
    for (const k of ["transactions", "transaction", "review", "review-count", "summary", "monthly", "daily", "accounts", "rules", "integrations", "sync-history", "networth", "networth-history", "budgets", "recurring", "cards", "analytics"]) {
      qc.invalidateQueries({ queryKey: [k] });
    }
  };
}

export interface CategoriseVars {
  id: string;
  categoryId: string;
  remember?: RememberScope;
  note?: string;
  business?: boolean;
  descriptionPattern?: string;
}

export function useCategorise() {
  const invalidate = useInvalidateMoney();
  return useMutation({
    mutationFn: ({ id, ...body }: CategoriseVars) =>
      api.post<{ transaction: Transaction; ruleSaved: boolean; appliedToOthers: number }>(`/transactions/${id}/categorise`, body),
    onSuccess: invalidate,
  });
}

export function useBulkConfirm() {
  const invalidate = useInvalidateMoney();
  return useMutation({
    mutationFn: (ids: string[]) => api.post<{ confirmed: number; skipped: number }>("/review/bulk-confirm", { ids }),
    onSuccess: invalidate,
  });
}

export function useMarkTransfer() {
  const invalidate = useInvalidateMoney();
  return useMutation({
    mutationFn: ({ id, counterpartAccountId }: { id: string; counterpartAccountId?: string | null }) =>
      api.post<{ counterpartCreated: boolean }>(`/transactions/${id}/mark-transfer`, { counterpartAccountId: counterpartAccountId || null }),
    onSuccess: invalidate,
  });
}

export function useExclude() {
  const invalidate = useInvalidateMoney();
  return useMutation({
    mutationFn: ({ id, note }: { id: string; note?: string }) => api.post<Transaction>(`/transactions/${id}/exclude`, { note }),
    onSuccess: invalidate,
  });
}

export function useSplit() {
  const invalidate = useInvalidateMoney();
  return useMutation({
    mutationFn: ({ id, lines }: { id: string; lines: { categoryId: string; amount: string; note?: string }[] }) =>
      api.put<Transaction>(`/transactions/${id}/splits`, lines),
    onSuccess: invalidate,
  });
}

export function useResolveDuplicate() {
  const invalidate = useInvalidateMoney();
  return useMutation({
    mutationFn: ({ id, action }: { id: string; action: "KEEP_BOTH" | "MERGE" }) =>
      api.post<void>(`/review/duplicates/${id}/resolve`, { action }),
    onSuccess: invalidate,
  });
}

export function useUpdateTransaction() {
  const invalidate = useInvalidateMoney();
  return useMutation({
    mutationFn: ({ id, ...body }: { id: string } & Record<string, unknown>) => api.put<Transaction>(`/transactions/${id}`, body),
    onSuccess: invalidate,
  });
}

export function useCreateTransaction() {
  const invalidate = useInvalidateMoney();
  return useMutation({
    mutationFn: (body: Record<string, unknown>) => api.post<Transaction>("/transactions", body),
    onSuccess: invalidate,
  });
}

export function useImportCsv() {
  const invalidate = useInvalidateMoney();
  return useMutation({
    mutationFn: ({ accountId, file }: { accountId: string; file: File }) => {
      const fd = new FormData();
      fd.append("file", file);
      return api.post<ImportResult>(`/imports/csv?accountId=${encodeURIComponent(accountId)}`, fd);
    },
    onSuccess: invalidate,
  });
}
