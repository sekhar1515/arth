import { render, screen } from "@testing-library/react";
import { CategoryBreakdown, MoneyFlow } from "@/components/finance/dashboard-parts";
import { summary } from "./fixtures";

describe("dashboard", () => {
  it("money flow states income, spending and what was kept", () => {
    render(<MoneyFlow s={summary} />);
    const heading = screen.getByRole("heading");
    expect(heading).toHaveTextContent("₹92,000 came in and ₹31,250 went out, so you kept 66%.");
    expect(screen.getByText("Not categorised yet")).toBeInTheDocument();
    expect(screen.getByText(/moved between your own accounts and is not counted/)).toBeInTheDocument();
  });

  it("calls out overspending", () => {
    render(<MoneyFlow s={{ ...summary, income: 20000, expenses: 31250, savings: -11250, savingsRate: -56.3 }} />);
    expect(screen.getByRole("heading")).toHaveTextContent("₹11,250 more than you earned");
  });

  it("shows pending categorisation separately from categories", () => {
    render(<CategoryBreakdown slices={summary.expenseByCategory} pending={1250} total={31250} />);
    expect(screen.getByText("Pending categorisation")).toBeInTheDocument();
    expect(screen.getByText("Rent")).toBeInTheDocument();
    expect(screen.getByText("₹1,250")).toBeInTheDocument();
  });
});
