import { describe, expect, it, vi, afterEach } from "vitest";
import { netWorthMoves } from "@/components/finance/networth-moves";

const p = (date: string, netWorth: number) => ({ date, assets: netWorth, liabilities: 0, netWorth });

describe("netWorthMoves", () => {
  afterEach(() => vi.useRealTimers());

  it("works out the change for each day and each month, with today's live figure", () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date("2026-11-24T10:00:00+05:30"));
    const m = netWorthMoves([p("2026-10-30", 100), p("2026-10-31", 110), p("2026-11-22", 150), p("2026-11-23", 140), p("2026-11-24", 999)], 145);

    expect(m.daily[0]).toMatchObject({ key: "2026-11-24", change: 5, netWorth: 145, gapDays: 1 });
    expect(m.daily[1]).toMatchObject({ key: "2026-11-23", change: -10 });
    expect(m.daily[2]).toMatchObject({ key: "2026-11-22", change: 40, gapDays: 22 });
    expect(m.today?.change).toBe(5);

    // November: month-end of October (110) to today (145)
    expect(m.monthly[0]).toMatchObject({ key: "2026-11", change: 35, current: true });
    // October: first month saved, so from its first day
    expect(m.monthly[1]).toMatchObject({ key: "2026-10", change: 10, from: "2026-10-30" });
    expect(m.thisMonth?.change).toBe(35);
  });

  it("has nothing to show with a single day", () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date("2026-11-24T10:00:00+05:30"));
    const m = netWorthMoves([], 500);
    expect(m.daily).toEqual([]);
    expect(m.monthly).toEqual([]);
    expect(m.today).toBeNull();
  });
});

import { portfolioMoves } from "@/components/finance/networth-moves";

describe("portfolioMoves", () => {
  const p = (date: string, value: number, gain: number) => ({ date, value, invested: value - gain, gain });

  it("counts market movement, not money added by a SIP", () => {
    // 30 Oct: worth 1,00,000 (gain 10,000). 31 Oct: up 1,000. 5 Nov: SIP of 5,000 added, market flat
    const m = portfolioMoves([p("2026-10-30", 100000, 10000), p("2026-10-31", 101000, 11000), p("2026-11-05", 106000, 11000)],
      { value: 106500, gain: 11500 }, "2026-11-06");
    expect(m.today).toMatchObject({ key: "2026-11-06", change: 500, netWorth: 106500 });
    expect(m.daily[1]).toMatchObject({ key: "2026-11-05", change: 0 });
    expect(m.thisMonth).toMatchObject({ key: "2026-11", change: 500, current: true });
    expect(m.monthly[1]).toMatchObject({ key: "2026-10", change: 1000, from: "2026-10-30" });
  });
});
