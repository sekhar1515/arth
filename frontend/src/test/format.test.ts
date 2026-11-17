import { initials, inr, merchantName, signed } from "@/lib/format";
import { presetRange } from "@/components/finance/date-range";
import { txn } from "./fixtures";

describe("money formatting", () => {
  it("uses Indian digit grouping", () => {
    expect(inr(24500)).toBe("₹24,500");
    expect(inr(1234567)).toBe("₹12,34,567");
    expect(inr(450.5, { paise: true })).toBe("₹450.50");
    expect(inr(null)).toBe("—");
  });

  it("signs amounts by direction", () => {
    expect(signed(450, "DEBIT")).toBe("−₹450");
    expect(signed(85000, "CREDIT")).toBe("+₹85,000");
    expect(signed(12.5, "DEBIT")).toBe("−₹12.50");
  });

  it("names merchants, transfers and unknowns", () => {
    expect(merchantName(txn())).toBe("Swiggy");
    expect(merchantName(txn({ merchant: null, counterparty: null, kind: "TRANSFER", description: null }))).toBe("Transfer out");
    expect(initials("Bharat Petroleum")).toBe("BP");
    expect(initials("Swiggy")).toBe("SW");
  });

  it("financial-year preset starts on 1 April", () => {
    expect(presetRange("year").from.endsWith("-04-01")).toBe(true);
    expect(presetRange("this-month").from.endsWith("-01")).toBe(true);
  });
});
