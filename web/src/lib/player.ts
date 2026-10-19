import { useSyncExternalStore } from "react";
import * as Q from "./queue";
import { crossfadeApplies, crossfadeGains, storeCrossfade, storedCrossfade } from "./crossfade";
import { replayGainVolume, storedReplayGainMode, storeReplayGainMode, type ReplayGainMode } from "./replaygain";
import { getClient } from "./session";
import type { Song } from "./subsonic";

// Il player: un solo <audio> per tutta l'app, la coda pura di queue.ts,
// e la Media Session API (tasti multimediali, schermata di blocco, notifica
// Android sul tablet).

export interface PlayerState {
  queue: Q.QueueState;
  playing: boolean;
  buffering: boolean;
  position: number;
  duration: number;
  volume: number;
  error: string | null;
  /** Da dove arriva la coda: nome mostrato e ID della pagina (per il tasto play/pausa). */
  from: { label: string; id: string } | null;
  /** Fine del timer di spegnimento (ms), -1 = a fine brano, null = spento. */
  sleepAt: number | null;
  /** Volume uguale tra i brani: album, brano o spento. */
  replayGain: ReplayGainMode;
  /** Dissolvenza incrociata tra i brani, in secondi; 0 = spenta. */
  crossfade: number;
}

const VOLUME_KEY = "baldmusic.volume";

function storedVolume(): number {
  try {
    const v = Number(localStorage.getItem(VOLUME_KEY));
    return Number.isFinite(v) && v > 0 && v <= 1 ? v : 1;
  } catch {
    return 1;
  }
}

let state: PlayerState = {
  queue: Q.emptyQueue,
  playing: false,
  buffering: false,
  position: 0,
  duration: 0,
  volume: storedVolume(),
  error: null,
  from: null,
  sleepAt: null,
  replayGain: storedReplayGainMode(),
  crossfade: storedCrossfade(),
};

let sleepTimer: ReturnType<typeof setTimeout> | null = null;

const listeners = new Set<() => void>();

function set(patch: Partial<PlayerState>): void {
  state = { ...state, ...patch };
  listeners.forEach((l) => l());
}

function subscribe(l: () => void): () => void {
  listeners.add(l);
  return () => listeners.delete(l);
}

export function usePlayer(): PlayerState {
  return useSyncExternalStore(subscribe, () => state);
}

/**
 * Solo un pezzo dello stato (un valore semplice): chi lo usa si ridisegna quando cambia
 * quello, non a ogni avanzamento della posizione. Serve agli elenchi con migliaia di brani.
 */
export function usePlayerSelect<T>(pick: (s: PlayerState) => T): T {
  return useSyncExternalStore(subscribe, () => pick(state));
}

export function getPlayerState(): PlayerState {
  return state;
}

// --- audio ---------------------------------------------------------------

let audio: HTMLAudioElement | null = null;
let loadedUid: number | null = null;
/** Ascolto già registrato sul server per il brano corrente. */
let scrobbled = false;

// Quasi senza pause tra i brani: negli ultimi secondi il brano dopo si carica in un
// secondo <audio>; alla fine si passa a quello, già pronto, invece di caricarlo da capo.
let spare: HTMLAudioElement | null = null;
let spareUid: number | null = null;
const PRELOAD_SECONDS = 30;

function makeAudio(): HTMLAudioElement {
  const a = new Audio();
  a.preload = "auto";
  // Solo l'<audio> che sta suonando aggiorna lo stato: quello di scorta tace.
  const on = (type: string, f: () => void) => a.addEventListener(type, () => a === audio && f());
  on("play", () => set({ playing: true, error: null }));
  on("pause", () => set({ playing: false }));
  on("waiting", () => set({ buffering: true }));
  on("playing", () => set({ buffering: false }));
  on("canplay", () => set({ buffering: false }));
  on("durationchange", () => set({ duration: finite(a.duration) }));
  on("timeupdate", onTime);
  on("ended", () => advance(true));
  on("error", () => {
    if (!a.src) return;
    set({ playing: false, buffering: false, error: "Impossibile riprodurre questo brano" });
  });
  return a;
}

function el(): HTMLAudioElement {
  if (audio) return audio;
  audio = makeAudio();
  audio.volume = state.volume;
  return audio;
}

/** Carica in anticipo il brano che viene dopo, se non è già pronto. */
function preloadNext(): void {
  const i = Q.nextIndex(state.queue, true);
  const entry = i === null || i === state.queue.index ? undefined : state.queue.entries[i];
  const client = getClient();
  if (!entry || !client || spareUid === entry.uid) return;
  spare ??= makeAudio();
  spare.src = client.streamUrl(entry.song.id);
  spare.load();
  spareUid = entry.uid;
}

// Dissolvenza incrociata: il brano che finisce continua su [fadingOut] abbassandosi,
// mentre quello dopo parte sull'<audio> preparato e sale ([crossIn]).
let fadingOut: HTMLAudioElement | null = null;
let fadeTimer: ReturnType<typeof setInterval> | null = null;
let crossIn = 1;

/** Negli ultimi secondi, se le regole lo permettono e il brano dopo è pronto, si comincia a sfumare. */
function maybeCrossfade(a: HTMLAudioElement): void {
  const secs = state.crossfade;
  if (fadingOut || secs <= 0 || state.sleepAt === -1 || state.queue.repeat === "one" || !(a.duration > 0)) return;
  const remaining = a.duration - a.currentTime;
  if (remaining > secs) return;
  const i = Q.nextIndex(state.queue, true);
  if (i === null || i === state.queue.index) return;
  const cur = Q.current(state.queue);
  const next = state.queue.entries[i];
  if (!crossfadeApplies(secs, a.duration, !!cur?.albumId && cur.albumId === next.song.albumId)) return;
  // Il brano dopo dev'essere già caricato: altrimenti si passa normalmente, senza buchi.
  if (!spare || spareUid !== next.uid || spare.readyState < HTMLMediaElement.HAVE_FUTURE_DATA) return;
  startCrossfade(i, Math.max(remaining, 0.5));
}

function startCrossfade(index: number, seconds: number): void {
  const entry = state.queue.entries[index];
  const old = el();
  const outVol = old.volume;
  fadingOut = old;
  audio = spare;
  spare = null;
  spareUid = null;
  crossIn = 0;
  loadedUid = entry.uid;
  scrobbled = false;
  set({ queue: Q.jumpTo(state.queue, index), position: 0, duration: entry.song.duration ?? 0, error: null });
  applyVolume();
  getClient()?.scrobble(entry.song.id, false).catch(() => {});
  updateMediaSession();
  el().play().catch(() => {});
  const t0 = performance.now();
  fadeTimer = setInterval(() => {
    const t = (performance.now() - t0) / (seconds * 1000);
    if (t >= 1) return endCrossfade();
    const [out, inn] = crossfadeGains(t);
    old.volume = outVol * out;
    crossIn = inn;
    applyVolume();
  }, 50);
}

/** Fine (o interruzione) della sfumatura: il brano vecchio si ferma, quello nuovo a volume pieno. */
function endCrossfade(): void {
  if (fadeTimer) clearInterval(fadeTimer);
  fadeTimer = null;
  const old = fadingOut;
  fadingOut = null;
  crossIn = 1;
  if (old) {
    old.pause();
    old.removeAttribute("src");
    old.load();
    spare ??= old;
  }
  if (audio) applyVolume();
}

/** Se il brano da suonare è quello già caricato in anticipo, lo si scambia con l'attuale. */
function swapToSpare(uid: number): boolean {
  if (!spare || spareUid !== uid) return false;
  const old = el();
  audio = spare;
  spare = old;
  spareUid = null;
  old.pause();
  old.removeAttribute("src");
  old.load();
  return true;
}

const finite = (n: number) => (Number.isFinite(n) ? n : 0);

/** Il volume scelto dall'utente per quello del ReplayGain del brano in corso. */
function applyVolume(): void {
  el().volume = state.volume * replayGainVolume(Q.current(state.queue)?.replayGain, state.replayGain) * crossIn;
}

function onTime(): void {
  const a = el();
  set({ position: a.currentTime });
  updatePositionState();
  if (a.duration > 0 && a.duration - a.currentTime < PRELOAD_SECONDS) preloadNext();
  maybeCrossfade(a);
  // Regola di Last.fm/Subsonic: un ascolto conta dopo metà brano o 4 minuti.
  const song = Q.current(state.queue);
  if (song && !scrobbled && a.duration > 0 && (a.currentTime >= a.duration / 2 || a.currentTime >= 240)) {
    scrobbled = true;
    getClient()?.scrobble(song.id, true).catch(() => {});
  }
}

function load(autoplay: boolean): void {
  // Qualunque cambio di coda o di brano interrompe una sfumatura in corso.
  endCrossfade();
  const entry = state.queue.entries[state.queue.index];
  const a = el();
  if (!entry) {
    a.pause();
    a.removeAttribute("src");
    a.load();
    loadedUid = null;
    set({ playing: false, position: 0, duration: 0 });
    updateMediaSession();
    return;
  }
  const client = getClient();
  if (!client) return;
  if (loadedUid !== entry.uid) {
    loadedUid = entry.uid;
    scrobbled = false;
    if (!swapToSpare(entry.uid)) el().src = client.streamUrl(entry.song.id);
    applyVolume();
    set({ position: 0, duration: entry.song.duration ?? 0, error: null });
    client.scrobble(entry.song.id, false).catch(() => {});
  }
  updateMediaSession();
  if (autoplay) {
    set({ buffering: true });
    // el(), non a: dopo lo scambio suona l'<audio> preparato in anticipo.
    el().play().catch((e: unknown) => {
      // NotAllowedError: il browser vuole un gesto dell'utente prima di suonare.
      set({ playing: false, buffering: false, error: e instanceof DOMException && e.name === "NotAllowedError" ? null : "Riproduzione non riuscita" });
    });
  }
}

function setQueue(queue: Q.QueueState, autoplay: boolean): void {
  set({ queue });
  load(autoplay);
}

function advance(auto: boolean): void {
  if (auto && state.sleepAt === -1) {
    // Timer "a fine brano": il brano è finito, ci si ferma qui.
    set({ sleepAt: null, playing: false });
    return;
  }
  const i = Q.nextIndex(state.queue, auto);
  if (i === null) {
    el().pause();
    set({ playing: false });
    return;
  }
  if (i === state.queue.index) {
    // Ripeti uno, oppure coda di un solo brano in "ripeti tutto".
    const a = el();
    a.currentTime = 0;
    scrobbled = false;
    a.play().catch(() => {});
    return;
  }
  setQueue(Q.jumpTo(state.queue, i), true);
}

// --- azioni --------------------------------------------------------------

export const player = {
  play(songs: Song[], start = 0, shuffle = false, from?: { label: string; id: string }): void {
    set({ from: from ?? null });
    setQueue(Q.playList(state.queue, songs, start, shuffle), true);
  },
  /** Timer di spegnimento: minuti, 0 = a fine brano, null = spento. */
  setSleep(minutes: number | null): void {
    if (sleepTimer) clearTimeout(sleepTimer);
    sleepTimer = null;
    if (minutes === null) return set({ sleepAt: null });
    if (minutes === 0) return set({ sleepAt: -1 });
    const at = Date.now() + minutes * 60_000;
    set({ sleepAt: at });
    sleepTimer = setTimeout(() => {
      player.pause();
      set({ sleepAt: null });
    }, minutes * 60_000);
  },
  toggle(): void {
    if (el().paused) player.resume();
    else player.pause();
  },
  resume(): void {
    const a = el();
    if (!Q.current(state.queue)) return;
    if (!a.src) load(true);
    else a.play().catch(() => {});
  },
  pause(): void {
    endCrossfade();
    el().pause();
  },
  next(): void {
    advance(false);
  },
  prev(): void {
    const a = el();
    // Come ogni player: oltre i 3 secondi "indietro" riavvolge il brano.
    if (a.currentTime > 3) {
      a.currentTime = 0;
      return;
    }
    setQueue(Q.jumpTo(state.queue, Q.prevIndex(state.queue)), true);
  },
  seek(seconds: number): void {
    const a = el();
    if (a.src) a.currentTime = Math.max(0, Math.min(seconds, a.duration || seconds));
    set({ position: seconds });
  },
  jumpTo(index: number): void {
    setQueue(Q.jumpTo(state.queue, index), true);
  },
  enqueue(songs: Song[]): void {
    const wasEmpty = state.queue.entries.length === 0;
    setQueue(Q.enqueue(state.queue, songs), wasEmpty);
  },
  playNext(songs: Song[]): void {
    const wasEmpty = state.queue.entries.length === 0;
    setQueue(Q.playNext(state.queue, songs), wasEmpty);
  },
  removeAt(index: number): void {
    const playingRemoved = index === state.queue.index;
    setQueue(Q.removeAt(state.queue, index), playingRemoved && state.playing);
  },
  move(from: number, to: number): void {
    set({ queue: Q.move(state.queue, from, to) });
  },
  toggleShuffle(): void {
    set({ queue: Q.toggleShuffle(state.queue) });
  },
  cycleRepeat(): void {
    set({ queue: Q.cycleRepeat(state.queue) });
  },
  setVolume(v: number): void {
    const volume = Math.max(0, Math.min(1, v));
    set({ volume });
    applyVolume();
    try {
      localStorage.setItem(VOLUME_KEY, String(volume));
    } catch {
      // ignorato: il volume semplicemente non viene ricordato
    }
  },
  clear(): void {
    setQueue(Q.emptyQueue, false);
  },
  setCrossfade(seconds: number): void {
    set({ crossfade: seconds });
    storeCrossfade(seconds);
  },
  setReplayGain(mode: ReplayGainMode): void {
    set({ replayGain: mode });
    storeReplayGainMode(mode);
    applyVolume();
  },
  /** Aggiorna il brano in coda dopo un "mi piace", senza toccare la riproduzione. */
  patchSong(id: string, patch: Partial<Song>): void {
    const mapEntries = (es: Q.Entry[]) => es.map((e) => (e.song.id === id ? { ...e, song: { ...e.song, ...patch } } : e));
    const q = state.queue;
    set({ queue: { ...q, entries: mapEntries(q.entries), unshuffled: q.unshuffled ? mapEntries(q.unshuffled) : null } });
  },
};

// --- Media Session -------------------------------------------------------

function updateMediaSession(): void {
  if (!("mediaSession" in navigator)) return;
  const song = Q.current(state.queue);
  if (!song) {
    navigator.mediaSession.metadata = null;
    return;
  }
  const client = getClient();
  const artwork = client && song.coverArt ? [96, 256, 512].map((size) => ({ src: client.coverUrl(song.coverArt, size)!, sizes: `${size}x${size}` })) : [];
  navigator.mediaSession.metadata = new MediaMetadata({
    title: song.title,
    artist: song.artist ?? "",
    album: song.album ?? "",
    artwork,
  });
}

function updatePositionState(): void {
  if (!("mediaSession" in navigator) || !navigator.mediaSession.setPositionState) return;
  const a = el();
  if (!(a.duration > 0)) return;
  try {
    navigator.mediaSession.setPositionState({ duration: a.duration, position: Math.min(a.currentTime, a.duration), playbackRate: a.playbackRate });
  } catch {
    // Alcuni browser rifiutano valori durante il caricamento: si riprova al prossimo timeupdate.
  }
}

if (typeof navigator !== "undefined" && "mediaSession" in navigator) {
  const ms = navigator.mediaSession;
  const handlers: [MediaSessionAction, MediaSessionActionHandler][] = [
    ["play", () => player.resume()],
    ["pause", () => player.pause()],
    ["nexttrack", () => player.next()],
    ["previoustrack", () => player.prev()],
    ["seekto", (d) => d.seekTime !== undefined && player.seek(d.seekTime)],
    ["seekbackward", (d) => player.seek(el().currentTime - (d.seekOffset ?? 10))],
    ["seekforward", (d) => player.seek(el().currentTime + (d.seekOffset ?? 10))],
  ];
  for (const [action, handler] of handlers) {
    try {
      ms.setActionHandler(action, handler);
    } catch {
      // Azione non supportata da questo browser.
    }
  }
}
