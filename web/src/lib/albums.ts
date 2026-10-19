import type { Album } from "./subsonic";

/** L'anno della prima uscita, se Navidrome lo sa (tag di Picard); altrimenti quello dell'edizione. */
export function albumYear(a: Album): number {
  return a.originalReleaseDate?.year || a.year || 0;
}

/**
 * Un album vero, da mettere tra gli album: almeno due brani e non un singolo.
 * Il resto (singoli, o un brano solo di un disco) sta tra i brani sparsi dell'artista.
 */
export function isRealAlbum(a: Album): boolean {
  return (a.songCount ?? 2) > 1 && !(a.releaseTypes ?? []).some((t) => t.toLowerCase() === "single");
}

/** Le sezioni della discografia di un artista, in quest'ordine (come Discography.kt sul telefono). */
export const RELEASE_KINDS = ["Album", "EP", "Dal vivo", "Colonne sonore", "Compilation", "Altro"] as const;
export type ReleaseKind = (typeof RELEASE_KINDS)[number];

const OTHER_TYPES = new Set(["remix", "dj-mix", "mixtape", "mixtape/street", "demo", "interview", "audiobook", "audio drama", "spokenword", "broadcast"]);

/** Il tipo di un disco dai tipi di MusicBrainz: il secondario conta più del primario ("album; live" è dal vivo). */
export function releaseKind(a: Album): ReleaseKind {
  const t = new Set((a.releaseTypes ?? []).map((x) => x.trim().toLowerCase()));
  if (t.has("live")) return "Dal vivo";
  if (t.has("soundtrack")) return "Colonne sonore";
  if (t.has("compilation")) return "Compilation";
  if (t.has("ep")) return "EP";
  if ([...t].some((x) => OTHER_TYPES.has(x))) return "Altro";
  return "Album";
}

/** La discografia per tipo, ogni tipo dal più recente; i tipi vuoti non ci sono. */
export function discography(albums: Album[]): { kind: ReleaseKind; albums: Album[] }[] {
  return RELEASE_KINDS.map((kind) => ({ kind, albums: albums.filter((a) => releaseKind(a) === kind).sort((a, b) => albumYear(b) - albumYear(a)) })).filter((g) => g.albums.length > 0);
}
