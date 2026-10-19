import type { Song } from "./subsonic";

// Le regole per modificare una raccolta o una playlist, come sul telefono
// (UserLibrary.reorderPlaylist e sortPlaylist). Subsonic non sa spostare un brano:
// si riscrive la lista intera nell'ordine nuovo.

/** Sposta l'elemento in posizione [from] in posizione [to]. */
export function moveItem<T>(list: T[], from: number, to: number): T[] {
  if (from === to || from < 0 || from >= list.length) return list;
  const out = [...list];
  const [item] = out.splice(from, 1);
  out.splice(Math.max(0, Math.min(to, out.length)), 0, item);
  return out;
}

const collator = new Intl.Collator("it", { numeric: true, sensitivity: "base" });

/** "The Beatles" sta sotto la B: l'articolo inglese non conta. */
const sortKey = (s: string | undefined) => (s ?? "").replace(/^the\s+/i, "");

/** Ordina per titolo, o per artista e poi titolo. */
export function sortSongs(songs: Song[], by: "title" | "artist"): Song[] {
  return [...songs].sort((a, b) =>
    by === "title"
      ? collator.compare(a.title, b.title)
      : collator.compare(sortKey(a.artist), sortKey(b.artist)) || collator.compare(a.title, b.title),
  );
}

/** Le posizioni tra [a] e [b], comprese: Maiuscolo + clic su un intervallo. */
export function range(a: number, b: number): number[] {
  const [from, to] = a <= b ? [a, b] : [b, a];
  return Array.from({ length: to - from + 1 }, (_, i) => from + i);
}
