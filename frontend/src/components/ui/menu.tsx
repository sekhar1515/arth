"use client";

import * as DropdownMenu from "@radix-ui/react-dropdown-menu";
import { cn } from "@/lib/utils";

/**
 * Action menu ("⋯"). Rendered in a portal so cards with overflow-hidden can't clip it; flips up near the bottom of the
 * screen, closes on outside tap or Esc, and works with the keyboard and screen readers.
 */
export function Menu({ trigger, children, align = "end" }: { trigger: React.ReactNode; children: React.ReactNode; align?: "start" | "end" }) {
  return (
    <DropdownMenu.Root modal={false}>
      <DropdownMenu.Trigger asChild>{trigger}</DropdownMenu.Trigger>
      <DropdownMenu.Portal>
        <DropdownMenu.Content
          align={align}
          sideOffset={6}
          collisionPadding={12}
          className="z-50 min-w-44 overflow-hidden rounded-2xl border border-line bg-surface py-1 text-sm shadow-lift"
        >
          {children}
        </DropdownMenu.Content>
      </DropdownMenu.Portal>
    </DropdownMenu.Root>
  );
}

export function MenuItem({ children, onSelect, danger }: { children: React.ReactNode; onSelect: () => void; danger?: boolean }) {
  return (
    <DropdownMenu.Item
      onSelect={onSelect}
      className={cn(
        "flex cursor-pointer select-none items-center gap-2 px-3 py-2.5 outline-none data-[highlighted]:bg-surface-2",
        danger ? "text-out" : "text-ink",
      )}
    >
      {children}
    </DropdownMenu.Item>
  );
}
