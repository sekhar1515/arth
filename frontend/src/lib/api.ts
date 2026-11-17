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

async function request<T>(method: string, path: string, body?: unknown, init?: RequestInit): Promise<T> {
  const isForm = typeof FormData !== "undefined" && body instanceof FormData;
  const res = await fetch(`/api${path}`, {
    method,
    credentials: "same-origin",
    headers: body !== undefined && !isForm ? { "Content-Type": "application/json" } : undefined,
    body: body === undefined ? undefined : isForm ? (body as FormData) : JSON.stringify(body),
    cache: "no-store",
    ...init,
  });
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
