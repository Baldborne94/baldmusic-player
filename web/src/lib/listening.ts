// Mentre ascolti una raccolta, una playlist, un album o un mix: i brani già sentiti e quelli
// saltati, con le stesse regole del telefono (ListeningSession.kt e QueueOrder.kt). Un brano
// saltato esce dalla coda e non torna in questo ascolto (né col casuale rifatto, né con «ripeti
// tutto», né toccando un altro brano della stessa raccolta); uno già sentito non si ripropone
// prima di quelli che mancano. Un altro contenitore, o due ore senza ascoltare, e si ricomincia.
// Solo in memoria, come sul telefono.

/** Più di così senza ascoltare: è un ascolto nuovo. */
export const GAP_MS = 2 * 3600 * 1000;

export interface Listening {
  /** Il contenitore della coda ("playlist/x", "album/y"); null = niente da ricordare. */
  container: string | null;
  heard: Set<string>;
  skipped: Set<string>;
  lastAt: number;
}

export const freshListening = (container: string | null = null, now = 0): Listening => ({ container, heard: new Set(), skipped: new Set(), lastAt: now });

/**
 * Saltato se lasciato prima degli ultimi 30 s e prima dei tre quarti (la dissolvenza parte al
 * massimo 12 s prima della fine: non è un salto). Durata sconosciuta: prima di 30 s.
 */
export function isSkip(positionSec: number, durationSec?: number): boolean {
  if (!durationSec || !(durationSec > 0)) return positionSec < 30;
  return positionSec < Math.min(durationSec - 30, (durationSec * 3) / 4);
}

/** Un ascolto conta (più ascoltati, mix) quando non è più un salto: un brano saltato non è un brano che ti piace. */
export const countsAsPlay = (playedSec: number, durationSec?: number) => !isSkip(playedSec, durationSec);

/** Stai per suonare [container]: se è lo stesso di adesso (e da poco) si continua, altrimenti si ricomincia. */
export function startingOn(l: Listening, container: string | null, now: number): Listening {
  return container !== null && container === l.container && now - l.lastAt <= GAP_MS ? { ...l, lastAt: now } : freshListening(container, now);
}

/**
 * Lasciato [songId] arrivati a [positionSec] su [durationSec]: finito o sentito quasi tutto =
 * sentito; lasciato prima andando avanti ([forward], non «precedente») = saltato. Contano solo i
 * brani scelti dal contenitore ([ofContainer]), non quelli aggiunti in coda o da «Continua ad ascoltare».
 */
export function left(
  l: Listening,
  songId: string,
  positionSec: number,
  durationSec: number | undefined,
  forward: boolean,
  ended: boolean,
  ofContainer: boolean,
  now: number,
): Listening {
  if (l.container === null || !ofContainer) return l;
  if (ended || !isSkip(positionSec, durationSec)) {
    const skipped = new Set(l.skipped);
    skipped.delete(songId);
    return { ...l, heard: new Set(l.heard).add(songId), skipped, lastAt: now };
  }
  if (forward) return { ...l, skipped: new Set(l.skipped).add(songId), lastAt: now };
  return { ...l, lastAt: now };
}

/** Cosa «Continua ad ascoltare» non deve aggiungere: i brani in coda e quelli già sentiti o saltati. */
export function notAgain(l: Listening, queueIds: Iterable<string>): Set<string> {
  return new Set([...queueIds, ...l.heard, ...l.skipped]);
}

/**
 * Una coda nuova dello stesso contenitore ([ids], partendo da [start]; [picked] = quel brano
 * l'hai toccato tu): fuori i saltati e i già sentiti, tranne quello che hai scelto. Se non resta
 * niente (raccolta già sentita tutta) la coda resta intera. Senza un brano scelto si parte dal
 * primo tenuto, o (col casuale) da uno a caso.
 */
export function keepFor(l: Listening, ids: string[], start: number, picked: boolean, shuffle = false, random = Math.random): { kept: number[]; start: number } {
  const all = ids.map((_, i) => i);
  if (l.container === null || (l.heard.size === 0 && l.skipped.size === 0)) return { kept: all, start };
  const done = (id: string) => l.heard.has(id) || l.skipped.has(id);
  const kept = all.filter((i) => (picked && i === start) || !done(ids[i]));
  if (kept.length === 0 || (picked && kept.length === 1 && kept[0] === start)) return { kept: all, start };
  if (picked) return { kept, start: kept.indexOf(start) };
  const at = kept.indexOf(start);
  return { kept, start: at >= 0 ? at : shuffle ? Math.floor(random() * kept.length) : 0 };
}

/**
 * Le tue raccolte, playlist, preferiti e mix suonano tutti anche partendo dall'ultimo brano
 * (da lì alla fine e poi dall'inizio); album, artisti e ricerche restano in ordine dal brano toccato.
 */
export function wrapsAround(container: string | null | undefined): boolean {
  if (!container) return false;
  return container === "liked" || container.startsWith("playlist/") || container.startsWith("mix/");
}
