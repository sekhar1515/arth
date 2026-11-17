"use client";

import { useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import type { AnalyticsReport, AppNotification, BudgetOverview, CardOverview, Goal, NotificationPrefs, RecurringSummary } from "@/types/api";

export const useBudgets = () => useQuery({ queryKey: ["budgets"], queryFn: () => api.get<BudgetOverview>("/budgets") });
export const useRecurring = () => useQuery({ queryKey: ["recurring"], queryFn: () => api.get<RecurringSummary>("/recurring") });
export const useCards = () => useQuery({ queryKey: ["cards"], queryFn: () => api.get<CardOverview>("/credit-cards") });
export const useGoals = () => useQuery({ queryKey: ["goals"], queryFn: () => api.get<Goal[]>("/goals") });
export const useAnalytics = (month: string | null) =>
  useQuery({ queryKey: ["analytics", month], queryFn: () => api.get<AnalyticsReport>("/analytics/report", { month: month ?? undefined }), placeholderData: (p) => p });
export const useNotifications = (unread = false) =>
  useQuery({ queryKey: ["notifications", unread], queryFn: () => api.get<AppNotification[]>("/notifications", { unread, limit: 100 }) });
export const useUnreadCount = () =>
  useQuery({ queryKey: ["notifications-unread"], queryFn: () => api.get<{ count: number }>("/notifications/unread-count"), refetchInterval: 60_000 });
export const useNotificationPrefs = () => useQuery({ queryKey: ["notification-prefs"], queryFn: () => api.get<NotificationPrefs>("/notifications/preferences") });

/** Planning views depend on transactions too, so they refresh together. */
export function useInvalidatePlanning() {
  const qc = useQueryClient();
  return () => {
    for (const k of ["budgets", "recurring", "cards", "goals", "analytics", "notifications", "notifications-unread", "summary", "accounts", "holdings", "networth"]) {
      qc.invalidateQueries({ queryKey: [k] });
    }
  };
}
