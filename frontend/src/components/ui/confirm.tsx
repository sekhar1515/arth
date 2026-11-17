"use client";

import * as React from "react";
import { Button } from "./button";
import { Dialog, DialogContent } from "./dialog";

export interface ConfirmOptions {
  title: string;
  body?: React.ReactNode;
  confirmLabel?: string;
  danger?: boolean;
  /** Optional extra content (e.g. a checkbox); read its state in your own closure. */
  extra?: React.ReactNode;
}

/**
 * Promise-based confirmation dialog: `if (await confirm({...})) doIt()`.
 * Render `dialog` once in the component.
 */
export function useConfirm() {
  const [opts, setOpts] = React.useState<ConfirmOptions | null>(null);
  const resolver = React.useRef<((ok: boolean) => void) | null>(null);

  const confirm = React.useCallback((o: ConfirmOptions) => {
    setOpts(o);
    return new Promise<boolean>((resolve) => {
      resolver.current = resolve;
    });
  }, []);

  function close(ok: boolean) {
    resolver.current?.(ok);
    resolver.current = null;
    setOpts(null);
  }

  const dialog = (
    <Dialog open={!!opts} onOpenChange={(o) => !o && close(false)}>
      {opts && (
        <DialogContent title={opts.title}>
          {opts.body && <div className="text-sm text-ink-2">{opts.body}</div>}
          {opts.extra && <div className="mt-4">{opts.extra}</div>}
          <div className="mt-6 flex justify-end gap-2">
            <Button variant="ghost" onClick={() => close(false)}>Cancel</Button>
            <Button variant={opts.danger ? "danger" : "primary"} onClick={() => close(true)} autoFocus>
              {opts.confirmLabel ?? "Confirm"}
            </Button>
          </div>
        </DialogContent>
      )}
    </Dialog>
  );

  return { confirm, dialog };
}
