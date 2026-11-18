"use client";

import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { Suspense, useEffect, useState } from "react";
import { MailCheck } from "lucide-react";
import { api, ApiError } from "@/lib/api";
import { Button } from "@/components/ui/button";
import { Field, Input } from "@/components/ui/input";

function ForgotPassword() {
  const params = useSearchParams();
  const [email, setEmail] = useState(params.get("email") ?? "");
  const [sent, setSent] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [emailAvailable, setEmailAvailable] = useState(true);

  useEffect(() => {
    api.get<{ emailAvailable: boolean }>("/auth/registration-info").then((r) => setEmailAvailable(r.emailAvailable)).catch(() => {});
  }, []);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await api.post("/auth/forgot-password", { email });
      setSent(true);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Couldn't reach the server.");
    } finally {
      setBusy(false);
    }
  }

  if (sent) {
    return (
      <div className="w-full max-w-sm">
        <MailCheck className="size-10 text-peacock" aria-hidden />
        <h1 className="mt-4 font-display text-3xl font-semibold tracking-tight">Check your e-mail</h1>
        <p className="mt-2 text-sm text-ink-2">
          If an account exists for <strong className="text-ink">{email}</strong>, we&apos;ve sent a link to reset your password. It works for 30 minutes.
          Check your spam folder if it doesn&apos;t arrive in a few minutes.
        </p>
        {!emailAvailable && (
          <p className="mt-4 rounded-2xl bg-marigold-soft p-3 text-sm text-marigold-ink">
            E-mail isn&apos;t set up on this server yet, so the link was written to the server log instead. Ask whoever runs this Arth to pass it on.
          </p>
        )}
        <Link href="/login" className="mt-8 inline-block text-sm font-semibold text-peacock hover:underline">Back to sign in</Link>
      </div>
    );
  }

  return (
    <div className="w-full max-w-sm">
      <h1 className="font-display text-3xl font-semibold tracking-tight">Forgot your password?</h1>
      <p className="mt-1 text-sm text-ink-2">Enter your e-mail and we&apos;ll send you a link to choose a new one.</p>
      <form onSubmit={submit} className="mt-8 space-y-4">
        <Field label="E-mail" htmlFor="email">
          <Input id="email" type="email" autoComplete="email" required autoFocus value={email} onChange={(e) => setEmail(e.target.value)} />
        </Field>
        {error && <p role="alert" className="text-sm text-out">{error}</p>}
        <Button type="submit" size="lg" className="w-full" disabled={busy}>{busy ? "Sending…" : "Send reset link"}</Button>
      </form>
      <p className="mt-8 text-sm text-ink-2">
        Remembered it? <Link href="/login" className="font-semibold text-peacock hover:underline">Sign in</Link>
      </p>
    </div>
  );
}

export default function ForgotPasswordPage() {
  return (
    <Suspense>
      <ForgotPassword />
    </Suspense>
  );
}
