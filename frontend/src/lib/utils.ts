import { clsx, type ClassValue } from "clsx";
import { twMerge } from "tailwind-merge";

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs));
}

export function flattenCategories<T extends { children: T[] }>(tree: T[]): T[] {
  return tree.flatMap((c) => [c, ...c.children]);
}
