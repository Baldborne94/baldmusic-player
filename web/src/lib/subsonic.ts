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
  song?: Song[];
}

export interface Artist {
  id: string;
  name: string;
  albumCount?: number;
  coverArt?: string;
  artistImageUrl?: string;
  starred?: string;
  album?: Album[];
}

export interface Playlist {
  id: string;
  name: string;
  songCount?: number;
  duration?: number;
  coverArt?: string;
  owner?: string;
  entry?: Song[];
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

/** Normalizza l'indirizzo digitato dall'utente: aggiunge lo schema, toglie lo slash finale. */
export function normalizeServerUrl(raw: string): string {
  let url = raw.trim();
  if (!/^https?:\/\//i.test(url)) url = `https://${url}`;
  return url.replace(/\/+$/, "");
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
    return indexes.flatMap((i) => list<Artist>(i.artist));
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

  async randomSongs(size = 50): Promise<Song[]> {
    const r = await this.call("getRandomSongs", { size });
    return list<Song>((r.randomSongs as Raw | undefined)?.song);
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

  streamUrl(id: string, maxBitRate?: number): string {
    return buildUrl(this.creds, "stream", { id, maxBitRate });
  }

  coverUrl(id: string | undefined, size = 300): string | undefined {
    return id ? buildUrl(this.creds, "getCoverArt", { id, size }) : undefined;
  }
}
