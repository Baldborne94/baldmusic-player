// Dissolvenza incrociata tra un brano e l'altro, come i DJ: negli ultimi secondi il
// brano che finisce si abbassa mentre il successivo parte e sale. Solo volume: i file
// restano originali. Stesse regole del telefono (Crossfade.kt).

/** Durate tra cui scegliere, in secondi; 0 = spenta. */
export const CROSSFADE_CHOICES = [0, 3, 6, 9, 12];
export const DEFAULT_CROSSFADE = 6;

/**
 * Si sfuma? Mai tra due brani dello stesso album (dal vivo e concept restano
 * attaccati) e mai su brani troppo corti per avere un inizio e una fine.
 */
export function crossfadeApplies(seconds: number, durationSeconds: number, sameAlbum: boolean): boolean {
  return seconds > 0 && !sameAlbum && durationSeconds >= seconds * 2 + 5;
}

/** Volumi a metà sfumatura ([t] da 0 a 1): uscente ed entrante, a potenza costante. */
export function crossfadeGains(t: number): [number, number] {
  const x = Math.max(0, Math.min(1, t));
  return [Math.cos((x * Math.PI) / 2), Math.sin((x * Math.PI) / 2)];
}

const KEY = "baldmusic.crossfade";

export function storedCrossfade(): number {
  try {
    const raw = localStorage.getItem(KEY);
    const v = raw === null ? DEFAULT_CROSSFADE : Number(raw);
    return CROSSFADE_CHOICES.includes(v) ? v : DEFAULT_CROSSFADE;
  } catch {
    return DEFAULT_CROSSFADE;
  }
}

export function storeCrossfade(seconds: number): void {
  try {
    localStorage.setItem(KEY, String(seconds));
  } catch {
    // ignorato: alla prossima apertura si torna a quella predefinita
  }
}
