"use client";

import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { Suspense, useState } from "react";
import { toast } from "sonner";
import { api, ApiError } from "@/lib/api";
import { Button } from "@/components/ui/button";
import { Field, Input } from "@/components/ui/input";

function ResetPassword() {
  const params = useSearchParams();
  const router = useRouter();
  const token = params.get("token") ?? "";
  const [password, setPassword] = useState("");
  const [confirm, setConfirm] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    if (password !== confirm) {
      setError("The two passwords don't match.");
      return;
    }
    setBusy(true);
    setError(null);
    try {
      await api.post("/auth/reset-password", { token, newPassword: password });
      toast.success("Password changed. Sign in with your new password.");
      router.replace("/login");
    } catch (err) {
      setError(err instanceof ApiError ? [err.message, ...err.details].join(". ") : "Couldn't reach the server.");
      setBusy(false);
    }
  }

  if (!token) {
    return (
      <div className="w-full max-w-sm">
        <h1 className="font-display text-3xl font-semibold tracking-tight">Link incomplete</h1>
        <p className="mt-2 text-sm text-ink-2">Open the link from your e-mail again, or ask for a new one.</p>
        <Link href="/forgot-password" className="mt-6 inline-block text-sm font-semibold text-peacock hover:underline">Get a new link</Link>
      </div>
    );
  }

  return (
    <div className="w-full max-w-sm">
      <h1 className="font-display text-3xl font-semibold tracking-tight">Choose a new password</h1>
      <p className="mt-1 text-sm text-ink-2">You&apos;ll be signed out everywhere else, then you can sign in with the new password.</p>
      <form onSubmit={submit} className="mt-8 space-y-4">
        <Field label="New password" htmlFor="password" hint="At least 10 characters, with letters and numbers.">
          <Input id="password" type="password" autoComplete="new-password" required minLength={10} maxLength={72} autoFocus
            value={password} onChange={(e) => setPassword(e.target.value)} />
        </Field>
        <Field label="Type it again" htmlFor="confirm">
          <Input id="confirm" type="password" autoComplete="new-password" required minLength={10} maxLength={72}
            value={confirm} onChange={(e) => setConfirm(e.target.value)} />
        </Field>
        {error && (
          <p role="alert" className="text-sm text-out">
            {error}{" "}
            {error.includes("expired") && <Link href="/forgot-password" className="font-semibold underline">Get a new link</Link>}
          </p>
        )}
        <Button type="submit" size="lg" className="w-full" disabled={busy}>{busy ? "Saving…" : "Save new password"}</Button>
      </form>
    </div>
  );
}

export default function ResetPasswordPage() {
  return (
    <Suspense>
      <ResetPassword />
    </Suspense>
  );
}
