"use client";

import * as DialogPrimitive from "@radix-ui/react-dialog";
import { X } from "lucide-react";
import * as React from "react";
import { cn } from "@/lib/utils";

export const Dialog = DialogPrimitive.Root;
export const DialogTrigger = DialogPrimitive.Trigger;
export const DialogClose = DialogPrimitive.Close;

export function DialogContent({ className, children, title, description, wide }: {
  className?: string; children: React.ReactNode; title: string; description?: string; wide?: boolean;
}) {
  return (
    <DialogPrimitive.Portal>
      <DialogPrimitive.Overlay className="fixed inset-0 z-50 bg-[#06191b]/45 backdrop-blur-[2px]" />
      <DialogPrimitive.Content
        className={cn(
          "fixed inset-x-0 bottom-0 z-50 max-h-[92dvh] overflow-y-auto rounded-t-3xl border border-line bg-surface p-5 shadow-lift sm:inset-auto sm:left-1/2 sm:top-1/2 sm:w-full sm:-translate-x-1/2 sm:-translate-y-1/2 sm:rounded-3xl sm:p-6",
          wide ? "sm:max-w-2xl" : "sm:max-w-md",
          className,
        )}
      >
        <div className="mb-4 flex items-start justify-between gap-4">
          <div>
            <DialogPrimitive.Title className="font-display text-lg font-semibold text-ink">{title}</DialogPrimitive.Title>
            {description ? (
              <DialogPrimitive.Description className="mt-1 text-sm text-ink-2">{description}</DialogPrimitive.Description>
            ) : (
              <DialogPrimitive.Description className="sr-only">{title}</DialogPrimitive.Description>
            )}
          </div>
          <DialogPrimitive.Close className="rounded-full p-1.5 text-ink-3 hover:bg-surface-2 hover:text-ink" aria-label="Close">
            <X className="size-4" />
          </DialogPrimitive.Close>
        </div>
        {children}
      </DialogPrimitive.Content>
    </DialogPrimitive.Portal>
  );
}
