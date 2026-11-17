import { fireEvent, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { TransactionApprovalCard } from "@/components/finance/approval-card";
import { accounts, categories, txn } from "./fixtures";
import { renderWithQuery } from "./render";

describe("TransactionApprovalCard", () => {
  afterEach(() => vi.restoreAllMocks());

  it("shows the transaction, the suggestion and preselects it", () => {
    renderWithQuery(<TransactionApprovalCard txn={txn()} categories={categories} accounts={accounts} />);
    expect(screen.getByRole("heading", { name: "Swiggy" })).toBeInTheDocument();
    expect(screen.getByText("−₹450")).toBeInTheDocument();
    expect(screen.getByText(/Suggested/)).toHaveTextContent("Food & Dining > Food Delivery");
    expect(screen.getByRole("button", { name: "Confirm Food Delivery" })).toBeEnabled();
    expect(screen.getByText("UPI via PhonePe")).toBeInTheDocument();
  });

  it("debits only offer spending categories", () => {
    renderWithQuery(<TransactionApprovalCard txn={txn()} categories={categories} accounts={accounts} />);
    expect(screen.queryByRole("option", { name: /Salary/ })).not.toBeInTheDocument();
    expect(screen.getByRole("option", { name: /Groceries/ })).toBeInTheDocument();
  });

  it("confirms with the chosen category and remember scope", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(
      new Response(JSON.stringify({ transaction: txn(), ruleSaved: true, appliedToOthers: 0 }), { status: 200 }),
    );
    const onDone = vi.fn();
    renderWithQuery(<TransactionApprovalCard txn={txn()} categories={categories} accounts={accounts} onDone={onDone} />);
    await userEvent.click(screen.getByRole("option", { name: /Groceries/ }));
    await userEvent.click(screen.getByText("Always for Swiggy"));
    await userEvent.click(screen.getByRole("button", { name: "Confirm Groceries" }));
    await waitFor(() => expect(fetchMock).toHaveBeenCalled());
    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe("/api/transactions/t1/categorise");
    expect(JSON.parse(String((init as RequestInit).body))).toMatchObject({ categoryId: "groceries", remember: "MERCHANT" });
    await waitFor(() => expect(onDone).toHaveBeenCalled(), { timeout: 1000 });
  });

  it("has no confirm action until a category is chosen", () => {
    renderWithQuery(<TransactionApprovalCard txn={txn({ suggestedCategory: null, suggestionReason: null, suggestionConfidence: null })} categories={categories} accounts={accounts} />);
    expect(screen.getByRole("button", { name: "Choose a category" })).toBeDisabled();
  });

  it("possible duplicates ask merge or keep, not a category", () => {
    renderWithQuery(<TransactionApprovalCard txn={txn({ duplicateStatus: "SUSPECTED" })} categories={categories} accounts={accounts} />);
    expect(screen.getByText(/already has/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /merge/i })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /keep both/i })).toBeInTheDocument();
    expect(screen.queryByRole("listbox", { name: "Category" })).not.toBeInTheDocument();
  });

  it("credit-card bill payments surface a transfer action", () => {
    renderWithQuery(<TransactionApprovalCard
      txn={txn({ merchant: null, suggestedCategory: null, suggestionReason: "Looks like a credit card bill payment: mark it as a transfer so it is not counted twice" })}
      categories={categories} accounts={accounts} />);
    expect(screen.getByRole("button", { name: /Mark as transfer/ })).toBeInTheDocument();
  });

  it("number keys pick categories when focused", () => {
    renderWithQuery(<TransactionApprovalCard txn={txn({ suggestedCategory: null })} categories={categories} accounts={accounts} autoFocus />);
    fireEvent.keyDown(window, { key: "2" });
    expect(screen.getByRole("button", { name: "Confirm Groceries" })).toBeInTheDocument();
  });
});
