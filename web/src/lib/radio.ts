import { libraryIndex } from "./library";
import { getClient } from "./session";
import type { Song } from "./subsonic";

// La radio di un brano, con il suo mood e il suo stile: le stesse regole del telefono
// (MoodRadio.kt). L'indizio migliore sono le tue raccolte (le cartelle): le hai fatte tu per
// stile. Poi gli artisti che stanno nelle stesse raccolte, il genere scritto nel file, i brani
// che Navidrome sa simili (se glielo calcola Last.fm), l'energia (ReplayGain) e il decennio.
// Serve a «Avvia radio» e a «Continua ad ascoltare».

export interface MoodSong {
  id: string;
  artist?: string;
  raccolta?: string;
  genres: Set<string>;
  decade?: number;
  /** Guadagno di ReplayGain: più basso = brano più forte. */
  gainDb?: number;
}

/** Al massimo così tanti brani nella radio (il brano scelto compreso). */
export const RADIO_SIZE = 50;
/** Dello stesso artista del brano scelto, al massimo: uno ogni tre. */
const SAME_ARTIST = 15;

/** Un nome confrontabile: senza accenti, maiuscole e punteggiatura. */
export function nameKey(text: string): string {
  return text
    .normalize("NFD")
    .replace(/[̀-ͯ]/g, "")
    .toLowerCase()
    .replace(/[^\p{L}\p{N}]+/gu, " ")
    .trim();
}

/** Il genere scritto nel file, in parole confrontabili ("Rock; Alternative Metal" → rock, alternative metal). */
export function genres(text: string | undefined): Set<string> {
  if (!text) return new Set();
  return new Set(
    text
      .split(/[;/,|]/)
      .map(nameKey)
      .filter((g) => g && g !== "other" && g !== "unknown"),
  );
}

export function moodSong(s: Song, raccolta?: string): MoodSong {
  const artist = s.artist ? nameKey(s.artist) : "";
  return {
    id: s.id,
    artist: artist || undefined,
    raccolta,
    genres: genres(s.genre),
    decade: s.year && s.year > 0 ? Math.floor(s.year / 10) * 10 : undefined,
    gainDb: s.replayGain?.trackGain ?? s.replayGain?.albumGain ?? undefined,
  };
}

/** Raccolte e artisti che vanno insieme, da come hai ordinato la musica. */
export class Affinity {
  private artistsOf = new Map<string, Set<string>>();
  private raccolteOf = new Map<string, Set<string>>();
  private cache = new Map<string, number>();

  constructor(all: MoodSong[]) {
    for (const s of all) {
      if (!s.raccolta || !s.artist) continue;
      if (!this.artistsOf.has(s.raccolta)) this.artistsOf.set(s.raccolta, new Set());
      if (!this.raccolteOf.has(s.artist)) this.raccolteOf.set(s.artist, new Set());
      this.artistsOf.get(s.raccolta)!.add(s.artist);
      this.raccolteOf.get(s.artist)!.add(s.raccolta);
    }
  }

  /** Da 0 a 1: quanti artisti hanno in comune due raccolte, rispetto alla più piccola. */
  between(a: string, b: string): number {
    const key = a < b ? `${a}\u0000${b}` : `${b}\u0000${a}`;
    let v = this.cache.get(key);
    if (v === undefined) {
      const x = this.artistsOf.get(a);
      const y = this.artistsOf.get(b);
      v = !x?.size || !y?.size ? 0 : [...x].filter((n) => y.has(n)).length / Math.min(x.size, y.size);
      this.cache.set(key, v);
    }
    return v;
  }

  /** I due artisti stanno insieme in almeno una raccolta. */
  related(a: string | undefined, b: string | undefined): boolean {
    if (!a || !b || a === b) return false;
    const x = this.raccolteOf.get(a);
    const y = this.raccolteOf.get(b);
    return !!x && !!y && [...y].some((r) => x.has(r));
  }
}

/** Quanto [c] somiglia a [seed]: sopra zero è simile. [similar]: i simili secondo Navidrome. */
export function score(seed: MoodSong, c: MoodSong, affinity: Affinity, similar: Set<string> = new Set()): number {
  let s = 0;
  // La stessa raccolta: il segno più forte, l'hai scelto tu.
  if (seed.raccolta && c.raccolta === seed.raccolta) s += 4;
  else if (seed.raccolta && c.raccolta) s += 3 * affinity.between(seed.raccolta, c.raccolta);
  // Un artista che metti nelle stesse raccolte dell'artista del brano.
  if (affinity.related(seed.artist, c.artist)) s += 2;
  if (seed.genres.size && c.genres.size) s += [...seed.genres].some((g) => c.genres.has(g)) ? 3 : -2;
  if (similar.has(c.id)) s += 3;
  if (seed.decade !== undefined && c.decade === seed.decade) s += 1;
  // Energia: un brano molto più forte o più tranquillo rompe il mood.
  if (seed.gainDb !== undefined && c.gainDb !== undefined) {
    const d = Math.abs(seed.gainDb - c.gainDb);
    if (d > 8) s -= 3;
    else if (d > 5) s -= 1.5;
  }
  return s;
}

/** Il prossimo dei simili, saltando (tra i primi tre) quelli dello stesso artista dell'ultimo. */
function takeVaried(queue: MoodSong[], lastArtist: string | undefined): MoodSong | undefined {
  if (queue.length === 0) return undefined;
  let i = queue.slice(0, 3).findIndex((s) => !s.artist || s.artist !== lastArtist);
  if (i < 0) i = 0;
  return queue.splice(i, 1)[0];
}

const byScore = (a: [MoodSong, number], b: [MoodSong, number]) => b[1] - a[1];

/**
 * La radio di [seed]: lui per primo, poi brani simili (due) e dello stesso artista (uno), a
 * turno, senza due brani di fila dello stesso artista quando si può evitare. Restituisce gli ID.
 */
export function pickRadio(seed: MoodSong, all: MoodSong[], random: () => number = Math.random, similar: Set<string> = new Set()): string[] {
  const affinity = new Affinity(all);
  const sameArtist = all
    .filter((s) => s.id !== seed.id && !!seed.artist && s.artist === seed.artist)
    .map((s): [MoodSong, number] => [s, score(seed, s, affinity, similar) + random()])
    .sort(byScore)
    .slice(0, SAME_ARTIST)
    .map(([s]) => s);
  const others = all.filter((s) => s.id !== seed.id && (!seed.artist || s.artist !== seed.artist)).map((s): [MoodSong, number] => [s, score(seed, s, affinity, similar)]);
  // I simili davvero; se sono pochi (libreria piccola, niente raccolte) si completa coi più vicini.
  const similarOnes = others
    .filter(([, v]) => v > 0)
    .map(([s, v]): [MoodSong, number] => [s, v + random() * 1.5])
    .sort(byScore)
    .map(([s]) => s);
  const pool =
    similarOnes.length >= 10
      ? similarOnes
      : [
          ...similarOnes,
          ...others
            .filter(([, v]) => v <= 0)
            .map(([s, v]): [MoodSong, number] => [s, v + random()])
            .sort(byScore)
            .map(([s]) => s)
            .slice(0, 30 - similarOnes.length),
        ];

  const out: MoodSong[] = [seed];
  const a = [...sameArtist];
  const r = [...pool];
  while ((a.length || r.length) && out.length < RADIO_SIZE) {
    for (let k = 0; k < 2; k++) {
      const next = takeVaried(r, out[out.length - 1].artist);
      if (next) out.push(next);
    }
    const mine = a.shift();
    if (mine) out.push(mine);
  }
  const seen = new Set<string>();
  return out.filter((s) => !seen.has(s.id) && !!seen.add(s.id)).slice(0, RADIO_SIZE).map((s) => s.id);
}

/**
 * La radio di [seed] dal server: tra tutti i brani (con le raccolte delle cartelle) se l'app li ha
 * già letti, altrimenti tra i simili, i più ascoltati dell'artista e brani dello stesso genere.
 */
export async function songRadio(seed: Song): Promise<Song[]> {
  const c = getClient();
  if (!c) return [seed];
  const index = libraryIndex();
  const similar = await c.similarSongs(seed.id, 50);
  let pool: Song[];
  if (index && index.songs.length) pool = index.songs;
  else {
    const [top, genre, any] = await Promise.all([
      seed.artist ? c.topSongs(seed.artist, 15) : [],
      seed.genre ? c.randomSongs(40, { genre: seed.genre }) : [],
      c.randomSongs(40),
    ]);
    pool = [...similar, ...top, ...genre, ...any];
  }
  const byId = new Map<string, Song>();
  for (const s of [seed, ...pool, ...similar]) if (!byId.has(s.id)) byId.set(s.id, s);
  const raccolta = (s: Song) => index?.folderOf.get(s.id);
  const ids = pickRadio(
    moodSong(seed, raccolta(seed)),
    [...byId.values()].map((s) => moodSong(s, raccolta(s))),
    Math.random,
    new Set(similar.map((s) => s.id)),
  );
  return ids.map((id) => byId.get(id)!);
}
