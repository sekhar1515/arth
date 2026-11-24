import { act, fireEvent, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { presetRange } from "@/components/finance/date-range";
import { netWorthMoves } from "@/components/finance/networth-moves";
import DashboardPage from "@/app/(app)/dashboard/page";
import { accounts, categories, summary } from "./fixtures";
import { renderWithQuery } from "./render";

vi.mock("next/navigation", () => ({ usePathname: () => "/dashboard", useRouter: () => ({ push: vi.fn(), replace: vi.fn() }) }));

const p = (date: string, netWorth: number) => ({ date, assets: netWorth, liabilities: 0, netWorth });

describe("month rollover", () => {
  afterEach(() => { vi.useRealTimers(); vi.unstubAllGlobals(); });

  it("'This month' starts on the 1st of the new month", () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date("2026-11-30T23:50:00+05:30"));
    expect(presetRange("this-month")).toMatchObject({ from: "2026-11-01", to: "2026-11-30" });
    vi.setSystemTime(new Date("2026-12-01T00:05:00+05:30"));
    expect(presetRange("this-month")).toMatchObject({ from: "2026-12-01", to: "2026-12-01" });
  });

  it("net worth 'This month' resets on the 1st, measured from the end of last month", () => {
    const history = [p("2026-11-29", 100), p("2026-11-30", 120)];
    const before = netWorthMoves(history, 125, "2026-11-30");
    expect(before.thisMonth).toMatchObject({ key: "2026-11" });
    const after = netWorthMoves(history, 130, "2026-12-01");
    expect(after.thisMonth).toMatchObject({ key: "2026-12", change: 10, current: true });
    expect(after.today).toMatchObject({ key: "2026-12-01", change: 10 });
  });
});

describe("dashboard add transaction", () => {
  afterEach(() => vi.unstubAllGlobals());

  function stubApi(sum: typeof summary) {
    const calls: string[] = [];
    vi.stubGlobal("fetch", vi.fn(async (url: string) => {
      calls.push(url);
      const body = url.startsWith("/api/dashboard/summary") ? sum
        : url.startsWith("/api/accounts") ? accounts
        : url.startsWith("/api/categories") ? categories
        : url.startsWith("/api/auth/me") ? { id: "u", displayName: "Meher Test", email: "m@x.in", demo: false }
        : [];
      return new Response(JSON.stringify(body), { status: 200, headers: { "Content-Type": "application/json" } });
    }));
    return calls;
  }

  it("the + on the empty dashboard opens the add-transaction form", async () => {
    stubApi({ ...summary, income: 0, expenses: 0, accounts: { ...summary.accounts, accounts: summary.accounts.accounts.map((a) => ({ ...a, transactionCount: 0 })) } });
    renderWithQuery(<DashboardPage />);
    const plus = await screen.findByRole("button", { name: "Add a transaction" });
    await waitFor(() => expect(plus).toBeEnabled());
    await act(async () => { fireEvent.click(plus); });
    expect(await screen.findByText("Add a transaction", { selector: "h2, [role=dialog] *" })).toBeInTheDocument();
  });

  it("the header button opens it too", async () => {
    stubApi(summary);
    renderWithQuery(<DashboardPage />);
    const btn = await screen.findByRole("button", { name: /Add transaction/ });
    await waitFor(() => expect(btn).toBeEnabled());
    await act(async () => { fireEvent.click(btn); });
    expect(await screen.findByRole("dialog")).toBeInTheDocument();
  });
});
