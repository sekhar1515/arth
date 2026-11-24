import type { ApiErrorBody } from "@/types/api";

/** Error carrying the backend's error envelope. */
export class ApiError extends Error {
  constructor(public status: number, public code: string, message: string, public details: string[] = []) {
    super(message);
  }
}

type Query = Record<string, string | number | boolean | null | undefined>;

export function qs(params: Query = {}): string {
  const sp = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) {
    if (v === undefined || v === null || v === "") continue;
    sp.set(k, String(v));
  }
  const s = sp.toString();
  return s ? `?${s}` : "";
}

/**
 * The API runs on a free host that sleeps when nobody has used it for a while; the first request after that waits
 * while it wakes (up to ~2 minutes), and the proxy in front may give up early with 502/503/504 or drop the
 * connection. Requests that are safe to repeat (reads, signing in/out) are retried until the server answers, and
 * {@link serverWake} tells the UI so it can say what's going on instead of looking stuck.
 */
const WAKE_STATUSES = new Set([502, 503, 504]);
const WAKE_DEADLINE_MS = 150_000;
const RETRYABLE_POSTS = new Set(["/auth/login", "/auth/logout"]);

type WakeListener = (waking: boolean) => void;
const wakeListeners = new Set<WakeListener>();
let waking = false;
function setWaking(v: boolean) {
  if (waking === v) return;
  waking = v;
  wakeListeners.forEach((l) => l(v));
}
export const serverWake = {
  get waking() { return waking; },
  subscribe(l: WakeListener) { wakeListeners.add(l); return () => { wakeListeners.delete(l); }; },
};

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

async function send(method: string, path: string, body: unknown, init?: RequestInit): Promise<Response | null> {
  const isForm = typeof FormData !== "undefined" && body instanceof FormData;
  try {
    return await fetch(`/api${path}`, {
      method,
      credentials: "same-origin",
      headers: body !== undefined && !isForm ? { "Content-Type": "application/json" } : undefined,
      body: body === undefined ? undefined : isForm ? (body as FormData) : JSON.stringify(body),
      cache: "no-store",
      ...init,
    });
  } catch (e) {
    if (init?.signal?.aborted) throw e;
    return null; // network error: the server didn't answer (often still waking)
  }
}

/** A 502/503/504 or dropped connection that came from the host/proxy, not an error answered by Arth itself. */
async function isWakeFailure(res: Response | null): Promise<boolean> {
  if (res === null) return true;
  if (!WAKE_STATUSES.has(res.status)) return false;
  const text = await res.clone().text().catch(() => "");
  return !(safeJson(text) as ApiErrorBody | undefined)?.error;
}

async function request<T>(method: string, path: string, body?: unknown, init?: RequestInit): Promise<T> {
  const retryable = method === "GET" || RETRYABLE_POSTS.has(path);
  const started = Date.now();
  let res: Response;
  for (let attempt = 0; ; attempt++) {
    const r = await send(method, path, body, init);
    if (r !== null && !(await isWakeFailure(r))) { res = r; break; }
    if (!retryable || Date.now() - started > WAKE_DEADLINE_MS) {
      setWaking(false);
      throw new ApiError(503, "SERVER_WAKING", retryable
        ? "The server is taking too long to wake up. Try again in a minute."
        : "The server was waking up, so this may not have been saved. Check, then try again.");
    }
    setWaking(true);
    await sleep(Math.min(2000 + attempt * 2000, 10_000));
  }
  setWaking(false);
  if (res.status === 204) return undefined as T;
  const text = await res.text();
  const data = text ? safeJson(text) : undefined;
  if (!res.ok) {
    const err = (data as ApiErrorBody | undefined)?.error;
    if (res.status === 401 && typeof window !== "undefined" && !path.startsWith("/auth/")) {
      window.location.href = "/login";
    }
    throw new ApiError(res.status, err?.code ?? "HTTP_" + res.status, err?.message ?? "Request failed. Check that the API is running.", err?.details ?? []);
  }
  return data as T;
}

function safeJson(text: string): unknown {
  try {
    return JSON.parse(text);
  } catch {
    return undefined;
  }
}

export const api = {
  get: <T>(path: string, query?: Query) => request<T>("GET", path + qs(query)),
  post: <T>(path: string, body?: unknown) => request<T>("POST", path, body),
  put: <T>(path: string, body?: unknown) => request<T>("PUT", path, body),
  del: <T>(path: string) => request<T>("DELETE", path),
};
