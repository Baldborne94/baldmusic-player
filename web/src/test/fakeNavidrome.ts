import type { Album, Song } from "../lib/subsonic";

// Un Navidrome finto, tutto in memoria: risponde alle chiamate Subsonic che fa l'app
// con lo stesso JSON del server vero. Lo usano i test unitari (fetch finto) e le prove
// nel browser (page.route di Playwright), così le due cose parlano con lo stesso server.

export interface FakePlaylist {
  id: string;
  name: string;
  comment?: string;
  owner: string;
  songIds: string[];
}

/** Una chiamata ricevuta: metodo (senza .view) e parametri, anche quelli ripetuti. */
export interface FakeCall {
  method: string;
  params: URLSearchParams;
}

export interface FakeInit {
  songs?: Song[];
  playlists?: (Omit<FakePlaylist, "owner"> & { owner?: string })[];
  starred?: string[];
  user?: string;
}

type Body = Record<string, unknown>;

export const ok = (body: Body = {}) => ({ "subsonic-response": { status: "ok", version: "1.16.1", type: "navidrome", openSubsonic: true, ...body } });

export const fail = (code: number, message: string) => ({ "subsonic-response": { status: "failed", version: "1.16.1", error: { code, message } } });

export class FakeNavidrome {
  songs: Song[];
  playlists: FakePlaylist[];
  starred: Set<string>;
  user: string;
  calls: FakeCall[] = [];
  /** Metodi che rispondono con un errore, per provare i casi storti. */
  failing = new Set<string>();
  private seq = 1;

  constructor(init: FakeInit = {}) {
    this.user = init.user ?? "b";
    this.songs = init.songs ?? [];
    this.playlists = (init.playlists ?? []).map((p) => ({ ...p, owner: p.owner ?? this.user, songIds: [...p.songIds] }));
    this.starred = new Set(init.starred ?? []);
  }

  /** Le chiamate a un metodo, nell'ordine in cui sono arrivate. */
  callsOf(method: string): FakeCall[] {
    return this.calls.filter((c) => c.method === method);
  }

  playlist(id: string): FakePlaylist | undefined {
    return this.playlists.find((p) => p.id === id);
  }

  playlistByName(name: string): FakePlaylist | undefined {
    return this.playlists.find((p) => p.name === name);
  }

  /** Risponde a un indirizzo come http://nd.test/rest/getPlaylist?id=…&f=json. */
  handle(url: string): unknown {
    const u = new URL(url);
    const method = u.pathname.replace(/^.*\/rest\//, "").replace(/\.view$/, "");
    const q = u.searchParams;
    this.calls.push({ method, params: q });
    if (this.failing.has(method)) return fail(0, `Errore finto su ${method}`);
    switch (method) {
      case "ping":
        return ok();
      case "getPlaylists":
        return ok({ playlists: { playlist: this.playlists.map((p) => this.summary(p)) } });
      case "getPlaylist": {
        const p = this.playlist(q.get("id") ?? "");
        return p ? ok({ playlist: this.full(p) }) : fail(70, "Playlist non trovata");
      }
      case "createPlaylist": {
        const ids = q.getAll("songId");
        const existing = q.get("playlistId");
        if (existing) {
          const p = this.playlist(existing);
          if (!p) return fail(70, "Playlist non trovata");
          if (p.owner !== this.user) return fail(50, "Non autorizzato");
          p.songIds = ids;
          return ok({ playlist: this.full(p) });
        }
        const p: FakePlaylist = { id: `pl-${this.seq++}`, name: q.get("name") ?? "", owner: this.user, songIds: ids };
        this.playlists.push(p);
        return ok({ playlist: this.full(p) });
      }
      case "updatePlaylist": {
        const p = this.playlist(q.get("playlistId") ?? "");
        if (!p) return fail(70, "Playlist non trovata");
        if (p.owner !== this.user) return fail(50, "Non autorizzato");
        if (q.has("name")) p.name = q.get("name")!;
        if (q.has("comment")) p.comment = q.get("comment")!;
        const remove = new Set(q.getAll("songIndexToRemove").map(Number));
        p.songIds = [...p.songIds.filter((_, i) => !remove.has(i)), ...q.getAll("songIdToAdd")];
        return ok();
      }
      case "deletePlaylist":
        this.playlists = this.playlists.filter((p) => p.id !== q.get("id"));
        return ok();
      case "search3": {
        const query = q.get("query") ?? "";
        const size = Number(q.get("songCount") ?? 20);
        const offset = Number(q.get("songOffset") ?? 0);
        const words = query === '""' ? "" : query.toLowerCase();
        const hits = this.songs.filter((s) => !words || `${s.title} ${s.artist ?? ""}`.toLowerCase().includes(words));
        return ok({ searchResult3: { song: hits.slice(offset, offset + size), album: [], artist: [] } });
      }
      case "getStarred2":
        return ok({ starred2: { song: this.songs.filter((s) => this.starred.has(s.id)).map((s) => ({ ...s, starred: "2026-01-01T10:00:00Z" })) } });
      case "star":
        this.starred.add(q.get("id") ?? "");
        return ok();
      case "unstar":
        this.starred.delete(q.get("id") ?? "");
        return ok();
      case "getAlbumList2":
        return ok({ albumList2: { album: this.albums().slice(0, Number(q.get("size") ?? 10)) } });
      case "getAlbum": {
        const a = this.albums().find((x) => x.id === q.get("id"));
        return a ? ok({ album: { ...a, song: this.songs.filter((s) => s.albumId === a.id) } }) : fail(70, "Album non trovato");
      }
      case "getArtists":
        return ok({ artists: { index: [] } });
      case "getGenres":
        return ok({ genres: {} });
      case "getRandomSongs":
        return ok({ randomSongs: { song: this.songs.slice(0, Number(q.get("size") ?? 10)) } });
      case "getTopSongs":
        return ok({ topSongs: {} });
      case "getSimilarSongs2":
        return ok({ similarSongs2: {} });
      case "scrobble":
        return ok();
      default:
        return fail(0, `Metodo non previsto dal server finto: ${method}`);
    }
  }

  private summary(p: FakePlaylist) {
    const songs = this.songsOf(p);
    return {
      id: p.id,
      name: p.name,
      comment: p.comment,
      owner: p.owner,
      public: false,
      songCount: songs.length,
      duration: songs.reduce((t, s) => t + (s.duration ?? 0), 0),
      coverArt: `pl-${p.id}`,
    };
  }

  private full(p: FakePlaylist) {
    return { ...this.summary(p), entry: this.songsOf(p) };
  }

  private songsOf(p: FakePlaylist): Song[] {
    const byId = new Map(this.songs.map((s) => [s.id, s]));
    return p.songIds.flatMap((id) => (byId.has(id) ? [byId.get(id)!] : []));
  }

  private albums(): Album[] {
    const out = new Map<string, Album>();
    for (const s of this.songs) {
      if (!s.albumId) continue;
      const a = out.get(s.albumId) ?? { id: s.albumId, name: s.album ?? "", artist: s.artist, artistId: s.artistId, coverArt: s.albumId, songCount: 0 };
      a.songCount = (a.songCount ?? 0) + 1;
      out.set(s.albumId, a);
    }
    return [...out.values()];
  }
}

/** La cartella della musica sul PC dell'utente, come la vede Navidrome con «Report Real Path». */
export const MUSIC = "C:\\Users\\B\\Music";

/** Un brano in [folder] (dentro MUSIC) con un percorso vero di Windows. */
export function fileSong(id: string, folder: string, title: string, extra: Partial<Song> = {}): Song {
  return { id, title, artist: "Artista", duration: 200, path: `${MUSIC}\\${folder}\\${title}.mp3`, ...extra };
}

/** [n] brani numerati nella stessa cartella, in ordine di percorso. */
export function folderSongs(folder: string, n: number, prefix = folder.replace(/\W+/g, "").toLowerCase()): Song[] {
  const digits = String(n).length;
  return Array.from({ length: n }, (_, i) => {
    const num = String(i + 1).padStart(digits, "0");
    return fileSong(`${prefix}-${num}`, folder, `${num} - Brano ${num}`);
  });
}
