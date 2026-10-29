import * as Q from "./queue";
import type { Song } from "./subsonic";

// Ripresa, come sul telefono (QueueSnapshot.kt): la coda (brani, brano in corso, posizione,
// ordine del casuale, ripeti, da dove arriva) resta dopo aver chiuso o ricaricato la pagina.
// Al massimo 500 brani attorno a quello in corso.

export const MAX_SAVED = 500;
const BEFORE = 50;

export interface SavedQueue {
  songs: Song[];
  /** Le posizioni (in [songs]) dei brani aggiunti a mano o da «Continua ad ascoltare». */
  added: number[];
  index: number;
  /** Col casuale, l'ordine originale: posizioni in [songs]. */
  unshuffled: number[] | null;
  repeat: Q.RepeatMode;
  position: number;
  from: { label: string; id: string } | null;
}

export function snapshot(q: Q.QueueState, position: number, from: SavedQueue["from"]): SavedQueue | null {
  if (q.index < 0 || q.index >= q.entries.length) return null;
  // Verso la fine della coda si tengono più brani prima: sempre MAX_SAVED in tutto.
  const start = Math.max(0, Math.min(q.index - BEFORE, q.entries.length - MAX_SAVED));
  const kept = q.entries.slice(start, start + MAX_SAVED);
  const at = new Map(kept.map((e, i) => [e.uid, i]));
  return {
    songs: kept.map((e) => e.song),
    added: kept.flatMap((e, i) => (e.added ? [i] : [])),
    index: q.index - start,
    unshuffled: q.unshuffled ? q.unshuffled.flatMap((e) => (at.has(e.uid) ? [at.get(e.uid)!] : [])) : null,
    repeat: q.repeat,
    position: Math.max(0, position),
    from,
  };
}

/** La coda salvata, o null se manca o non torna (file rovinato, versione vecchia). */
export function restore(text: string | null): { queue: Q.QueueState; position: number; from: SavedQueue["from"] } | null {
  if (!text) return null;
  let s: SavedQueue;
  try {
    s = JSON.parse(text) as SavedQueue;
  } catch {
    return null;
  }
  if (!s || !Array.isArray(s.songs) || !s.songs.every((x) => x && typeof x.id === "string")) return null;
  if (!Number.isInteger(s.index) || s.index < 0 || s.index >= s.songs.length) return null;
  const added = new Set(Array.isArray(s.added) ? s.added : []);
  const entries = Q.entriesOf(s.songs, (i) => added.has(i));
  const order = Array.isArray(s.unshuffled) ? [...new Set(s.unshuffled.filter((i) => Number.isInteger(i) && i >= 0 && i < entries.length))] : null;
  // Brani dell'ordine originale persi (coda accorciata): in fondo, come aggiunti durante il casuale.
  const unshuffled = order ? [...order, ...entries.map((_, i) => i).filter((i) => !order.includes(i))].map((i) => entries[i]) : null;
  const repeat: Q.RepeatMode = s.repeat === "all" || s.repeat === "one" ? s.repeat : "off";
  const position = typeof s.position === "number" && s.position > 0 ? s.position : 0;
  const from = s.from && typeof s.from.id === "string" && typeof s.from.label === "string" ? { label: s.from.label, id: s.from.id } : null;
  return { queue: { entries, unshuffled, index: s.index, repeat }, position, from };
}
