import type { Song } from "./subsonic";

// «Continua ad ascoltare», come sul telefono (Autoplay.kt): quando parte l'ultimo brano della
// coda, invece di fermarsi alla fine si aggiungono brani dalla radio del brano (mood e stile,
// radio.ts). Si aggiungono subito, così il brano dopo si carica in anticipo e la dissolvenza c'è.

/** Quanti brani si aggiungono per volta. */
export const AUTOPLAY_BATCH = 25;

/**
 * Se aggiungere adesso: attivo nelle Impostazioni, nessun brano dopo quello in corso, senza
 * «ripeti» (che ricomincia da sé) e senza il timer «a fine brano» (che sta per fermare tutto).
 */
export function autoplayNeeded(enabled: boolean, hasNext: boolean, repeatOff: boolean, endOfTrackTimer: boolean): boolean {
  return enabled && !hasNext && repeatOff && !endOfTrackTimer;
}

/** I brani da aggiungere tra quelli della radio: niente di già in coda, sentito o saltato ([notAgain]), niente doppioni. */
export function autoplayPick(candidates: Song[], notAgain: Set<string>, max = AUTOPLAY_BATCH): Song[] {
  const seen = new Set(notAgain);
  const out: Song[] = [];
  for (const s of candidates) {
    if (out.length >= max) break;
    if (seen.has(s.id)) continue;
    seen.add(s.id);
    out.push(s);
  }
  return out;
}

const KEY = "baldmusic.continua";

/** Attivo di serie, come sul telefono. */
export function storedAutoplay(): boolean {
  try {
    return localStorage.getItem(KEY) !== "off";
  } catch {
    return true;
  }
}

export function storeAutoplay(on: boolean): void {
  try {
    localStorage.setItem(KEY, on ? "on" : "off");
  } catch {
    // ignorato: alla prossima apertura torna attivo
  }
}
