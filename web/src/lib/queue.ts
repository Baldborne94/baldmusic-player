import type { Song } from "./subsonic";

// Logica pura della coda di riproduzione: nessun audio, nessun DOM.
// Ogni voce ha un uid proprio perché lo stesso brano può comparire due volte.

export type RepeatMode = "off" | "all" | "one";

export interface Entry {
  uid: number;
  song: Song;
}

export interface QueueState {
  entries: Entry[];
  /** Ordine originale, conservato mentre lo shuffle è attivo. */
  unshuffled: Entry[] | null;
  index: number;
  repeat: RepeatMode;
}

export const emptyQueue: QueueState = { entries: [], unshuffled: null, index: -1, repeat: "off" };

let nextUid = 1;
const wrap = (songs: Song[]): Entry[] => songs.map((song) => ({ uid: nextUid++, song }));

export function current(q: QueueState): Song | undefined {
  return q.entries[q.index]?.song;
}

function shuffled<T>(items: T[], random: () => number): T[] {
  const a = items.slice();
  for (let i = a.length - 1; i > 0; i--) {
    const j = Math.floor(random() * (i + 1));
    [a[i], a[j]] = [a[j], a[i]];
  }
  return a;
}

/** Sostituisce la coda. Con shuffle, il brano scelto resta il primo. */
export function playList(q: QueueState, songs: Song[], start = 0, shuffle = false, random = Math.random): QueueState {
  if (songs.length === 0) return { ...emptyQueue, repeat: q.repeat };
  const entries = wrap(songs);
  const idx = Math.min(Math.max(start, 0), entries.length - 1);
  if (!shuffle) return { entries, unshuffled: null, index: idx, repeat: q.repeat };
  const first = entries[idx];
  const rest = shuffled(entries.filter((_, i) => i !== idx), random);
  return { entries: [first, ...rest], unshuffled: entries, index: 0, repeat: q.repeat };
}

export function toggleShuffle(q: QueueState, random = Math.random): QueueState {
  const cur = q.entries[q.index];
  if (q.unshuffled) {
    // Spegnendo lo shuffle si torna all'ordine originale, sul brano corrente.
    // Le voci aggiunte durante lo shuffle restano in fondo.
    const known = new Set(q.unshuffled.map((e) => e.uid));
    const entries = [...q.unshuffled.filter((e) => q.entries.some((x) => x.uid === e.uid)), ...q.entries.filter((e) => !known.has(e.uid))];
    return { ...q, entries, unshuffled: null, index: cur ? entries.findIndex((e) => e.uid === cur.uid) : -1 };
  }
  if (!cur) return { ...q, unshuffled: q.entries };
  const before = q.entries.slice(0, q.index);
  const after = shuffled(q.entries.slice(q.index + 1), random);
  return { ...q, entries: [...before, cur, ...after], unshuffled: q.entries, index: q.index };
}

export function cycleRepeat(q: QueueState): QueueState {
  const order: RepeatMode[] = ["off", "all", "one"];
  return { ...q, repeat: order[(order.indexOf(q.repeat) + 1) % order.length] };
}

/**
 * Indice del brano successivo, o null se la coda è finita.
 * `auto` = fine naturale del brano: solo lì "ripeti uno" rifà lo stesso brano;
 * il tasto "avanti" va comunque al successivo.
 */
export function nextIndex(q: QueueState, auto: boolean): number | null {
  if (q.entries.length === 0) return null;
  if (auto && q.repeat === "one") return q.index;
  if (q.index + 1 < q.entries.length) return q.index + 1;
  return q.repeat === "off" ? null : 0;
}

export function prevIndex(q: QueueState): number {
  if (q.index > 0) return q.index - 1;
  return q.repeat === "all" ? q.entries.length - 1 : 0;
}

export function jumpTo(q: QueueState, index: number): QueueState {
  return index >= 0 && index < q.entries.length ? { ...q, index } : q;
}

export function enqueue(q: QueueState, songs: Song[]): QueueState {
  const added = wrap(songs);
  const entries = [...q.entries, ...added];
  return {
    ...q,
    entries,
    unshuffled: q.unshuffled ? [...q.unshuffled, ...added] : null,
    index: q.index < 0 ? 0 : q.index,
  };
}

export function playNext(q: QueueState, songs: Song[]): QueueState {
  if (q.index < 0) return enqueue(q, songs);
  const added = wrap(songs);
  const entries = [...q.entries.slice(0, q.index + 1), ...added, ...q.entries.slice(q.index + 1)];
  return { ...q, entries, unshuffled: q.unshuffled ? [...q.unshuffled, ...added] : null };
}

export function removeAt(q: QueueState, index: number): QueueState {
  if (index < 0 || index >= q.entries.length) return q;
  const gone = q.entries[index].uid;
  const entries = q.entries.filter((_, i) => i !== index);
  let newIndex = q.index;
  if (index < q.index) newIndex--;
  else if (index === q.index) newIndex = Math.min(q.index, entries.length - 1);
  return {
    ...q,
    entries,
    unshuffled: q.unshuffled ? q.unshuffled.filter((e) => e.uid !== gone) : null,
    index: entries.length ? newIndex : -1,
  };
}

export function move(q: QueueState, from: number, to: number): QueueState {
  if (from === to || from < 0 || to < 0 || from >= q.entries.length || to >= q.entries.length) return q;
  const entries = q.entries.slice();
  const [item] = entries.splice(from, 1);
  entries.splice(to, 0, item);
  const cur = q.entries[q.index];
  return { ...q, entries, index: cur ? entries.findIndex((e) => e.uid === cur.uid) : q.index };
}
