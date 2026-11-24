import { afterEach, describe, expect, it, vi } from "vitest";
import { api, ApiError, serverWake } from "@/lib/api";

const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

describe("sleeping server", () => {
  afterEach(() => { vi.useRealTimers(); vi.unstubAllGlobals(); });

  it("waits while the server wakes up, then signs in", async () => {
    vi.useFakeTimers();
    const fetchMock = vi.fn()
      .mockRejectedValueOnce(new TypeError("Failed to fetch"))
      .mockResolvedValueOnce(new Response("An error occurred with your deployment", { status: 504 }))
      .mockResolvedValueOnce(json({ ok: true }));
    vi.stubGlobal("fetch", fetchMock);
    const states: boolean[] = [];
    const off = serverWake.subscribe((w) => states.push(w));
    const p = api.post("/auth/login", { email: "a@b.in", password: "x" });
    await vi.runAllTimersAsync();
    await expect(p).resolves.toEqual({ ok: true });
    expect(fetchMock).toHaveBeenCalledTimes(3);
    expect(states).toEqual([true, false]);
    off();
  });

  it("doesn't repeat a change that may have gone through", async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response("Bad gateway", { status: 502 }));
    vi.stubGlobal("fetch", fetchMock);
    const err = await api.post("/transactions", { amount: 1 }).catch((e) => e);
    expect(err).toBeInstanceOf(ApiError);
    expect((err as ApiError).code).toBe("SERVER_WAKING");
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it("passes on real errors from the app straight away", async () => {
    const fetchMock = vi.fn().mockResolvedValue(json({ error: { code: "INVALID_CREDENTIALS", message: "E-mail or password is incorrect", details: [] } }, 401));
    vi.stubGlobal("fetch", fetchMock);
    const err = await api.post("/auth/login", {}).catch((e) => e);
    expect((err as ApiError).message).toBe("E-mail or password is incorrect");
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });
});
