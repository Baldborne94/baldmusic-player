import { useSyncExternalStore } from "react";
import * as Q from "./queue";
import { autoplayNeeded, autoplayPick, storeAutoplay, storedAutoplay } from "./autoplay";
import { crossfadeApplies, crossfadeGains, storeCrossfade, storedCrossfade } from "./crossfade";
import { replayGainVolume, storedReplayGainMode, storeReplayGainMode, type ReplayGainMode } from "./replaygain";
import * as L from "./listening";
import { songRadio } from "./radio";
import { restore, snapshot } from "./resume";
import { getClient } from "./session";
import type { Song, SubsonicClient } from "./subsonic";

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
  /** «Continua ad ascoltare»: finita la coda, brani simili invece di fermarsi. */
  autoplay: boolean;
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

// La coda di prima (Ripresa), per questo server e utente: un altro server non ha quei brani.
const queueKey = (c: SubsonicClient) => `baldmusic.coda.${c.creds.url}|${c.creds.user}`;

function savedQueue(): ReturnType<typeof restore> {
  const c = getClient();
  try {
    return c ? restore(localStorage.getItem(queueKey(c))) : null;
  } catch {
    return null;
  }
}

const resumed = savedQueue();

let state: PlayerState = {
  queue: resumed?.queue ?? Q.emptyQueue,
  playing: false,
  buffering: false,
  position: resumed?.position ?? 0,
  duration: resumed ? (Q.current(resumed.queue)?.duration ?? 0) : 0,
  volume: storedVolume(),
  error: null,
  from: resumed?.from ?? null,
  sleepAt: null,
  replayGain: storedReplayGainMode(),
  crossfade: storedCrossfade(),
  autoplay: storedAutoplay(),
};

/** Il brano ripreso riparte da qui al primo play. */
let resumeAt: { uid: number; position: number } | null = resumed ? { uid: resumed.queue.entries[resumed.queue.index].uid, position: resumed.position } : null;

/** Quello che si sta ascoltando adesso: sentiti e saltati (listening.ts). */
let listening = L.freshListening();

let sleepTimer: ReturnType<typeof setTimeout> | null = null;

const listeners = new Set<() => void>();

function set(patch: Partial<PlayerState>): void {
  const before = state;
  state = { ...state, ...patch };
  if (state.queue !== before.queue || state.from !== before.from) saveQueue();
  listeners.forEach((l) => l());
}

let savedAt = 0;

/** Salva la coda per la Ripresa (a ogni cambio di coda, in pausa, ogni 10 s mentre suona). */
function saveQueue(): void {
  savedAt = Date.now();
  const c = getClient();
  if (!c) return;
  try {
    const snap = snapshot(state.queue, audio?.src ? audio.currentTime : state.position, state.from);
    if (snap) localStorage.setItem(queueKey(c), JSON.stringify(snap));
    else localStorage.removeItem(queueKey(c));
  } catch {
    // ignorato: la coda semplicemente non si ritrova alla prossima apertura
  }
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
  on("pause", () => {
    set({ playing: false });
    saveQueue();
  });
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
  // Il brano che sfuma è finito: sentito.
  noteLeft(true, true);
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
  maybeContinue();
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
  if (!a.paused && Date.now() - savedAt > 10_000) saveQueue();
  // Come sul telefono: un ascolto conta (più ascoltati, mix) solo quando non è più un salto.
  const song = Q.current(state.queue);
  if (song && !scrobbled && a.duration > 0 && L.countsAsPlay(a.currentTime, a.duration)) {
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
    // Il brano ripreso dopo aver riaperto la pagina riparte da dov'eri.
    const from = resumeAt?.uid === entry.uid ? resumeAt.position : 0;
    resumeAt = null;
    if (from > 0) el().currentTime = from;
    applyVolume();
    set({ position: from, duration: entry.song.duration ?? 0, error: null });
    client.scrobble(entry.song.id, false).catch(() => {});
  }
  updateMediaSession();
  maybeContinue();
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

/**
 * Si lascia il brano in corso per un altro ([forward] = avanti, [ended] = finito da solo):
 * sentito o saltato. Restituisce l'uid della voce da togliere dalla coda se è un salto.
 */
function noteLeft(forward: boolean, ended: boolean): number | null {
  const entry = state.queue.entries[state.queue.index];
  if (!entry) return null;
  const a = el();
  const duration = finite(a.duration) || entry.song.duration;
  listening = L.left(listening, entry.song.id, ended ? (duration ?? 0) : a.currentTime, duration, forward, ended, !entry.added, Date.now());
  return !ended && forward && !entry.added && listening.skipped.has(entry.song.id) ? entry.uid : null;
}

/** Va al brano [index]: il brano lasciato, se saltato, esce dalla coda e non torna in questo ascolto. */
function goTo(index: number, forward: boolean, ended: boolean): void {
  const gone = noteLeft(forward, ended);
  const q = Q.jumpTo(state.queue, index);
  setQueue(gone === null ? q : Q.removeUid(q, gone), true);
}

let continuedFor: number | null = null;

/** «Continua ad ascoltare»: parte l'ultimo brano della coda, si aggiunge la sua radio. */
function maybeContinue(): void {
  const q = state.queue;
  const entry = q.entries[q.index];
  if (!entry || continuedFor === entry.uid) return;
  if (!autoplayNeeded(state.autoplay, q.index < q.entries.length - 1, q.repeat === "off", state.sleepAt === -1)) return;
  continuedFor = entry.uid;
  songRadio(entry.song)
    .then((radio) => {
      const now = state.queue;
      // Nel frattempo è cambiato qualcosa (salto, coda nuova): niente.
      if (now.entries[now.index]?.uid !== entry.uid || now.index < now.entries.length - 1) return;
      const chosen = autoplayPick(radio, L.notAgain(listening, now.entries.map((e) => e.song.id)));
      if (chosen.length) set({ queue: Q.enqueue(now, chosen) });
    })
    .catch(() => {});
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
  goTo(i, true, auto);
}

// --- azioni --------------------------------------------------------------

export const player = {
  /**
   * Una coda nuova. [picked]: il brano [start] l'hai toccato tu, quindi suona anche se in questo
   * ascolto era già sentito o saltato; gli altri sentiti e saltati dello stesso contenitore restano fuori.
   */
  play(songs: Song[], start = 0, shuffle = false, from?: { label: string; id: string }, picked = false): void {
    const container = from?.id ?? null;
    listening = L.startingOn(listening, container, Date.now());
    const kept = L.keepFor(listening, songs.map((s) => s.id), start, picked, shuffle);
    const list = kept.kept.length === songs.length ? songs : kept.kept.map((i) => songs[i]);
    set({ from: from ?? null });
    setQueue(Q.playList(state.queue, list, kept.start, shuffle, Math.random, L.wrapsAround(container)), true);
  },
  /** Timer di spegnimento: minuti, 0 = a fine brano, null = spento. */
  setSleep(minutes: number | null): void {
    if (sleepTimer) clearTimeout(sleepTimer);
    sleepTimer = null;
    if (minutes === null) {
      set({ sleepAt: null });
      return maybeContinue();
    }
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
    const i = Q.prevIndex(state.queue);
    if (i === state.queue.index) return setQueue(state.queue, true);
    goTo(i, false, false);
  },
  seek(seconds: number): void {
    const a = el();
    if (a.src) a.currentTime = Math.max(0, Math.min(seconds, a.duration || seconds));
    else if (resumeAt) resumeAt = { ...resumeAt, position: Math.max(0, seconds) };
    set({ position: seconds });
  },
  jumpTo(index: number): void {
    if (index === state.queue.index || index < 0 || index >= state.queue.entries.length) return setQueue(state.queue, true);
    goTo(index, index > state.queue.index, false);
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
    // I già sentiti restano prima di quello in corso, col casuale acceso o spento.
    set({ queue: Q.toggleShuffle(state.queue, Math.random, (id) => listening.heard.has(id)) });
  },
  cycleRepeat(): void {
    set({ queue: Q.cycleRepeat(state.queue) });
    maybeContinue();
  },
  setAutoplay(on: boolean): void {
    set({ autoplay: on });
    storeAutoplay(on);
    maybeContinue();
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
