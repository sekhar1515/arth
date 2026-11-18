"use client";

import { BarChart3, ChevronDown, Inbox, LayoutDashboard, ListOrdered, LogOut, Menu, Moon, PiggyBank, Settings, Sun, TrendingUp, Wallet } from "lucide-react";
import { NotificationBell } from "./notification-bell";
import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { api } from "@/lib/api";
import { applyTheme } from "@/lib/theme";
import { cn } from "@/lib/utils";
import { useMe, useReviewCount } from "@/hooks/queries";
import { Dialog, DialogContent } from "@/components/ui/dialog";
import { Skeleton } from "@/components/ui/states";

type NavLink = { href: string; label: string };
type NavGroup = { label: string; icon: typeof LayoutDashboard; href?: string; items?: NavLink[] };

/** Seven simple sections. Related pages live together (e.g. Analytics, Reports and Ask Arth under Insights). */
export const NAV_GROUPS: NavGroup[] = [
  { label: "Home", icon: LayoutDashboard, href: "/dashboard" },
  {
    label: "Transactions", icon: ListOrdered,
    items: [
      { href: "/transactions", label: "All transactions" },
      { href: "/review", label: "Needs review" },
      { href: "/integrations", label: "Import statements" },
    ],
  },
  {
    label: "Money", icon: Wallet,
    items: [
      { href: "/accounts", label: "Accounts" },
      { href: "/credit-cards", label: "Credit cards" },
    ],
  },
  {
    label: "Investments & net worth", icon: TrendingUp,
    items: [
      { href: "/investments", label: "Investments" },
      { href: "/networth", label: "Net worth" },
    ],
  },
  {
    label: "Plan", icon: PiggyBank,
    items: [
      { href: "/budgets", label: "Budgets" },
      { href: "/subscriptions", label: "Bills & subscriptions" },
    ],
  },
  {
    label: "Insights", icon: BarChart3,
    items: [
      { href: "/analytics", label: "Analytics" },
      { href: "/reports", label: "Reports" },
      { href: "/assistant", label: "Ask Arth" },
    ],
  },
  {
    label: "Settings", icon: Settings,
    items: [
      { href: "/settings", label: "Settings" },
      { href: "/categories", label: "Categories & rules" },
      { href: "/notifications", label: "Notifications" },
    ],
  },
];

const isActive = (pathname: string, href: string) => pathname === href || pathname.startsWith(href + "/");
const groupOf = (pathname: string) => NAV_GROUPS.find((g) => g.items?.some((i) => isActive(pathname, i.href)))?.label;

const MOBILE_TABS: { href: string; label: string; icon: typeof LayoutDashboard }[] = [
  { href: "/dashboard", label: "Home", icon: LayoutDashboard },
  { href: "/transactions", label: "Transactions", icon: ListOrdered },
  { href: "/review", label: "Review", icon: Inbox },
  { href: "/investments", label: "Investments", icon: TrendingUp },
];

function Wordmark() {
  return (
    <Link href="/dashboard" className="flex items-center gap-2.5 px-2" aria-label="Arth home">
      <svg viewBox="0 0 32 32" className="size-8" aria-hidden>
        <rect width="32" height="32" rx="10" className="fill-peacock" />
        <path d="M9 22.5 15.2 9h1.6L23 22.5h-3l-1.3-3h-5.4l-1.3 3H9Zm5.3-5.6h3.4L16 12.6l-1.7 4.3Z" fill="#fff" />
        <circle cx="23.5" cy="9.5" r="2.5" className="fill-marigold" />
      </svg>
      <span className="font-display text-xl font-semibold tracking-tight">Arth</span>
    </Link>
  );
}

function Badge({ n }: { n: number }) {
  return n > 0 ? <span className="num min-w-6 rounded-full bg-marigold px-1.5 text-center text-xs font-bold text-[#3d2900]">{n}</span> : null;
}

function NavLinks({ onNavigate, reviewCount }: { onNavigate?: () => void; reviewCount: number }) {
  const pathname = usePathname();
  const current = groupOf(pathname);
  const [open, setOpen] = useState<Set<string>>(() => new Set(current ? [current] : []));
  useEffect(() => {
    if (current) setOpen((o) => (o.has(current) ? o : new Set(o).add(current)));
  }, [current]);
  const toggle = (label: string) =>
    setOpen((o) => {
      const next = new Set(o);
      if (next.has(label)) next.delete(label);
      else next.add(label);
      return next;
    });

  return (
    <nav className="flex flex-col gap-1" aria-label="Main">
      {NAV_GROUPS.map((group) => {
        const Icon = group.icon;
        if (group.href) {
          const active = isActive(pathname, group.href);
          return (
            <Link key={group.label} href={group.href} onClick={onNavigate} aria-current={active ? "page" : undefined}
              className={cn("flex items-center gap-3 rounded-xl px-3 py-2.5 text-sm font-semibold transition-colors",
                active ? "bg-peacock-soft text-peacock" : "text-ink hover:bg-surface-2")}>
              <Icon className="size-[18px] shrink-0" />
              <span className="flex-1">{group.label}</span>
            </Link>
          );
        }
        const expanded = open.has(group.label);
        const containsActive = current === group.label;
        const groupBadge = group.label === "Transactions" && !expanded ? reviewCount : 0;
        const id = `nav-${group.label.toLowerCase()}`;
        return (
          <div key={group.label}>
            <button type="button" onClick={() => toggle(group.label)} aria-expanded={expanded} aria-controls={id}
              className={cn("flex w-full items-center gap-3 rounded-xl px-3 py-2.5 text-sm font-semibold transition-colors hover:bg-surface-2",
                containsActive && !expanded ? "text-peacock" : "text-ink")}>
              <Icon className="size-[18px] shrink-0" />
              <span className="flex-1 text-left">{group.label}</span>
              <Badge n={groupBadge} />
              <ChevronDown className={cn("size-4 text-ink-3 transition-transform", expanded && "rotate-180")} />
            </button>
            {expanded && (
              <div id={id} className="mb-1 ml-[22px] mt-0.5 flex flex-col gap-0.5 border-l border-line pl-3">
                {group.items!.map((item) => {
                  const active = isActive(pathname, item.href);
                  return (
                    <Link key={item.href} href={item.href} onClick={onNavigate} aria-current={active ? "page" : undefined}
                      className={cn("flex items-center gap-2 rounded-lg px-2.5 py-1.5 text-sm transition-colors",
                        active ? "bg-peacock-soft font-semibold text-peacock" : "text-ink-2 hover:bg-surface-2 hover:text-ink")}>
                      <span className="flex-1">{item.label}</span>
                      {item.href === "/review" && <Badge n={reviewCount} />}
                    </Link>
                  );
                })}
              </div>
            )}
          </div>
        );
      })}
    </nav>
  );
}

/** Tabs for the pages in the current section, so related pages are one tap apart (also on phones). */
function SectionTabs({ reviewCount }: { reviewCount: number }) {
  const pathname = usePathname();
  const group = NAV_GROUPS.find((g) => g.items?.some((i) => isActive(pathname, i.href)));
  if (!group?.items || group.items.length < 2) return null;
  return (
    <div className="-mx-1 mb-5 flex gap-1 overflow-x-auto px-1" role="tablist" aria-label={group.label}>
      {group.items.map((item) => {
        const active = isActive(pathname, item.href);
        return (
          <Link key={item.href} href={item.href} role="tab" aria-selected={active}
            className={cn("flex shrink-0 items-center gap-1.5 rounded-full border px-3.5 py-1.5 text-sm font-medium transition-colors",
              active ? "border-peacock bg-peacock text-white" : "border-line text-ink-2 hover:bg-surface-2 hover:text-ink")}>
            {item.label}
            {item.href === "/review" && reviewCount > 0 && (
              <span className={cn("num rounded-full px-1.5 text-[11px] font-bold", active ? "bg-white/25" : "bg-marigold text-[#3d2900]")}>{reviewCount}</span>
            )}
          </Link>
        );
      })}
    </div>
  );
}

export function AppShell({ children }: { children: React.ReactNode }) {
  const me = useMe();
  const review = useReviewCount();
  const router = useRouter();
  const pathname = usePathname();
  const [moreOpen, setMoreOpen] = useState(false);
  const [dark, setDark] = useState(false);
  const reviewCount = review.data?.count ?? 0;

  useEffect(() => setDark(document.documentElement.classList.contains("dark")), []);
  useEffect(() => {
    if (me.isError) router.replace("/login");
  }, [me.isError, router]);

  async function logout() {
    await api.post("/auth/logout").catch(() => undefined);
    router.replace("/login");
  }

  function toggleTheme() {
    const next = dark ? "light" : "dark";
    applyTheme(next);
    setDark(!dark);
    api.put("/preferences", { theme: next }).catch(() => undefined);
  }

  if (me.isPending || me.isError) {
    return (
      <div className="mx-auto max-w-6xl p-6">
        <Skeleton className="h-10 w-40" />
        <Skeleton className="mt-6 h-48" />
      </div>
    );
  }

  return (
    <div className="min-h-dvh lg:grid lg:grid-cols-[260px_1fr]">
      <aside className="sticky top-0 hidden h-dvh flex-col border-r border-line bg-surface px-3 py-5 lg:flex">
        <div className="flex items-center justify-between pr-1">
          <Wordmark />
          <NotificationBell />
        </div>
        <div className="mt-6 flex-1 overflow-y-auto">
          <NavLinks reviewCount={reviewCount} />
        </div>
        <div className="mt-4 border-t border-line pt-4">
          <div className="px-3 text-sm">
            <p className="truncate font-semibold">{me.data.displayName}</p>
            <p className="truncate text-xs text-ink-3">{me.data.email}</p>
          </div>
          <div className="mt-3 flex gap-1 px-1">
            <button onClick={toggleTheme} className="flex flex-1 items-center gap-2 rounded-xl px-2 py-2 text-sm text-ink-2 hover:bg-surface-2">
              {dark ? <Sun className="size-4" /> : <Moon className="size-4" />} {dark ? "Light mode" : "Dark mode"}
            </button>
            <button onClick={logout} className="rounded-xl px-2.5 py-2 text-ink-2 hover:bg-surface-2" aria-label="Sign out" title="Sign out">
              <LogOut className="size-4" />
            </button>
          </div>
        </div>
      </aside>

      <div className="flex min-w-0 flex-col">
        {me.data.demo && (
          <div className="border-b border-marigold/40 bg-marigold-soft px-4 py-2 text-center text-[13px] text-marigold-ink">
            You&apos;re viewing the <strong>demo account</strong>. Every transaction here is synthetic sample data, not a connected bank.
          </div>
        )}
        <header className="sticky top-0 z-30 flex items-center justify-between border-b border-line bg-paper/90 px-4 py-3 backdrop-blur lg:hidden">
          <Wordmark />
          <div className="flex items-center">
            <NotificationBell />
            <button onClick={toggleTheme} className="rounded-full p-2 text-ink-2 hover:bg-surface-2" aria-label="Toggle dark mode">
              {dark ? <Sun className="size-5" /> : <Moon className="size-5" />}
            </button>
          </div>
        </header>
        <main className="mx-auto w-full max-w-6xl flex-1 px-4 pb-28 pt-5 sm:px-6 lg:px-10 lg:pb-12 lg:pt-8"><SectionTabs reviewCount={reviewCount} />{children}</main>
      </div>

      {/* mobile tab bar */}
      <nav
        className="fixed inset-x-0 bottom-0 z-40 grid grid-cols-5 border-t border-line bg-surface/95 pb-[env(safe-area-inset-bottom)] backdrop-blur lg:hidden"
        aria-label="Primary"
      >
        {MOBILE_TABS.map((item) => {
          const Icon = item.icon;
          const active = isActive(pathname, item.href);
          return (
            <Link key={item.href} href={item.href} className={cn("relative flex flex-col items-center gap-0.5 py-2 text-[11px] font-medium", active ? "text-peacock" : "text-ink-3")}>
              <Icon className="size-5" />
              {item.label}
              {item.href === "/review" && reviewCount > 0 && (
                <span className="num absolute right-[22%] top-1 rounded-full bg-marigold px-1.5 text-[10px] font-bold text-[#3d2900]">{reviewCount}</span>
              )}
            </Link>
          );
        })}
        <button onClick={() => setMoreOpen(true)} className="flex flex-col items-center gap-0.5 py-2 text-[11px] font-medium text-ink-3">
          <Menu className="size-5" />
          More
        </button>
      </nav>
      <Dialog open={moreOpen} onOpenChange={setMoreOpen}>
        <DialogContent title="Menu">
          <NavLinks reviewCount={reviewCount} onNavigate={() => setMoreOpen(false)} />
          <button onClick={logout} className="mt-3 flex w-full items-center gap-3 rounded-xl px-3 py-2 text-sm text-ink-2 hover:bg-surface-2">
            <LogOut className="size-[18px]" /> Sign out
          </button>
        </DialogContent>
      </Dialog>
    </div>
  );
}

export function PageHeader({ title, subtitle, actions }: { title: string; subtitle?: React.ReactNode; actions?: React.ReactNode }) {
  return (
    <div className="mb-6 flex flex-wrap items-end justify-between gap-4">
      <div>
        <h1 className="font-display text-[28px] font-semibold leading-tight tracking-tight sm:text-[32px]">{title}</h1>
        {subtitle && <p className="mt-1 max-w-2xl text-sm text-ink-2">{subtitle}</p>}
      </div>
      {actions && <div className="flex flex-wrap gap-2">{actions}</div>}
    </div>
  );
}
