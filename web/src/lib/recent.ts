import { isRealAlbum } from "./albums";
import type { Route } from "./router";
import type { Album, Playlist } from "./subsonic";

// «Ascoltati di recente» in cima alla Home, come sul telefono: quello che hai fatto partire tu
// (una raccolta, una playlist, un album, un artista, un mix di genere o di decennio, i
// preferiti), non l'album di ogni brano sentito. Navidrome ricorda solo gli album dei brani:
// ascoltando una raccolta, in cima finivano gli «album» di singoli presi a caso.

/** Quello che hai fatto partire, com'era allora (nome e copertina di un brano). */
export interface RecentItem {
  id: string;
  title: string;
  cover?: string;
}

/** Quanti se ne ricordano. */
export const MAX_RECENT = 12;
/** Quanti riquadri sotto «Buongiorno». */
export const QUICK_TILES = 8;

/** Un riquadro: dove porta e cosa mostra. [play] = il contenitore da suonare col ▶ (album e playlist). */
export interface QuickTile {
  key: string;
  title: string;
  cover?: string;
  route: Route;
  liked?: boolean;
  play?: { kind: "album" | "playlist"; id: string };
}

/** Dove porta un contenitore; null per quello che non ha una pagina (radio, ricerche, mix casuale). */
export function routeOf(id: string): Route | null {
  if (id === "liked") return { name: "liked" };
  if (id.startsWith("mix/genre/")) return { name: "genre", id: id.slice("mix/genre/".length) };
  if (id.startsWith("mix/decade/")) return { name: "decade", id: id.slice("mix/decade/".length) };
  const slash = id.indexOf("/");
  const kind = id.slice(0, slash);
  const rest = id.slice(slash + 1);
  if (slash < 0 || !rest) return null;
  if (kind === "album") return { name: "album", id: rest };
  if (kind === "playlist") return { name: "playlist", id: rest };
  if (kind === "artist") return { name: "artist", id: rest };
  return null;
}

/** [item] appena fatto partire: in cima, senza doppioni; solo quello che ha una pagina. */
export function withRecent(list: RecentItem[], item: RecentItem): RecentItem[] {
  if (!routeOf(item.id)) return list;
  return [item, ...list.filter((r) => r.id !== item.id)].slice(0, MAX_RECENT);
}

/**
 * I riquadri sotto «Buongiorno»: «Brani che ti piacciono» se ne hai, poi quello che hai fatto
 * partire di recente (raccolte e playlist col nome e la copertina di adesso, fuori se non ci
 * sono più), e se non basta gli album veri ascoltati di recente secondo Navidrome (mai i singoli).
 */
export function quickTiles(recent: RecentItem[], playlists: Playlist[], likedCount: number, recentAlbums: Album[]): QuickTile[] {
  const out: QuickTile[] = [];
  const seen = new Set<string>();
  if (likedCount > 0) {
    out.push({ key: "liked", title: "Brani che ti piacciono", route: { name: "liked" }, liked: true });
    seen.add("liked");
  }
  const byId = new Map(playlists.map((p) => [p.id, p]));
  for (const r of recent) {
    if (seen.has(r.id)) continue;
    const route = routeOf(r.id);
    if (!route) continue;
    if (route.name === "playlist") {
      const p = byId.get(route.id);
      if (!p) continue;
      out.push({ key: r.id, title: p.name, cover: p.coverArt ?? r.cover, route, play: { kind: "playlist", id: p.id } });
    } else {
      // Un artista con la sua foto (come nella Home), non la copertina del primo brano.
      const cover = route.name === "artist" ? `ar-${route.id}` : r.cover;
      out.push({ key: r.id, title: r.title, cover, route, play: route.name === "album" ? { kind: "album", id: route.id } : undefined });
    }
    seen.add(r.id);
  }
  for (const a of recentAlbums.filter(isRealAlbum)) {
    const key = `album/${a.id}`;
    if (seen.has(key)) continue;
    out.push({ key, title: a.name, cover: a.coverArt, route: { name: "album", id: a.id }, play: { kind: "album", id: a.id } });
    seen.add(key);
  }
  return out.slice(0, QUICK_TILES);
}
