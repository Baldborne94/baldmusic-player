import type { DragEvent } from "react";
import type { Song } from "./subsonic";

// Brani trascinati da un elenco su una playlist o una raccolta della barra laterale, come
// in Spotify sul PC. Restano nella stessa pagina: basta ricordarli qui, il tipo dice solo
// a chi riceve che sono brani.

export const SONGS_TYPE = "application/x-baldmusic-songs";

let dragged: Song[] = [];

export function startSongDrag(e: DragEvent, songs: Song[]): void {
  dragged = songs;
  e.dataTransfer.effectAllowed = "copyMove";
  e.dataTransfer.setData(SONGS_TYPE, songs.map((s) => s.id).join(","));
  e.dataTransfer.setData("text/plain", songs.map((s) => (s.artist ? `${s.artist} - ${s.title}` : s.title)).join("\n"));
  // Un'etichetta col titolo, o quanti sono, al posto dell'immagine di tutta la riga.
  const tag = document.createElement("div");
  tag.className = "drag-tag";
  tag.textContent = songs.length === 1 ? songs[0].title : `${songs.length} brani`;
  document.body.appendChild(tag);
  e.dataTransfer.setDragImage(tag, -12, -12);
  setTimeout(() => tag.remove(), 0);
}

export const isSongDrag = (e: DragEvent) => e.dataTransfer.types.includes(SONGS_TYPE);

export const draggedSongs = () => dragged;

export function endSongDrag(): void {
  dragged = [];
}
