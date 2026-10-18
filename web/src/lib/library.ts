import { useSyncExternalStore } from "react";
import { getClient } from "./session";
import type { Playlist, Song } from "./subsonic";

// La "tua musica" sul server: brani preferiti (star di Navidrome) e playlist.
// Stanno su Navidrome, quindi sono le stesse su PC, tablet e (col server) telefono.

interface LibraryState {
  liked: Set<string>;
  likedSongs: Song[];
  playlists: Playlist[];
  loaded: boolean;
}

let state: LibraryState = { liked: new Set(), likedSongs: [], playlists: [], loaded: false };
const listeners = new Set<() => void>();

function set(patch: Partial<LibraryState>) {
  state = { ...state, ...patch };
  listeners.forEach((l) => l());
}

export function useLibrary(): LibraryState {
  return useSyncExternalStore(
    (l) => {
      listeners.add(l);
      return () => listeners.delete(l);
    },
    () => state,
  );
}

export async function refreshLibrary(): Promise<void> {
  const c = getClient();
  if (!c) return;
  const [starred, playlists] = await Promise.all([c.starred().catch(() => null), c.playlists().catch(() => null)]);
  set({
    liked: starred ? new Set(starred.song.map((s) => s.id)) : state.liked,
    likedSongs: starred ? starred.song : state.likedSongs,
    playlists: playlists ?? state.playlists,
    loaded: true,
  });
}

/** Mi piace / non mi piace più: aggiorna subito l'interfaccia, poi il server. */
export async function toggleLike(song: Song): Promise<void> {
  const c = getClient();
  if (!c) return;
  const was = state.liked.has(song.id);
  const liked = new Set(state.liked);
  if (was) liked.delete(song.id);
  else liked.add(song.id);
  set({
    liked,
    likedSongs: was ? state.likedSongs.filter((s) => s.id !== song.id) : [{ ...song, starred: new Date().toISOString() }, ...state.likedSongs],
  });
  try {
    await c.setStar(song.id, !was);
  } catch {
    await refreshLibrary();
  }
}

export async function createPlaylist(name: string, songs: Song[] = []): Promise<void> {
  await getClient()?.createPlaylist(name, songs.map((s) => s.id));
  await refreshLibrary();
}

export async function addToPlaylist(id: string, songs: Song[]): Promise<void> {
  await getClient()?.updatePlaylist(id, { add: songs.map((s) => s.id) });
  await refreshLibrary();
}

export async function removeFromPlaylist(id: string, index: number): Promise<void> {
  await getClient()?.updatePlaylist(id, { removeIndexes: [index] });
  await refreshLibrary();
}

export async function renamePlaylist(id: string, name: string): Promise<void> {
  await getClient()?.updatePlaylist(id, { name });
  await refreshLibrary();
}

export async function deletePlaylist(id: string): Promise<void> {
  await getClient()?.deletePlaylist(id);
  await refreshLibrary();
}

/** Ricerche recenti, solo su questo dispositivo. */
const SEARCHES = "baldmusic.searches";

export function recentSearches(): string[] {
  try {
    return JSON.parse(localStorage.getItem(SEARCHES) ?? "[]") as string[];
  } catch {
    return [];
  }
}

export function rememberSearch(q: string): void {
  const query = q.trim();
  if (query.length < 2) return;
  const list = [query, ...recentSearches().filter((s) => s.toLowerCase() !== query.toLowerCase())].slice(0, 10);
  try {
    localStorage.setItem(SEARCHES, JSON.stringify(list));
  } catch {
    // ignorato
  }
}

export function clearSearches(): void {
  try {
    localStorage.removeItem(SEARCHES);
  } catch {
    // ignorato
  }
}
