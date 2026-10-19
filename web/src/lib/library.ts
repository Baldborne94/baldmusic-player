import { useSyncExternalStore } from "react";
import { replaceCopies, type DuplicateGroup } from "./cleanup";
import { collectionComment, collectionFolders, folderOf, isRealPath, planSync, type CollectionsState } from "./collections";
import { getClient } from "./session";
import type { Playlist, Song, SubsonicClient } from "./subsonic";

// La "tua musica" sul server: brani preferiti (star di Navidrome) e playlist.
// Stanno su Navidrome, quindi sono le stesse su PC, tablet e (col server) telefono.

/**
 * Raccolte dalle cartelle: "syncing" mentre si allinea, "noRealPath" se Navidrome non manda
 * i percorsi veri dei file (serve "Mostra percorso reale" nel lettore), [missing] le cartelle
 * senza raccolta che si possono creare a mano.
 */
export interface CollectionsStatus {
  state: "idle" | "syncing" | "ready" | "noRealPath" | "error";
  missing: string[];
  error?: string;
}

interface LibraryState {
  liked: Set<string>;
  likedSongs: Song[];
  playlists: Playlist[];
  loaded: boolean;
  collections: CollectionsStatus;
}

let state: LibraryState = { liked: new Set(), likedSongs: [], playlists: [], loaded: false, collections: { state: "idle", missing: [] } };
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

/** Riscrive i brani di una playlist o raccolta nell'ordine nuovo: nome e commento restano. */
export async function rewritePlaylist(id: string, songIds: string[]): Promise<void> {
  await getClient()?.replacePlaylistSongs(id, songIds);
  await refreshLibrary();
}

/** Toglie più brani in una volta, per posizione. */
export async function removeManyFromPlaylist(id: string, indexes: number[]): Promise<void> {
  await getClient()?.updatePlaylist(id, { removeIndexes: indexes });
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

/** Raccolte (playlist che vengono da una cartella) e playlist normali. */
export const isCollection = (p: Playlist) => folderOf(p) !== null;

// Le cartelle viste all'ultimo allineamento, per creare a mano quelle che mancano.
let lastFolders = new Map<string, string[]>();
let syncing: Promise<void> | null = null;

/** Allinea le raccolte alle cartelle della musica sul server. Una sola volta per volta. */
export function syncCollections(): Promise<void> {
  syncing ??= doSync().finally(() => {
    syncing = null;
  });
  return syncing;
}

async function doSync(): Promise<void> {
  const c = getClient();
  if (!c) return;
  set({ collections: { ...state.collections, state: "syncing" } });
  try {
    const [songs, playlists] = await Promise.all([c.allSongs(), c.playlists()]);
    lastFolders = collectionFolders(songs);
    if (lastFolders.size === 0) {
      const fake = songs.length > 0 && !songs.some((s) => s.path && isRealPath(s.path));
      set({ collections: { state: fake ? "noRealPath" : "ready", missing: [] } });
      return;
    }
    const plan = planSync(lastFolders, playlists, loadState(c));
    for (const k of plan.create) await createFromFolder(c, k.folder, k.songIds);
    for (const a of plan.add) {
      const have = new Set((await c.playlist(a.playlistId)).entry?.map((s) => s.id));
      await addInChunks(c, a.playlistId, a.songIds.filter((id) => !have.has(id)));
    }
    saveState(c, plan.state);
    set({ collections: { state: "ready", missing: plan.missing } });
  } catch (e) {
    set({ collections: { state: "error", missing: state.collections.missing, error: e instanceof Error ? e.message : String(e) } });
  }
  await refreshLibrary();
}

/** Crea a mano la raccolta di una cartella (eliminata prima, o vista da un altro browser). */
export async function createCollection(folder: string): Promise<void> {
  const c = getClient();
  const ids = lastFolders.get(folder);
  if (!c || !ids) return;
  await createFromFolder(c, folder, ids);
  set({ collections: { ...state.collections, missing: state.collections.missing.filter((f) => f !== folder) } });
  await refreshLibrary();
}

// Gli ID vanno nell'indirizzo della richiesta: a blocchi, per non farlo troppo lungo.
const CHUNK = 200;

async function createFromFolder(c: SubsonicClient, folder: string, ids: string[]): Promise<void> {
  const pl = await c.createPlaylist(folder, ids.slice(0, CHUNK));
  await c.updatePlaylist(pl.id, { comment: collectionComment(folder) });
  await addInChunks(c, pl.id, ids.slice(CHUNK));
}

async function addInChunks(c: SubsonicClient, playlistId: string, ids: string[]): Promise<void> {
  for (let i = 0; i < ids.length; i += CHUNK) await c.updatePlaylist(playlistId, { add: ids.slice(i, i + CHUNK) });
}

// Cosa ricorda questo browser delle cartelle, per server e utente.
const stateKey = (c: SubsonicClient) => `baldmusic.raccolte.${c.creds.url}|${c.creds.user}`;

function loadState(c: SubsonicClient): CollectionsState | null {
  try {
    const raw = localStorage.getItem(stateKey(c));
    return raw ? (JSON.parse(raw) as CollectionsState) : null;
  } catch {
    return null;
  }
}

function saveState(c: SubsonicClient, s: CollectionsState): void {
  try {
    localStorage.setItem(stateKey(c), JSON.stringify(s));
  } catch {
    // ignorato: al prossimo giro le cartelle si confrontano di nuovo da capo
  }
}

/**
 * Prima di buttare i doppioni: le raccolte, le playlist e i preferiti che avevano una copia
 * buttata passano a quella tenuta, nella stessa posizione (come mergeTracks sul telefono).
 */
export async function mergeDuplicates(groups: DuplicateGroup[]): Promise<void> {
  const c = getClient();
  if (!c) return;
  const keptFor = new Map(groups.flatMap((g) => g.others.map((o) => [o.id, g.keep.id] as const)));
  if (keptFor.size === 0) return;
  const mine = state.playlists.filter((p) => !p.owner || p.owner.toLowerCase() === c.creds.user.toLowerCase());
  for (const p of mine) {
    const ids = ((await c.playlist(p.id)).entry ?? []).map((s) => s.id);
    if (ids.some((id) => keptFor.has(id))) await c.replacePlaylistSongs(p.id, replaceCopies(ids, keptFor));
  }
  for (const [copy, kept] of keptFor) {
    if (state.liked.has(copy) && !state.liked.has(kept)) await c.setStar(kept, true);
  }
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
