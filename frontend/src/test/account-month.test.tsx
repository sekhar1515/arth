import { fireEvent, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { AccountMonthDialog } from "@/components/finance/account-month-dialog";
import { txn } from "./fixtures";
import { renderWithQuery } from "./render";

vi.mock("next/navigation", () => ({ usePathname: () => "/accounts", useRouter: () => ({ push: vi.fn() }) }));

describe("account month view", () => {
  afterEach(() => { vi.useRealTimers(); vi.unstubAllGlobals(); });

  it("shows this month's transactions for the account, and moves to the previous month", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    vi.setSystemTime(new Date("2026-11-25T12:00:00+05:30"));
    const urls: string[] = [];
    vi.stubGlobal("fetch", vi.fn(async (url: string) => {
      urls.push(url);
      const body = url.startsWith("/api/transactions")
        ? { content: [txn({ id: "a", amount: 500, direction: "DEBIT" }), txn({ id: "b", amount: 2000, direction: "CREDIT" })], page: 0, size: 200, totalElements: 2, totalPages: 1 }
        : [];
      return new Response(JSON.stringify(body), { status: 200, headers: { "Content-Type": "application/json" } });
    }));
    renderWithQuery(<AccountMonthDialog accountId="acc-1" name="HDFC Savings" onClose={() => undefined} />);
    expect(await screen.findByText("November 2026")).toBeInTheDocument();
    await waitFor(() => expect(urls.some((u) => u.includes("accountId=acc-1") && u.includes("from=2026-11-01") && u.includes("to=2026-11-25"))).toBe(true));
    expect(await screen.findByText("₹500")).toBeInTheDocument();
    expect(screen.getByText("₹2,000")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Next month" })).toBeDisabled();

    fireEvent.click(screen.getByRole("button", { name: "Previous month" }));
    expect(await screen.findByText("October 2026")).toBeInTheDocument();
    await waitFor(() => expect(urls.some((u) => u.includes("from=2026-10-01") && u.includes("to=2026-10-31"))).toBe(true));
  });
});

describe("account month list", () => {
  it("shows the amount in red for money out and green for money in, with no Account column", async () => {
    const { TransactionTable } = await import("@/components/finance/transaction-list");
    const { render } = await import("@testing-library/react");
    render(<TransactionTable accountView onOpen={() => undefined}
      rows={[txn({ id: "d", amount: 500, direction: "DEBIT" }), txn({ id: "c", amount: 2000, direction: "CREDIT" })]} />);
    expect(screen.queryByRole("columnheader", { name: "Account" })).toBeNull();
    expect(screen.getByText("−₹500")).toHaveClass("text-out");
    expect(screen.getByText("+₹2,000")).toHaveClass("text-in");
  });
});
