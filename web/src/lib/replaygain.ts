// Volume uguale tra i brani (ReplayGain), con le stesse regole del telefono
// (Loudness.kt): si abbassa e basta, mai oltre il picco, senza tag il brano suona
// com'è. Niente effetti né equalizzatore: cambia solo il volume.

/** Album (predefinito: dentro un disco restano le differenze volute), Brano, Spento. */
export type ReplayGainMode = "album" | "track" | "off";

/** OpenSubsonic (Navidrome): il ReplayGain letto dai tag del file, in dB e picchi lineari. */
export interface ReplayGain {
  trackGain?: number;
  albumGain?: number;
  trackPeak?: number;
  albumPeak?: number;
}

/** Volume da 0 a 1 per un brano con la modalità scelta. */
export function replayGainVolume(rg: ReplayGain | undefined, mode: ReplayGainMode): number {
  if (!rg || mode === "off") return 1;
  const album = rg.albumGain !== undefined && rg.albumGain !== null;
  const track = rg.trackGain !== undefined && rg.trackGain !== null;
  const [gain, peak] =
    mode === "album" ? (album ? [rg.albumGain, rg.albumPeak] : [rg.trackGain, rg.trackPeak]) : track ? [rg.trackGain, rg.trackPeak] : [rg.albumGain, rg.albumPeak];
  if (gain === undefined || gain === null) return 1;
  let v = Math.pow(10, gain / 20);
  if (peak && peak > 0) v = Math.min(v, 1 / peak);
  return Math.max(0, Math.min(1, v));
}

const KEY = "baldmusic.replaygain";

export function storedReplayGainMode(): ReplayGainMode {
  try {
    const v = localStorage.getItem(KEY);
    return v === "track" || v === "off" ? v : "album";
  } catch {
    return "album";
  }
}

export function storeReplayGainMode(mode: ReplayGainMode): void {
  try {
    localStorage.setItem(KEY, mode);
  } catch {
    // ignorato: si torna ad "album" alla prossima apertura
  }
}
