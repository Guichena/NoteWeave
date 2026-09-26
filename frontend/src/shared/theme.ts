export type ThemeMode = "light" | "dark";

const THEME_KEY = "noteweave.theme.v1";

export function getInitialTheme(): ThemeMode {
  if (typeof window === "undefined") {
    return "light";
  }
  try {
    const stored = window.localStorage.getItem(THEME_KEY);
    if (stored === "dark" || stored === "light") {
      return stored;
    }
  } catch {
    // Ignore localStorage access errors
  }
  if (typeof window.matchMedia === "function") {
    return window.matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light";
  }
  return "light";
}

export function applyTheme(theme: ThemeMode) {
  if (typeof window === "undefined" || !document.documentElement) {
    return;
  }
  document.documentElement.setAttribute("data-theme", theme);
  try {
    window.localStorage.setItem(THEME_KEY, theme);
  } catch {
    // Ignore localStorage access errors
  }
}

export function toggleTheme(): ThemeMode {
  const current = (document.documentElement.getAttribute("data-theme") as ThemeMode) || getInitialTheme();
  const next = current === "dark" ? "light" : "dark";
  applyTheme(next);
  return next;
}
