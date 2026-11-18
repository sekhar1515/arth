"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { api, ApiError } from "@/lib/api";
import { Button } from "@/components/ui/button";
import { Field, Input } from "@/components/ui/input";

export default function LoginPage() {
  const router = useRouter();
  const qc = useQueryClient();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [demo, setDemo] = useState<{ email: string; password: string } | null>(null);
  const [needCode, setNeedCode] = useState(false);
  const [code, setCode] = useState("");

  useEffect(() => {
    api.get<{ enabled: boolean; email?: string; password?: string }>("/auth/demo-info")
      .then((d) => d.enabled && d.email && setDemo({ email: d.email, password: d.password ?? "" }))
      .catch(() => undefined);
  }, []);

  async function submit(e: React.FormEvent, creds?: { email: string; password: string }) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await api.post("/auth/login", creds ?? { email, password, code: needCode ? code : undefined });
      qc.clear();
      router.replace("/dashboard");
    } catch (err) {
      if (err instanceof ApiError && err.code === "MFA_REQUIRED") {
        setNeedCode(true);
        setError(null);
      } else {
        setError(err instanceof ApiError ? err.message : "Couldn't reach the server. Check that the API is running.");
      }
      setBusy(false);
    }
  }

  return (
    <div className="w-full max-w-sm">
      <h1 className="font-display text-3xl font-semibold tracking-tight">Sign in</h1>
      <p className="mt-1 text-sm text-ink-2">Welcome back. Your review inbox is waiting.</p>
      <form onSubmit={submit} className="mt-8 space-y-4">
        <Field label="E-mail" htmlFor="email">
          <Input id="email" type="email" autoComplete="email" required value={email} onChange={(e) => setEmail(e.target.value)} />
        </Field>
        <Field label="Password" htmlFor="password">
          <Input id="password" type="password" autoComplete="current-password" required value={password} onChange={(e) => setPassword(e.target.value)} />
        </Field>
        <div className="-mt-2 text-right text-sm">
          <Link href={email ? `/forgot-password?email=${encodeURIComponent(email)}` : "/forgot-password"} className="font-medium text-peacock hover:underline">
            Forgot password?
          </Link>
        </div>
        {needCode && (
          <Field label="Code from your authenticator app" htmlFor="code">
            <Input id="code" autoComplete="one-time-code" inputMode="text" autoFocus required value={code} onChange={(e) => setCode(e.target.value)} placeholder="123456 or a recovery code" />
          </Field>
        )}
        {error && <p role="alert" className="text-sm text-out">{error}</p>}
        <Button type="submit" size="lg" className="w-full" disabled={busy}>
          {busy ? "Signing in…" : "Sign in"}
        </Button>
      </form>
      {demo && (
        <div className="mt-6 rounded-2xl border border-marigold/40 bg-marigold-soft p-4 text-sm text-marigold-ink">
          <p>This server runs in demo mode with synthetic sample data.</p>
          <Button variant="outline" size="sm" className="mt-3" disabled={busy} onClick={(e) => submit(e, demo)}>
            Explore the demo account
          </Button>
        </div>
      )}
      <p className="mt-8 text-sm text-ink-2">
        New here?{" "}
        <Link href="/register" className="font-semibold text-peacock hover:underline">
          Create an account
        </Link>
      </p>
    </div>
  );
}
