import { useSyncExternalStore } from "react";

// Router minimale su hash (#/album/123): funziona su GitHub Pages
// e su qualunque hosting statico senza regole di rewrite.

export type Route =
  | { name: "home" }
  | { name: "search"; query: string }
  | { name: "library" }
  | { name: "albums" }
  | { name: "artists" }
  | { name: "playlists" }
  | { name: "collections" }
  | { name: "cleanup" }
  | { name: "trash" }
  | { name: "album"; id: string }
  | { name: "artist"; id: string }
  | { name: "playlist"; id: string }
  | { name: "liked" }
  | { name: "genre"; id: string }
  | { name: "decade"; id: string }
  | { name: "stats" }
  | { name: "queue" }
  | { name: "settings" };

const WITH_ID = new Set(["album", "artist", "playlist", "genre", "decade"]);
const SIMPLE = new Set(["library", "albums", "artists", "playlists", "collections", "cleanup", "trash", "liked", "stats", "queue", "settings"]);

/** Un "%" scritto a mano (#/genre/100%) farebbe lanciare decodeURIComponent: resta com'è. */
function decode(part: string): string {
  try {
    return decodeURIComponent(part);
  } catch {
    return part;
  }
}

export function parseRoute(hash: string): Route {
  const [path, qs = ""] = hash.replace(/^#\/?/, "").split("?");
  const [section, id] = path.split("/").map(decode);
  if (section === "search") return { name: "search", query: new URLSearchParams(qs).get("q") ?? "" };
  if (section === "favorites") return { name: "liked" };
  if (WITH_ID.has(section)) return id ? ({ name: section, id } as Route) : { name: "home" };
  if (SIMPLE.has(section)) return { name: section } as Route;
  return { name: "home" };
}

export function href(route: Route): string {
  switch (route.name) {
    case "home":
      return "#/";
    case "search":
      return route.query ? `#/search?q=${encodeURIComponent(route.query)}` : "#/search";
    default:
      return "id" in route ? `#/${route.name}/${encodeURIComponent(route.id)}` : `#/${route.name}`;
  }
}

export function navigate(route: Route, replace = false): void {
  const target = href(route);
  if (replace) {
    history.replaceState(null, "", target);
    window.dispatchEvent(new HashChangeEvent("hashchange"));
  } else location.hash = target;
}

export function useRoute(): Route {
  const hash = useSyncExternalStore(
    (l) => {
      window.addEventListener("hashchange", l);
      return () => window.removeEventListener("hashchange", l);
    },
    () => location.hash,
  );
  return parseRoute(hash);
}
