import {
  Apple, BadgePercent, Briefcase, Carrot, Building, Car, CircleHelp, CirclePlus, Clapperboard, Coins, FileText, Fuel, Gift, GraduationCap,
  HandHeart, HeartPulse, Home, Landmark, Laptop, Palette, Percent, Plane, Receipt, Repeat, Shield, ShoppingBag, ShoppingBasket,
  Sparkles, Store, Tag, TrendingUp, Users, Utensils, Zap, type LucideIcon,
} from "lucide-react";
import type * as React from "react";
import { cn } from "@/lib/utils";

/** Temple (mandir) with a shikhara tower and flag, drawn in the same style as the other icons. */
export function TempleIcon({ className, ...props }: React.SVGProps<SVGSVGElement>) {
  return (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} strokeLinecap="round" strokeLinejoin="round"
      className={cn("lucide", className)} width={24} height={24} aria-hidden {...props}>
      <path d="M12 4V1.5l3 1-3 1" />
      <path d="M8 13c0-3.5 1.6-6 4-9 2.4 3 4 5.5 4 9" />
      <path d="M9.2 9.5h5.6" />
      <path d="M6 21v-8h12v8" />
      <path d="M10 21v-3a2 2 0 0 1 4 0v3" />
      <path d="M3 21h18" />
    </svg>
  );
}

const ICONS: Record<string, LucideIcon | typeof TempleIcon> = {
  home: Home, "shopping-basket": ShoppingBasket, zap: Zap, car: Car, fuel: Fuel, "heart-pulse": HeartPulse, shield: Shield,
  "graduation-cap": GraduationCap, utensils: Utensils, "shopping-bag": ShoppingBag, clapperboard: Clapperboard, repeat: Repeat,
  plane: Plane, sparkles: Sparkles, palette: Palette, landmark: Landmark, "trending-up": TrendingUp, receipt: Receipt,
  "file-text": FileText, users: Users, gift: Gift, "hand-heart": HandHeart, "circle-help": CircleHelp, briefcase: Briefcase,
  laptop: Laptop, store: Store, building: Building, percent: Percent, coins: Coins, "badge-percent": BadgePercent,
  "circle-plus": CirclePlus, tag: Tag, temple: TempleIcon, carrot: Carrot, apple: Apple,
};

export function CategoryIcon({ icon, color, className, size = "md" }: { icon?: string | null; color?: string | null; className?: string; size?: "sm" | "md" }) {
  const Icon = (icon && ICONS[icon]) || Tag;
  return (
    <span
      className={cn("inline-grid shrink-0 place-items-center rounded-xl", size === "sm" ? "size-7 [&_svg]:size-3.5" : "size-9 [&_svg]:size-4", className)}
      style={{ backgroundColor: (color ?? "#64748B") + "22", color: color ?? "#64748B" }}
      aria-hidden
    >
      <Icon />
    </span>
  );
}

export const CATEGORY_ICON_NAMES = Object.keys(ICONS);
