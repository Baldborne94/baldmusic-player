import SparkMD5 from "spark-md5";

// Client per l'API Subsonic, quella che parla Navidrome.
// Riferimento: https://www.subsonic.org/pages/api.jsp e
// https://www.navidrome.org/docs/developers/subsonic-api/

export const API_VERSION = "1.16.1";
export const CLIENT_NAME = "baldmusic";

export interface Credentials {
  /** URL del server senza slash finale, es. https://pc.tail1234.ts.net */
  url: string;
  user: string;
  /** Sale casuale e md5(password + sale): la password in chiaro non viene salvata. */
  salt: string;
  token: string;
}

export interface Song {
  id: string;
  title: string;
  album?: string;
  albumId?: string;
  artist?: string;
  artistId?: string;
  track?: number;
  discNumber?: number;
  year?: number;
  duration?: number;
  coverArt?: string;
  starred?: string;
  suffix?: string;
  bitRate?: number;
  playCount?: number;
  genre?: string;
  /** Percorso del file: vero solo se nel lettore di Navidrome è attivo "Mostra percorso reale". */
  path?: string;
  /** ID MusicBrainz della registrazione (OpenSubsonic): c'è se il file è passato da Picard. */
  musicBrainzId?: string;
  /** Quando Navidrome ha trovato il file. */
  created?: string;
}

export interface Album {
  id: string;
  name: string;
  artist?: string;
  artistId?: string;
  coverArt?: string;
  songCount?: number;
  duration?: number;
  year?: number;
  genre?: string;
  starred?: string;
  playCount?: number;
  created?: string;
  song?: Song[];
  /** OpenSubsonic (Navidrome): tipo di uscita da MusicBrainz, es. ["Album"], ["Single"]. */
  releaseTypes?: string[];
  /** OpenSubsonic (Navidrome): data della prima uscita. */
  originalReleaseDate?: { year?: number; month?: number; day?: number };
}

export interface Artist {
  id: string;
  name: string;
  /** OpenSubsonic: nome per l'ordinamento (es. "Beatles, The"), se il tag c'è. */
  sortName?: string;
  albumCount?: number;
  coverArt?: string;
  artistImageUrl?: string;
  starred?: string;
  album?: Album[];
}

export interface Playlist {
  id: string;
  name: string;
  comment?: string;
  songCount?: number;
  duration?: number;
  coverArt?: string;
  owner?: string;
  entry?: Song[];
}

export interface Genre {
  value: string;
  songCount?: number;
  albumCount?: number;
}

export interface SearchResult {
  artist: Artist[];
  album: Album[];
  song: Song[];
}

export type AlbumListType =
  | "newest"
  | "recent"
  | "frequent"
  | "random"
  | "alphabeticalByName"
  | "alphabeticalByArtist"
  | "starred";

export class SubsonicError extends Error {
  constructor(
    message: string,
    readonly code?: number,
  ) {
    super(message);
    this.name = "SubsonicError";
  }
}

const artistCollator = new Intl.Collator("it", { numeric: true, sensitivity: "base" });

/**
 * Artisti in ordine alfabetico, senza "The" davanti. Navidrome li raggruppa per lettera
 * e mette prima i gruppi degli alfabeti non latini (cirillico, giapponese…): qui le
 * regole dell'italiano li portano in fondo.
 */
export function sortArtists(artists: Artist[]): Artist[] {
  const key = (a: Artist) => (a.sortName || a.name).replace(/^the\s+/i, "");
  return [...artists].sort((a, b) => artistCollator.compare(key(a), key(b)));
}

/** Porta predefinita di Navidrome. */
export const DEFAULT_PORT = 4533;

/**
 * Normalizza l'indirizzo digitato: aggiunge lo schema, toglie lo slash finale e,
 * se è un indirizzo IP o localhost senza porta, aggiunge :4533 (la porta
 * dimenticata è l'errore più comune). I nomi come …ts.net restano invariati.
 */
export function normalizeServerUrl(raw: string): string {
  let url = raw.trim().replace(/\/+$/, "");
  const bare = url.replace(/^https?:\/\//i, "").split(/[/:]/)[0];
  const local = /^\d{1,3}(\.\d{1,3}){3}$/.test(bare) || bare.toLowerCase() === "localhost";
  if (!/^https?:\/\//i.test(url)) url = `${local ? "http" : "https"}://${url}`;
  const m = /^(https?:\/\/)([^/:]+)(:\d+)?(.*)$/i.exec(url);
  if (m && !m[3] && local) url = `${m[1]}${m[2]}:${DEFAULT_PORT}${m[4]}`;
  return url;
}

/**
 * Da una pagina https il browser blocca le richieste http ("contenuto misto"),
 * tranne quelle verso il PC stesso: localhost e 127.x sono considerati sicuri.
 */
export function blockedAsMixedContent(pageProtocol: string, serverUrl: string): boolean {
  if (pageProtocol !== "https:") return false;
  let url: URL;
  try {
    url = new URL(serverUrl);
  } catch {
    return false;
  }
  if (url.protocol !== "http:") return false;
  const host = url.hostname;
  const loopback = host === "localhost" || host.endsWith(".localhost") || /^127\./.test(host) || host === "[::1]";
  return !loopback;
}

export function randomSalt(bytes = 8): string {
  const buf = new Uint8Array(bytes);
  crypto.getRandomValues(buf);
  return Array.from(buf, (b) => b.toString(16).padStart(2, "0")).join("");
}

/** Autenticazione a token di Subsonic: t = md5(password + s). */
export function makeCredentials(url: string, user: string, password: string, salt = randomSalt()): Credentials {
  return {
    url: normalizeServerUrl(url),
    user: user.trim(),
    salt,
    token: SparkMD5.hash(password + salt),
  };
}

export function buildUrl(creds: Credentials, method: string, params: Record<string, string | number | boolean | undefined> = {}): string {
  const q = new URLSearchParams({
    u: creds.user,
    t: creds.token,
    s: creds.salt,
    v: API_VERSION,
    c: CLIENT_NAME,
    f: "json",
  });
  for (const [k, v] of Object.entries(params)) {
    if (v !== undefined) q.set(k, String(v));
  }
  return `${creds.url}/rest/${method}?${q.toString()}`;
}

type Raw = Record<string, unknown>;

/** Subsonic in JSON omette le liste vuote: questo le riporta sempre ad array. */
function list<T>(value: unknown): T[] {
  if (value === undefined || value === null) return [];
  return Array.isArray(value) ? (value as T[]) : [value as T];
}

export function parseResponse(body: unknown): Raw {
  const root = (body as Raw | null)?.["subsonic-response"] as Raw | undefined;
  if (!root) throw new SubsonicError("Risposta non valida: il server non sembra Navidrome/Subsonic");
  if (root.status !== "ok") {
    const err = root.error as { code?: number; message?: string } | undefined;
    throw new SubsonicError(err?.message ?? "Errore del server", err?.code);
  }
  return root;
}

export class SubsonicClient {
  constructor(readonly creds: Credentials) {}

  private async call(method: string, params?: Record<string, string | number | boolean | undefined>): Promise<Raw> {
    let res: Response;
    try {
      res = await fetch(buildUrl(this.creds, method, params));
    } catch {
      throw new SubsonicError("Server non raggiungibile. È acceso? Il VPN (Tailscale) è attivo?");
    }
    if (!res.ok) throw new SubsonicError(`Il server ha risposto ${res.status}`);
    return parseResponse(await res.json());
  }

  async ping(): Promise<void> {
    await this.call("ping");
  }

  async albumList(type: AlbumListType, size = 40, offset = 0): Promise<Album[]> {
    const r = await this.call("getAlbumList2", { type, size, offset });
    return list<Album>((r.albumList2 as Raw | undefined)?.album);
  }

  async album(id: string): Promise<Album> {
    const r = await this.call("getAlbum", { id });
    const album = r.album as Album;
    return { ...album, song: list<Song>(album.song) };
  }

  async artists(): Promise<Artist[]> {
    const r = await this.call("getArtists");
    const indexes = list<Raw>((r.artists as Raw | undefined)?.index);
    return sortArtists(indexes.flatMap((i) => list<Artist>(i.artist)));
  }

  async artist(id: string): Promise<Artist> {
    const r = await this.call("getArtist", { id });
    const artist = r.artist as Artist;
    return { ...artist, album: list<Album>(artist.album) };
  }

  async playlists(): Promise<Playlist[]> {
    const r = await this.call("getPlaylists");
    return list<Playlist>((r.playlists as Raw | undefined)?.playlist);
  }

  async playlist(id: string): Promise<Playlist> {
    const r = await this.call("getPlaylist", { id });
    const pl = r.playlist as Playlist;
    return { ...pl, entry: list<Song>(pl.entry) };
  }

  async search(query: string): Promise<SearchResult> {
    const r = await this.call("search3", { query, artistCount: 10, albumCount: 20, songCount: 50 });
    const s = (r.searchResult3 ?? {}) as Raw;
    return { artist: list(s.artist), album: list(s.album), song: list(s.song) };
  }

  /** Brani a caso, filtrabili per genere e anni: la base dei mix. */
  async randomSongs(size = 50, filter: { genre?: string; fromYear?: number; toYear?: number } = {}): Promise<Song[]> {
    const r = await this.call("getRandomSongs", { size, ...filter });
    return list<Song>((r.randomSongs as Raw | undefined)?.song);
  }

  async genres(): Promise<Genre[]> {
    const r = await this.call("getGenres");
    return list<Genre>((r.genres as Raw | undefined)?.genre).sort((a, b) => (b.songCount ?? 0) - (a.songCount ?? 0));
  }

  async albumsByGenre(genre: string, size = 60): Promise<Album[]> {
    const r = await this.call("getAlbumList2", { type: "byGenre", genre, size });
    return list<Album>((r.albumList2 as Raw | undefined)?.album);
  }

  async albumsByYear(fromYear: number, toYear: number, size = 60): Promise<Album[]> {
    const r = await this.call("getAlbumList2", { type: "byYear", fromYear, toYear, size });
    return list<Album>((r.albumList2 as Raw | undefined)?.album);
  }

  /** Brani simili (radio). Richiede che il server sappia calcolarli; altrimenti lista vuota. */
  async similarSongs(id: string, count = 50): Promise<Song[]> {
    try {
      const r = await this.call("getSimilarSongs2", { id, count });
      return list<Song>((r.similarSongs2 as Raw | undefined)?.song);
    } catch {
      return [];
    }
  }

  async topSongs(artist: string, count = 10): Promise<Song[]> {
    try {
      const r = await this.call("getTopSongs", { artist, count });
      return list<Song>((r.topSongs as Raw | undefined)?.song);
    } catch {
      return [];
    }
  }

  /** Tutti i brani, a pagine: la ricerca vuota ("") di Navidrome li restituisce tutti. */
  async allSongs(): Promise<Song[]> {
    const page = 500;
    const out: Song[] = [];
    for (let offset = 0; ; offset += page) {
      const r = await this.call("search3", { query: '""', artistCount: 0, albumCount: 0, songCount: page, songOffset: offset });
      const songs = list<Song>((r.searchResult3 as Raw | undefined)?.song);
      out.push(...songs);
      if (songs.length < page) return out;
    }
  }

  /** Sostituisce tutti i brani di una playlist (nome e commento restano). */
  async replacePlaylistSongs(id: string, songIds: string[]): Promise<void> {
    const q = new URLSearchParams();
    songIds.forEach((s) => q.append("songId", s));
    await this.callMulti("createPlaylist", { playlistId: id }, q);
  }

  async createPlaylist(name: string, songIds: string[] = []): Promise<Playlist> {
    const q = new URLSearchParams();
    songIds.forEach((id) => q.append("songId", id));
    const r = await this.callMulti("createPlaylist", { name }, q);
    return r.playlist as Playlist;
  }

  /** Rinomina, cambia il commento, aggiunge brani, toglie brani per posizione. */
  async updatePlaylist(id: string, change: { name?: string; comment?: string; add?: string[]; removeIndexes?: number[] }): Promise<void> {
    const q = new URLSearchParams();
    change.add?.forEach((s) => q.append("songIdToAdd", s));
    change.removeIndexes?.forEach((i) => q.append("songIndexToRemove", String(i)));
    await this.callMulti("updatePlaylist", { playlistId: id, name: change.name, comment: change.comment }, q);
  }

  async deletePlaylist(id: string): Promise<void> {
    await this.call("deletePlaylist", { id });
  }

  /** Come call(), ma con parametri ripetuti (songId=…&songId=…). */
  private async callMulti(method: string, params: Record<string, string | number | boolean | undefined>, extra: URLSearchParams): Promise<Raw> {
    const url = `${buildUrl(this.creds, method, params)}&${extra.toString()}`;
    let res: Response;
    try {
      res = await fetch(url);
    } catch {
      throw new SubsonicError("Server non raggiungibile");
    }
    if (!res.ok) throw new SubsonicError(`Il server ha risposto ${res.status}`);
    return parseResponse(await res.json());
  }

  async starred(): Promise<SearchResult> {
    const r = await this.call("getStarred2");
    const s = (r.starred2 ?? {}) as Raw;
    return { artist: list(s.artist), album: list(s.album), song: list(s.song) };
  }

  async setStar(id: string, starred: boolean): Promise<void> {
    await this.call(starred ? "star" : "unstar", { id });
  }

  /** submission=false segnala "in ascolto ora", true registra l'ascolto. */
  async scrobble(id: string, submission: boolean): Promise<void> {
    await this.call("scrobble", { id, submission, time: Date.now() });
  }

  /** Sempre il file originale: "raw" impedisce a Navidrome di convertirlo. */
  streamUrl(id: string): string {
    return buildUrl(this.creds, "stream", { id, format: "raw" });
  }

  coverUrl(id: string | undefined, size = 300): string | undefined {
    return id ? buildUrl(this.creds, "getCoverArt", { id, size }) : undefined;
  }
}
