export type Theme = "light" | "dark" | "system";

export function applyTheme(theme: Theme) {
  if (typeof window === "undefined") return;
  const dark = theme === "dark" || (theme === "system" && window.matchMedia("(prefers-color-scheme: dark)").matches);
  document.documentElement.classList.toggle("dark", dark);
  try {
    localStorage.setItem("arth-theme", theme);
  } catch {
    /* storage unavailable: theme still applies for this page */
  }
}

/** Inline script that runs before paint so there is no light flash in dark mode. */
export const themeBootScript = `(function(){try{var t=localStorage.getItem('arth-theme')||'system';var d=t==='dark'||(t==='system'&&matchMedia('(prefers-color-scheme: dark)').matches);if(d)document.documentElement.classList.add('dark');}catch(e){}})();`;
