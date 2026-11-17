import {
  BadgePercent, Briefcase, Building, Car, CircleHelp, CirclePlus, Clapperboard, Coins, FileText, Fuel, Gift, GraduationCap,
  HandHeart, HeartPulse, Home, Landmark, Laptop, Palette, Percent, Plane, Receipt, Repeat, Shield, ShoppingBag, ShoppingBasket,
  Sparkles, Store, Tag, TrendingUp, Users, Utensils, Zap, type LucideIcon,
} from "lucide-react";
import { cn } from "@/lib/utils";

const ICONS: Record<string, LucideIcon> = {
  home: Home, "shopping-basket": ShoppingBasket, zap: Zap, car: Car, fuel: Fuel, "heart-pulse": HeartPulse, shield: Shield,
  "graduation-cap": GraduationCap, utensils: Utensils, "shopping-bag": ShoppingBag, clapperboard: Clapperboard, repeat: Repeat,
  plane: Plane, sparkles: Sparkles, palette: Palette, landmark: Landmark, "trending-up": TrendingUp, receipt: Receipt,
  "file-text": FileText, users: Users, gift: Gift, "hand-heart": HandHeart, "circle-help": CircleHelp, briefcase: Briefcase,
  laptop: Laptop, store: Store, building: Building, percent: Percent, coins: Coins, "badge-percent": BadgePercent,
  "circle-plus": CirclePlus, tag: Tag,
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
