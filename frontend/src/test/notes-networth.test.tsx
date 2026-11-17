import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { TransactionApprovalCard } from "@/components/finance/approval-card";
import { MoneyFlow } from "@/components/finance/dashboard-parts";
import { HoldingDialog } from "@/components/finance/networth-dialogs";
import { accounts, categories, summary, txn } from "./fixtures";
import { renderWithQuery } from "./render";

describe("notes on the approval slip", () => {
  afterEach(() => vi.restoreAllMocks());

  it("shows your note and defaults to remembering it", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(
      new Response(JSON.stringify({ transaction: txn(), ruleSaved: true, appliedToOthers: 0 }), { status: 200 }),
    );
    renderWithQuery(<TransactionApprovalCard txn={txn({ notes: "sabzi", merchant: { id: "m2", name: "Raju" }, suggestedCategory: null })} categories={categories} accounts={accounts} />);
    expect(screen.getByText(/Your note:/)).toHaveTextContent("Your note: sabzi");
    await userEvent.click(screen.getByRole("option", { name: /Groceries/ }));
    expect(screen.getByLabelText(/Always when my note says "sabzi"/)).toBeChecked();
    await userEvent.click(screen.getByRole("button", { name: "Confirm Groceries" }));
    await waitFor(() => expect(fetchMock).toHaveBeenCalled());
    expect(JSON.parse(String((fetchMock.mock.calls[0][1] as RequestInit).body))).toMatchObject({ remember: "NOTE", descriptionPattern: "sabzi", categoryId: "groceries" });
  });

  it("without a note there is no note rule option", async () => {
    renderWithQuery(<TransactionApprovalCard txn={txn({ notes: null })} categories={categories} accounts={accounts} />);
    expect(screen.queryByText(/my note says/)).not.toBeInTheDocument();
    expect(screen.getByLabelText("Always for Swiggy")).toBeChecked();
  });
});

describe("investments on the dashboard", () => {
  it("separates spending from investing", () => {
    render(<MoneyFlow s={{ ...summary, expenses: 31250, invested: 10000, savings: 60750, savingsRate: 66 }} />);
    expect(screen.getByRole("heading")).toHaveTextContent("₹92,000 came in, ₹31,250 was spent and ₹10,000 invested, so you kept 66%.");
    expect(screen.getByText("Invested")).toBeInTheDocument();
    expect(screen.getByText("Left over")).toBeInTheDocument();
  });
});

describe("adding an asset", () => {
  it("asks what kind, then shows the right fields and a live value", async () => {
    renderWithQuery(<HoldingDialog open onOpenChange={() => undefined} />);
    expect(screen.getByRole("heading", { name: "What do you want to add?" })).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: /^Gold\s?Grams/ }));
    await userEvent.type(screen.getByLabelText("Grams"), "10");
    await userEvent.type(screen.getByLabelText("Today's rate per gram"), "7650");
    expect(screen.getByText("₹76,500")).toBeInTheDocument();
  });

  it("fixed deposits ask for rate and dates", async () => {
    renderWithQuery(<HoldingDialog open onOpenChange={() => undefined} />);
    await userEvent.click(screen.getByRole("button", { name: /Fixed deposit/ }));
    expect(screen.getByLabelText("Interest rate (% a year)")).toBeInTheDocument();
    expect(screen.getByLabelText("Maturity date")).toBeInTheDocument();
    expect(screen.getByLabelText("Interest compounds")).toHaveValue("4");
  });
});
