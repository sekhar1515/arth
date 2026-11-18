"use client";

import QRCode from "qrcode";
import { Copy, KeyRound, ShieldCheck, ShieldOff } from "lucide-react";
import { useEffect, useState } from "react";
import { toast } from "sonner";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent } from "@/components/ui/dialog";
import { Field, Input } from "@/components/ui/input";
import { Panel, Pill } from "@/components/ui/states";
import { api, ApiError } from "@/lib/api";

const err = (e: unknown) => (e instanceof ApiError ? e.message : "Something went wrong");

export function SecurityPanel({ demo }: { demo: boolean }) {
  const qc = useQueryClient();
  const status = useQuery({ queryKey: ["mfa"], queryFn: () => api.get<{ enabled: boolean; recoveryCodesLeft: number }>("/auth/mfa") });
  const [setup, setSetup] = useState(false);
  const [disable, setDisable] = useState(false);
  const [pw, setPw] = useState({ currentPassword: "", newPassword: "" });

  async function changePassword() {
    try {
      await api.post("/auth/password", pw);
      setPw({ currentPassword: "", newPassword: "" });
      toast.success("Password changed. Other devices have been signed out.");
    } catch (e) {
      toast.error(err(e));
    }
  }

  const s = status.data;
  return (
    <Panel title="Sign-in and security">
      <div className="flex flex-wrap items-center gap-3">
        {s?.enabled ? <ShieldCheck className="size-5 text-in" /> : <ShieldOff className="size-5 text-ink-3" />}
        <div className="min-w-0 flex-1">
          <p className="text-sm font-semibold">Two-step sign-in {s?.enabled ? <Pill tone="in">On</Pill> : <Pill>Off</Pill>}</p>
          <p className="text-[13px] text-ink-2">
            {s?.enabled ? `Sign-in asks for a code from your authenticator app. ${s.recoveryCodesLeft} recovery codes left.`
              : "Ask for a 6-digit code from an authenticator app (Google Authenticator, Microsoft Authenticator, 1Password…) after your password."}
          </p>
        </div>
        {s && (s.enabled
          ? <Button variant="outline" size="sm" onClick={() => setDisable(true)}>Turn off</Button>
          : <Button size="sm" onClick={() => setSetup(true)} disabled={demo}>Turn on</Button>)}
      </div>
      <div className="mt-6 border-t border-line pt-5">
        <p className="mb-3 text-sm font-semibold">Change password</p>
        <div className="grid gap-3 sm:grid-cols-[1fr_1fr_auto] sm:items-end">
          <Field label="Current password" htmlFor="pw-cur"><Input id="pw-cur" type="password" autoComplete="current-password" value={pw.currentPassword} onChange={(e) => setPw({ ...pw, currentPassword: e.target.value })} /></Field>
          <Field label="New password" htmlFor="pw-new"><Input id="pw-new" type="password" autoComplete="new-password" value={pw.newPassword} onChange={(e) => setPw({ ...pw, newPassword: e.target.value })} /></Field>
          <Button variant="outline" disabled={demo || !pw.currentPassword || pw.newPassword.length < 10} onClick={changePassword}><KeyRound /> Change</Button>
        </div>
        <p className="mt-1 text-xs text-ink-3">At least 10 characters with letters and numbers.</p>
      </div>
      {setup && <SetupDialog onClose={() => { setSetup(false); qc.invalidateQueries({ queryKey: ["mfa"] }); qc.invalidateQueries({ queryKey: ["me"] }); }} />}
      {disable && <DisableDialog onClose={() => { setDisable(false); qc.invalidateQueries({ queryKey: ["mfa"] }); qc.invalidateQueries({ queryKey: ["me"] }); }} />}
    </Panel>
  );
}

function SetupDialog({ onClose }: { onClose: () => void }) {
  const [secret, setSecret] = useState<{ secret: string; otpauthUri: string } | null>(null);
  const [qr, setQr] = useState<string | null>(null);
  const [code, setCode] = useState("");
  const [codes, setCodes] = useState<string[] | null>(null);
  useEffect(() => {
    api.post<{ secret: string; otpauthUri: string }>("/auth/mfa/setup").then(async (s) => {
      setSecret(s);
      setQr(await QRCode.toDataURL(s.otpauthUri, { margin: 1, width: 200 }));
    }).catch((e) => { toast.error(err(e)); onClose(); });
  }, [onClose]);
  async function enable() {
    try {
      const r = await api.post<{ recoveryCodes: string[] }>("/auth/mfa/enable", { code });
      setCodes(r.recoveryCodes);
    } catch (e) {
      toast.error(err(e));
    }
  }
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent title={codes ? "Save your recovery codes" : "Turn on two-step sign-in"}>
        {codes ? (
          <div className="space-y-4 text-sm">
            <p className="text-ink-2">Each code works once if you lose your phone. They won&apos;t be shown again, so keep them somewhere safe (a password manager is ideal).</p>
            <ul className="grid grid-cols-2 gap-2 rounded-2xl bg-surface-2 p-4 font-mono text-[13px]">{codes.map((c) => <li key={c}>{c}</li>)}</ul>
            <div className="flex gap-2">
              <Button variant="outline" onClick={() => { navigator.clipboard.writeText(codes.join("\n")); toast.success("Copied"); }}><Copy /> Copy</Button>
              <Button className="flex-1" onClick={onClose}>I&apos;ve saved them</Button>
            </div>
          </div>
        ) : (
          <div className="space-y-4 text-sm">
            <p className="text-ink-2">Scan this with your authenticator app, then enter the 6-digit code it shows.</p>
            <div className="grid place-items-center rounded-2xl bg-white p-3">{qr ? <img src={qr} alt="QR code for your authenticator app" width={200} height={200} /> : <div className="size-[200px] animate-pulse bg-surface-2" />}</div>
            {secret && <p className="text-center text-xs text-ink-3">Can&apos;t scan? Enter this key: <span className="select-all font-mono text-ink-2">{secret.secret.match(/.{1,4}/g)?.join(" ")}</span></p>}
            <Field label="Code from the app" htmlFor="mfa-code">
              <Input id="mfa-code" inputMode="numeric" autoComplete="one-time-code" maxLength={6} value={code} onChange={(e) => setCode(e.target.value.replace(/\D/g, ""))} />
            </Field>
            <Button className="w-full" disabled={code.length !== 6} onClick={enable}>Turn on</Button>
          </div>
        )}
      </DialogContent>
    </Dialog>
  );
}

function DisableDialog({ onClose }: { onClose: () => void }) {
  const [password, setPassword] = useState("");
  const [code, setCode] = useState("");
  async function go() {
    try {
      await api.post("/auth/mfa/disable", { password, code });
      toast.success("Two-step sign-in turned off");
      onClose();
    } catch (e) {
      toast.error(err(e));
    }
  }
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent title="Turn off two-step sign-in?" description="Your account will be protected by your password only.">
        <div className="space-y-4">
          <Field label="Password" htmlFor="d-pw"><Input id="d-pw" type="password" autoComplete="current-password" value={password} onChange={(e) => setPassword(e.target.value)} /></Field>
          <Field label="Code from your app, or a recovery code" htmlFor="d-code"><Input id="d-code" autoComplete="one-time-code" value={code} onChange={(e) => setCode(e.target.value)} /></Field>
          <Button variant="danger" className="w-full" disabled={!password || !code} onClick={go}>Turn off</Button>
        </div>
      </DialogContent>
    </Dialog>
  );
}
