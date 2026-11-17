import { act, fireEvent, render, screen } from "@testing-library/react";
import { useState } from "react";
import { Meter } from "@/components/finance/meter";
import { useConfirm } from "@/components/ui/confirm";

describe("meter", () => {
  it("reports usage and turns red when over", () => {
    const { container } = render(<Meter pct={107} ticks={[50, 75, 90, 100]} label="Transport budget used" />);
    const m = screen.getByRole("meter", { name: "Transport budget used" });
    expect(m).toHaveAttribute("aria-valuenow", "107");
    expect(container.querySelector(".bg-out")).not.toBeNull();
    expect(container.querySelectorAll("[aria-hidden]").length).toBe(3); // 100% tick isn't drawn at the edge
  });

  it("uses marigold near the limit", () => {
    const { container } = render(<Meter pct={92} label="x" />);
    expect(container.querySelector(".bg-marigold")).not.toBeNull();
  });
});

function DeleteThing({ onDelete }: { onDelete: () => void }) {
  const { confirm, dialog } = useConfirm();
  const [asked, setAsked] = useState(false);
  return (
    <>
      <button onClick={async () => { setAsked(true); if (await confirm({ title: "Delete this?", confirmLabel: "Delete", danger: true })) onDelete(); }}>Remove</button>
      {asked && <span>asked</span>}
      {dialog}
    </>
  );
}

describe("confirm dialog", () => {
  it("only deletes after the user confirms", async () => {
    const onDelete = vi.fn();
    render(<DeleteThing onDelete={onDelete} />);
    fireEvent.click(screen.getByText("Remove"));
    expect(await screen.findByRole("heading", { name: "Delete this?" })).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Cancel" }));
    await act(async () => {});
    expect(onDelete).not.toHaveBeenCalled();
    fireEvent.click(screen.getByText("Remove"));
    fireEvent.click(await screen.findByRole("button", { name: "Delete" }));
    await act(async () => {});
    expect(onDelete).toHaveBeenCalledTimes(1);
  });
});
