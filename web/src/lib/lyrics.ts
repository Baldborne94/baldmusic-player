// Testi sincronizzati da LRCLIB (https://lrclib.net): archivio libero, senza
// chiave, che risponde anche dal browser. I risultati restano in localStorage.

export interface LyricLine {
  time: number; // secondi
  text: string;
}

export interface Lyrics {
  synced: LyricLine[];
  plain: string | null;
}

const TIME = /\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]/g;

/** Formato LRC: "[01:23.45]Testo", anche con più tempi per riga e [offset:±ms]. */
export function parseLrc(text: string): LyricLine[] {
  const offset = Number(/\[offset:\s*([+-]?\d+)]/i.exec(text)?.[1] ?? 0) / 1000;
  const out: LyricLine[] = [];
  for (const raw of text.split(/\r?\n/)) {
    const tags = [...raw.matchAll(TIME)];
    if (tags.length === 0) continue;
    const last = tags[tags.length - 1];
    const lyric = raw.slice((last.index ?? 0) + last[0].length).trim();
    for (const t of tags) {
      const frac = t[3] ?? "";
      const fracSec = frac ? Number(frac) / 10 ** frac.length : 0;
      out.push({ time: Math.max(0, Number(t[1]) * 60 + Number(t[2]) + fracSec - offset), text: lyric });
    }
  }
  return out.sort((a, b) => a.time - b.time);
}

/** Indice della riga da evidenziare a [position] secondi, -1 prima della prima. */
export function lineAt(lines: LyricLine[], position: number): number {
  let lo = 0;
  let hi = lines.length - 1;
  if (hi < 0 || position < lines[0].time) return -1;
  while (lo < hi) {
    const mid = (lo + hi + 1) >> 1;
    if (lines[mid].time <= position) lo = mid;
    else hi = mid - 1;
  }
  return lo;
}

interface LrcLibRecord {
  duration?: number;
  instrumental?: boolean;
  plainLyrics?: string | null;
  syncedLyrics?: string | null;
}

function toLyrics(r: LrcLibRecord): Lyrics | null {
  if (r.instrumental) return { synced: [], plain: "♪ Brano strumentale ♪" };
  const synced = r.syncedLyrics ? parseLrc(r.syncedLyrics) : [];
  if (synced.length === 0 && !r.plainLyrics) return null;
  return { synced, plain: r.plainLyrics ?? null };
}

const CACHE = "baldmusic.lyrics.";
const WEEK = 7 * 24 * 3600 * 1000;

export async function findLyrics(artist: string | undefined, title: string, album?: string, duration?: number, fetcher: typeof fetch = fetch): Promise<Lyrics | null> {
  if (!artist || !title) return null;
  const key = CACHE + `${artist}|${title}`.toLowerCase();
  try {
    const cached = localStorage.getItem(key);
    if (cached) {
      const c = JSON.parse(cached) as { at: number; lyrics: Lyrics | null };
      if (c.lyrics || Date.now() - c.at < WEEK) return c.lyrics;
    }
  } catch {
    // archiviazione non disponibile: si cerca e basta
  }
  const base = "https://lrclib.net/api";
  let lyrics: Lyrics | null = null;
  try {
    const q = new URLSearchParams({ artist_name: artist, track_name: title });
    if (album) q.set("album_name", album);
    if (duration) q.set("duration", String(Math.round(duration)));
    const res = await fetcher(`${base}/get?${q}`);
    if (res.ok) lyrics = toLyrics((await res.json()) as LrcLibRecord);
    if (!lyrics) {
      const s = await fetcher(`${base}/search?${new URLSearchParams({ artist_name: artist, track_name: title })}`);
      if (s.ok) {
        const list = ((await s.json()) as LrcLibRecord[]).filter((r) => r.syncedLyrics || r.plainLyrics);
        list.sort((a, b) => (a.syncedLyrics ? 0 : 1) - (b.syncedLyrics ? 0 : 1) || Math.abs((a.duration ?? 0) - (duration ?? 0)) - Math.abs((b.duration ?? 0) - (duration ?? 0)));
        lyrics = list[0] ? toLyrics(list[0]) : null;
      }
    }
  } catch {
    return null; // offline: niente cache del "non trovato"
  }
  try {
    localStorage.setItem(key, JSON.stringify({ at: Date.now(), lyrics }));
  } catch {
    // ignorato
  }
  return lyrics;
}
