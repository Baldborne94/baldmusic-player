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
