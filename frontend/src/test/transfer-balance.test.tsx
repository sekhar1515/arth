import { fireEvent, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ManualTransactionDialog } from "@/components/finance/dialogs";
import type { Account } from "@/types/api";
import { accounts } from "./fixtures";
import { renderWithQuery } from "./render";

const sbi: Account = { ...accounts[0], id: "sbi", name: "SBI Savings", last4: "3307" };

function stub() {
  const calls: { url: string; body: unknown }[] = [];
  vi.stubGlobal("fetch", vi.fn(async (url: string, init?: RequestInit) => {
    calls.push({ url, body: init?.body ? JSON.parse(String(init.body)) : undefined });
    return new Response(JSON.stringify({ matchedExisting: false }), { status: 201, headers: { "Content-Type": "application/json" } });
  }));
  return calls;
}

describe("transfer between own accounts", () => {
  afterEach(() => vi.unstubAllGlobals());

  it("records both sides in one request", async () => {
    const calls = stub();
    renderWithQuery(<ManualTransactionDialog accounts={[...accounts, sbi]} categories={[]} open onOpenChange={() => undefined} />);
    fireEvent.click(screen.getByRole("radio", { name: "Transfer" }));
    fireEvent.change(screen.getByLabelText("Amount (₹)"), { target: { value: "5000" } });
    expect(screen.getByText(/to SBI Savings/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Record transfer" }));
    await waitFor(() => expect(calls.some((c) => c.url === "/api/transactions/transfer")).toBe(true));
    expect(calls.find((c) => c.url === "/api/transactions/transfer")!.body).toMatchObject({ fromAccountId: "hdfc", toAccountId: "sbi", amount: "5000" });
  });

  it("asks for a second account when there's only one", () => {
    stub();
    renderWithQuery(<ManualTransactionDialog accounts={accounts} categories={[]} open onOpenChange={() => undefined} />);
    fireEvent.click(screen.getByRole("radio", { name: "Transfer" }));
    expect(screen.getByText(/Add a second account first/)).toBeInTheDocument();
  });
});
