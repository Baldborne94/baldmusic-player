import { useSyncExternalStore } from "react";

// Router minimale su hash (#/album/123): funziona su GitHub Pages
// e su qualunque hosting statico senza regole di rewrite.

export type Route =
  | { name: "home" }
  | { name: "albums" }
  | { name: "album"; id: string }
  | { name: "artists" }
  | { name: "artist"; id: string }
  | { name: "playlists" }
  | { name: "playlist"; id: string }
  | { name: "favorites" }
  | { name: "search"; query: string }
  | { name: "queue" }
  | { name: "settings" };

export function parseRoute(hash: string): Route {
  const [path, qs = ""] = hash.replace(/^#\/?/, "").split("?");
  const [section, id] = path.split("/").map((p) => decodeURIComponent(p));
  switch (section) {
    case "albums":
      return { name: "albums" };
    case "album":
      return id ? { name: "album", id } : { name: "albums" };
    case "artists":
      return { name: "artists" };
    case "artist":
      return id ? { name: "artist", id } : { name: "artists" };
    case "playlists":
      return { name: "playlists" };
    case "playlist":
      return id ? { name: "playlist", id } : { name: "playlists" };
    case "favorites":
      return { name: "favorites" };
    case "search":
      return { name: "search", query: new URLSearchParams(qs).get("q") ?? "" };
    case "queue":
      return { name: "queue" };
    case "settings":
      return { name: "settings" };
    default:
      return { name: "home" };
  }
}

export function href(route: Route): string {
  switch (route.name) {
    case "home":
      return "#/";
    case "album":
    case "artist":
    case "playlist":
      return `#/${route.name}/${encodeURIComponent(route.id)}`;
    case "search":
      return route.query ? `#/search?q=${encodeURIComponent(route.query)}` : "#/search";
    default:
      return `#/${route.name}`;
  }
}

export function navigate(route: Route, replace = false): void {
  const target = href(route);
  if (replace) history.replaceState(null, "", target);
  else location.hash = target;
  if (replace) window.dispatchEvent(new HashChangeEvent("hashchange"));
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
