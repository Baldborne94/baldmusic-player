import { useSyncExternalStore } from "react";

/**
 * Il tema del sito, come sul telefono (`AppTheme`): automatico = pop (giallo, contorni neri,
 * rosso, come il logo) quando il computer è in tema chiaro, scuro quando è scuro; oppure pop o
 * scuro sempre. Si applica come `data-theme="pop" | "dark"` su `<html>` (gli stili in styles.css).
 */
export type ThemeMode = "auto" | "pop" | "dark";

export const THEME_MODES: [ThemeMode, string][] = [
  ["auto", "Automatico"],
  ["pop", "Pop"],
  ["dark", "Scuro"],
];

export const THEME_KEY = "baldmusic.tema";

export function isPop(mode: ThemeMode, systemDark: boolean): boolean {
  return mode === "pop" || (mode === "auto" && !systemDark);
}

/** Il valore salvato; uno sconosciuto (o niente) è automatico. */
export function parseTheme(saved: string | null | undefined): ThemeMode {
  return saved === "pop" || saved === "dark" || saved === "auto" ? saved : "auto";
}

/** Il colore della barra del browser sul telefono per il tema in uso. */
export const themeColor = (pop: boolean) => (pop ? "#ffd23f" : "#0e0f13");

const listeners = new Set<() => void>();
let mode: ThemeMode = (() => {
  try {
    return parseTheme(localStorage.getItem(THEME_KEY));
  } catch {
    return "auto";
  }
})();

const darkQuery = () => (typeof matchMedia === "function" ? matchMedia("(prefers-color-scheme: dark)") : null);

/** Mette il tema giusto su `<html>`: all'avvio, quando cambia la scelta e quando cambia il tema del computer. */
export function applyTheme(): void {
  const pop = isPop(mode, darkQuery()?.matches ?? true);
  document.documentElement.dataset.theme = pop ? "pop" : "dark";
  document.querySelector('meta[name="theme-color"]')?.setAttribute("content", themeColor(pop));
}

export function setTheme(next: ThemeMode): void {
  mode = next;
  try {
    localStorage.setItem(THEME_KEY, next);
  } catch {
    // Archiviazione bloccata: vale finché resta aperta la scheda.
  }
  applyTheme();
  listeners.forEach((l) => l());
}

export function useTheme(): ThemeMode {
  return useSyncExternalStore(
    (l) => {
      listeners.add(l);
      return () => listeners.delete(l);
    },
    () => mode,
  );
}

/** All'avvio: il tema subito, poi segue il computer quando passa da chiaro a scuro. */
export function startTheme(): void {
  applyTheme();
  darkQuery()?.addEventListener?.("change", applyTheme);
}
