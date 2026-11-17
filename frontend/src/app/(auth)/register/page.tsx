"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { api, ApiError } from "@/lib/api";
import { Button } from "@/components/ui/button";
import { Field, Input } from "@/components/ui/input";

export default function RegisterPage() {
  const router = useRouter();
  const qc = useQueryClient();
  const [form, setForm] = useState({ displayName: "", email: "", password: "", inviteCode: "" });
  const [mode, setMode] = useState<"OPEN" | "INVITE" | "CLOSED">("OPEN");
  useEffect(() => {
    api.get<{ mode: "OPEN" | "INVITE" | "CLOSED" }>("/auth/registration-info").then((r) => setMode(r.mode)).catch(() => {});
  }, []);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await api.post("/auth/register", { ...form, inviteCode: mode === "INVITE" ? form.inviteCode : undefined });
      qc.clear();
      router.replace("/accounts?welcome=1");
    } catch (err) {
      setError(err instanceof ApiError ? [err.message, ...err.details].join(". ") : "Couldn't reach the server.");
      setBusy(false);
    }
  }

  return (
    <div className="w-full max-w-sm">
      <h1 className="font-display text-3xl font-semibold tracking-tight">Create your account</h1>
      <p className="mt-1 text-sm text-ink-2">Takes a minute. Next you&apos;ll add your bank accounts.</p>
      {mode === "CLOSED" ? (
        <p role="status" className="mt-8 rounded-2xl bg-surface-2 p-4 text-sm text-ink-2">This server isn&apos;t accepting new accounts right now. Ask whoever runs it for access.</p>
      ) : (
      <form onSubmit={submit} className="mt-8 space-y-4">
        {mode === "INVITE" && (
          <Field label="Invite code" htmlFor="invite" hint="This server is invite-only.">
            <Input id="invite" required autoComplete="off" value={form.inviteCode} onChange={(e) => setForm({ ...form, inviteCode: e.target.value })} />
          </Field>
        )}
        <Field label="Your name" htmlFor="name">
          <Input id="name" required maxLength={100} value={form.displayName} onChange={(e) => setForm({ ...form, displayName: e.target.value })} />
        </Field>
        <Field label="E-mail" htmlFor="email">
          <Input id="email" type="email" autoComplete="email" required value={form.email} onChange={(e) => setForm({ ...form, email: e.target.value })} />
        </Field>
        <Field label="Password" htmlFor="password" hint="At least 10 characters, with letters and numbers.">
          <Input id="password" type="password" autoComplete="new-password" required minLength={10} maxLength={72}
            value={form.password} onChange={(e) => setForm({ ...form, password: e.target.value })} />
        </Field>
        {error && <p role="alert" className="text-sm text-out">{error}</p>}
        <Button type="submit" size="lg" className="w-full" disabled={busy}>
          {busy ? "Creating account…" : "Create account"}
        </Button>
      </form>
      )}
      <p className="mt-8 text-sm text-ink-2">
        Already have an account?{" "}
        <Link href="/login" className="font-semibold text-peacock hover:underline">Sign in</Link>
      </p>
    </div>
  );
}
