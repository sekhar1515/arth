import { fireEvent, render, screen } from "@testing-library/react";
import { useState } from "react";
import { describe, expect, it } from "vitest";
import { AmountInput } from "@/components/ui/amount-input";
import { evaluate, isExpression } from "@/lib/calc";

describe("evaluate", () => {
  it("does sums with the usual order", () => {
    expect(evaluate("450+120*2")).toBe(690);
    expect(evaluate("(1200-200)/4")).toBe(250);
    expect(evaluate("1,250 × 3 ÷ 2")).toBe(1875);
    expect(evaluate("100−25.5")).toBe(74.5);
    expect(evaluate("10/3")).toBe(3.33);
  });
  it("returns null for incomplete or unsafe input", () => {
    expect(evaluate("450+")).toBeNull();
    expect(evaluate("5/0")).toBeNull();
    expect(evaluate("alert(1)")).toBeNull();
    expect(evaluate("(2+3")).toBeNull();
  });
  it("tells sums from plain numbers", () => {
    expect(isExpression("450")).toBe(false);
    expect(isExpression("-450")).toBe(false);
    expect(isExpression("450+1")).toBe(true);
  });
});

function Harness() {
  const [v, setV] = useState("");
  return <><AmountInput aria-label="Amount" value={v} onValueChange={setV} /><output data-testid="saved">{v}</output></>;
}

describe("AmountInput", () => {
  it("saves the answer of a typed sum and fills it in on leaving the box", () => {
    render(<Harness />);
    const box = screen.getByLabelText("Amount");
    fireEvent.change(box, { target: { value: "450+120*2" } });
    expect(screen.getByTestId("saved")).toHaveTextContent("690");
    expect(screen.getByText("= ₹690")).toBeInTheDocument();
    fireEvent.blur(box);
    expect(box).toHaveValue("690");
  });
  it("works from the keypad", () => {
    render(<Harness />);
    fireEvent.click(screen.getByRole("button", { name: "Calculator" }));
    for (const k of ["1", "2", "0", "+", "8", "0", "="]) fireEvent.click(screen.getByRole("button", { name: k }));
    expect(screen.getByLabelText("Amount")).toHaveValue("200");
    expect(screen.getByTestId("saved")).toHaveTextContent("200");
  });
});
