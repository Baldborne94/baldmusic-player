import { afterEach, describe, expect, it, vi } from "vitest";
import { CREDS, fakeFetch, memoryStorage } from "../test/env";
import { FakeAudio, streamedId } from "../test/fakeAudio";
import { FakeNavidrome, type FakeInit } from "../test/fakeNavidrome";
import type { Song } from "./subsonic";

// Il player senza audio vero: <audio> finti (FakeAudio) che il test fa avanzare a mano.
// useSyncExternalStore si sostituisce per vedere cosa leggerebbe React.
const react = vi.hoisted(() => ({ subscribe: null as null | ((l: () => void) => () => void), get: null as null | (() => unknown) }));
vi.mock("react", async (importOriginal) => ({
  ...(await importOriginal<typeof import("react")>()),
  useSyncExternalStore: (subscribe: (l: () => void) => () => void, get: () => unknown) => {
    react.subscribe = subscribe;
    react.get = get;
    return get();
  },
}));

async function open(storage: Storage = memoryStorage(), init: FakeInit = {}) {
  vi.resetModules();
  FakeAudio.all = [];
  const server = new FakeNavidrome(init);
  vi.stubGlobal("localStorage", storage);
  vi.stubGlobal("fetch", fakeFetch(server));
  vi.stubGlobal("Audio", FakeAudio);
  vi.stubGlobal("HTMLMediaElement", { HAVE_FUTURE_DATA: 3 });
  (await import("./session")).saveCredentials(CREDS);
  const mod = await import("./player");
  return { ...mod, server, storage };
}

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

const song = (id: string, extra: Partial<Song> = {}): Song => ({ id, title: `Brano ${id}`, duration: 200, albumId: `al-${id}`, ...extra });
const list = (...ids: string[]) => ids.map((id) => song(id));

/** L'<audio> che sta caricando il brano [id]. */
const audioOf = (id: string) => FakeAudio.all.find((a) => streamedId(a) === id);
const currentId = (p: Awaited<ReturnType<typeof open>>) => p.getPlayerState().queue.entries[p.getPlayerState().queue.index]?.song.id;
/** Fa partire il brano dopo l'evento play (le promesse di play() si risolvono). */
const flush = () => new Promise((r) => setTimeout(r, 0));

describe("player: coda", () => {
  it("suona il file originale del brano scelto e lo segnala al server", async () => {
    const p = await open();
    p.player.play(list("a", "b", "c"), 1, false, { label: "Album", id: "album/x" });
    const a = audioOf("b")!;
    expect(new URL(a.src).searchParams.get("format")).toBe("raw");
    expect(a.paused).toBe(false);
    expect(p.getPlayerState()).toMatchObject({ playing: true, from: { id: "album/x" } });
    await flush();
    expect(p.server.callsOf("scrobble").map((c) => [c.params.get("id"), c.params.get("submission")])).toEqual([["b", "false"]]);
  });

  it("a fine brano passa al successivo; a fine coda si ferma", async () => {
    const p = await open();
    p.player.play(list("a", "b"));
    audioOf("a")!.ready(200).end();
    expect(currentId(p)).toBe("b");
    expect(audioOf("b")!.paused).toBe(false);
    audioOf("b")!.ready(200).end();
    expect(currentId(p)).toBe("b");
    expect(p.getPlayerState().playing).toBe(false);
  });

  it("ripeti tutto ricomincia dall'inizio, ripeti uno rifà lo stesso brano", async () => {
    const p = await open();
    p.player.play(list("a", "b"));
    p.player.cycleRepeat();
    expect(p.getPlayerState().queue.repeat).toBe("all");
    p.player.jumpTo(1);
    audioOf("b")!.ready(200).end();
    expect(currentId(p)).toBe("a");

    p.player.cycleRepeat();
    expect(p.getPlayerState().queue.repeat).toBe("one");
    const a = audioOf("a")!.ready(200).at(150);
    a.end();
    expect(currentId(p)).toBe("a");
    expect(a.currentTime).toBe(0);
    expect(a.paused).toBe(false);
    // Il tasto avanti va comunque al successivo.
    p.player.next();
    expect(currentId(p)).toBe("b");
  });

  it("casuale: il brano in corso resta, gli altri si mescolano; spento torna l'ordine", async () => {
    const p = await open();
    p.player.play(list("a", "b", "c", "d", "e"), 2);
    p.player.toggleShuffle();
    const ids = () => p.getPlayerState().queue.entries.map((e) => e.song.id);
    expect(currentId(p)).toBe("c");
    expect([...ids()].sort()).toEqual(["a", "b", "c", "d", "e"]);
    p.player.toggleShuffle();
    expect(ids()).toEqual(["a", "b", "c", "d", "e"]);
    expect(currentId(p)).toBe("c");
  });

  it("indietro oltre i 3 secondi riavvolge, prima va al brano prima", async () => {
    const p = await open();
    p.player.play(list("a", "b"), 1);
    const b = audioOf("b")!.ready(200).at(10);
    p.player.prev();
    expect(b.currentTime).toBe(0);
    expect(currentId(p)).toBe("b");
    b.at(2);
    p.player.prev();
    expect(currentId(p)).toBe("a");
  });

  it("togliere dalla coda il brano che suona fa partire il successivo", async () => {
    const p = await open();
    p.player.play(list("a", "b", "c"));
    p.player.removeAt(0);
    expect(currentId(p)).toBe("b");
    expect(audioOf("b")!.paused).toBe(false);
  });

  it("l'ascolto conta quando non è più un salto (tre quarti o ultimi 30 s), una volta sola", async () => {
    const p = await open();
    p.player.play(list("a"));
    const a = audioOf("a")!.ready(200);
    a.at(100);
    await flush();
    // A metà è ancora un salto: non conta tra i più ascoltati (prima contava già qui).
    expect(p.server.callsOf("scrobble").filter((c) => c.params.get("submission") === "true")).toHaveLength(0);
    a.at(150);
    a.at(180);
    await flush();
    expect(p.server.callsOf("scrobble").filter((c) => c.params.get("submission") === "true")).toHaveLength(1);
  });

  it("timer «a fine brano»: alla fine si ferma invece di passare al successivo", async () => {
    const p = await open();
    p.player.play(list("a", "b"));
    p.player.setSleep(0);
    expect(p.getPlayerState().sleepAt).toBe(-1);
    audioOf("a")!.ready(200).end();
    expect(currentId(p)).toBe("a");
    expect(p.getPlayerState()).toMatchObject({ sleepAt: null, playing: false });
  });

  it("un errore di caricamento si dice; quello di un <audio> svuotato no", async () => {
    const p = await open();
    p.player.play(list("a"));
    audioOf("a")!.fire("error");
    expect(p.getPlayerState().error).toBe("Impossibile riprodurre questo brano");
    p.player.clear();
    const a = FakeAudio.all[0];
    expect(a.src).toBe("");
    p.player.play(list("b"));
    expect(p.getPlayerState().error).toBeNull();
    p.player.clear();
    a.fire("error");
    expect(p.getPlayerState().error).toBeNull();
  });
});

describe("player: come sul telefono", () => {
  const ids = (p: Awaited<ReturnType<typeof open>>) => p.getPlayerState().queue.entries.map((e) => e.song.id);
  const RACCOLTA = { label: "Raccolta", id: "playlist/r" };

  it("una raccolta partita dall'ultimo brano suona tutta prima di fermarsi", async () => {
    const p = await open();
    p.player.setAutoplay(false);
    p.player.play(list("a", "b", "c"), 2, false, RACCOLTA, true);
    expect(ids(p)).toEqual(["c", "a", "b"]);
    audioOf("c")!.ready(200).end();
    expect(currentId(p)).toBe("a");
  });

  it("un brano saltato esce dalla coda e non torna, né con ripeti tutto né ripartendo dalla raccolta", async () => {
    const p = await open();
    p.player.setAutoplay(false);
    p.player.play(list("a", "b", "c"), 0, false, RACCOLTA, true);
    p.player.cycleRepeat();
    audioOf("a")!.ready(200).at(20);
    p.player.next();
    expect(ids(p)).toEqual(["b", "c"]);
    expect(currentId(p)).toBe("b");
    // Ripeti tutto: dopo c si torna a b, non ad a.
    audioOf("b")!.ready(200).end();
    audioOf("c")!.ready(200).end();
    expect(currentId(p)).toBe("b");
    // Il tasto play della raccolta: fuori il saltato e i già sentiti.
    p.player.play(list("a", "b", "c", "d"), 0, false, RACCOLTA);
    expect(ids(p)).toEqual(["d"]);
    // Toccato apposta, il saltato suona.
    p.player.play(list("a", "b", "c", "d"), 0, false, RACCOLTA, true);
    expect(currentId(p)).toBe("a");
  });

  it("indietro o lasciato quasi alla fine non è un salto; le aggiunte in coda non contano", async () => {
    const p = await open();
    p.player.setAutoplay(false);
    p.player.play(list("a", "b", "c"), 0, false, RACCOLTA, true);
    p.player.playNext([song("x")]);
    audioOf("a")!.ready(200).at(185);
    p.player.next();
    expect(currentId(p)).toBe("x");
    audioOf("x")!.ready(200).at(5);
    p.player.next();
    expect(ids(p)).toEqual(["a", "x", "b", "c"]);
    audioOf("b")!.ready(200).at(1);
    p.player.prev();
    expect(ids(p)).toEqual(["a", "x", "b", "c"]);
  });

  it("spento il casuale, i già sentiti non tornano dopo il brano in corso", async () => {
    const p = await open();
    p.player.setAutoplay(false);
    p.player.play(list("a", "b", "c", "d", "e"), 0, true, RACCOLTA, true);
    const first = currentId(p)!;
    audioOf(first)!.ready(200).end();
    const second = currentId(p)!;
    p.player.toggleShuffle();
    const order = ids(p);
    expect(order.indexOf(first)).toBeLessThan(order.indexOf(second));
    expect(currentId(p)).toBe(second);
  });

  it("Continua ad ascoltare: all'ultimo brano arrivano brani simili, mai quelli già sentiti o saltati", async () => {
    const server = { songs: ["a", "b", "c", "d", "e", "f"].map((id) => song(id, { artist: id === "f" ? "Altro" : "Tizio", genre: "Rock" })) };
    const p = await open(memoryStorage(), server);
    p.player.play(server.songs.slice(0, 3), 0, false, RACCOLTA, true);
    audioOf("a")!.ready(200).at(10);
    p.player.next();
    audioOf("b")!.ready(200).end();
    // Parte c, l'ultimo: si aggiunge la radio.
    expect(currentId(p)).toBe("c");
    await vi.waitFor(() => expect(ids(p).length).toBeGreaterThan(2));
    const added = ids(p).slice(2);
    expect(added).not.toContain("a");
    expect(added).not.toContain("b");
    expect(added).not.toContain("c");
    expect(new Set(added)).toEqual(new Set(["d", "e", "f"]));
    expect(p.getPlayerState().queue.entries.slice(2).every((e) => e.added)).toBe(true);
  });

  it("Continua ad ascoltare spento: a fine coda ci si ferma", async () => {
    const p = await open(memoryStorage(), { songs: list("x", "y") });
    p.player.setAutoplay(false);
    p.player.play(list("a"));
    await flush();
    expect(ids(p)).toEqual(["a"]);
    expect(p.server.callsOf("getRandomSongs")).toHaveLength(0);
  });

  it("riaprendo la pagina la coda è quella di prima e riparte da dov'eri", async () => {
    const storage = memoryStorage();
    const p = await open(storage);
    p.player.setAutoplay(false);
    p.player.play(list("a", "b", "c"), 1, false, RACCOLTA, true);
    audioOf("b")!.ready(200).at(73);
    p.player.pause();
    const again = await open(storage);
    expect(again.getPlayerState()).toMatchObject({ playing: false, position: 73, from: RACCOLTA });
    expect(again.getPlayerState().queue.entries.map((e) => e.song.id)).toEqual(["b", "c", "a"]);
    again.player.resume();
    const b = FakeAudio.all.find((x) => streamedId(x) === "b")!;
    expect(b.currentTime).toBe(73);
    expect(b.paused).toBe(false);
  });
});

describe("player: senza pause tra i brani", () => {
  it("negli ultimi 30 secondi carica il brano dopo in un secondo <audio>, una volta sola", async () => {
    const p = await open();
    p.player.setCrossfade(0);
    p.player.play(list("a", "b"));
    const a = audioOf("a")!.ready(200);
    a.at(169);
    expect(FakeAudio.all).toHaveLength(1);
    a.at(171);
    a.at(172);
    const spare = audioOf("b")!;
    expect(spare).not.toBe(a);
    expect(spare.loads).toBe(1);
    expect(spare.paused).toBe(true);
    expect(currentId(p)).toBe("a");
  });

  it("alla fine passa all'<audio> già pronto; quello vecchio si svuota e non tocca più lo stato", async () => {
    const p = await open();
    p.player.setCrossfade(0);
    p.player.play(list("a", "b", "c"));
    const a = audioOf("a")!.ready(200);
    a.at(180);
    const spare = audioOf("b")!.ready(200);
    a.end();
    expect(currentId(p)).toBe("b");
    // Nessun nuovo <audio>: suona quello caricato in anticipo, senza ricaricarlo.
    expect(FakeAudio.all).toHaveLength(2);
    expect(spare.paused).toBe(false);
    expect(spare.loads).toBe(1);
    expect(a.src).toBe("");
    // L'<audio> di scorta tace: i suoi eventi non cambiano lo stato.
    a.currentTime = 42;
    a.fire("timeupdate");
    a.fire("pause");
    expect(p.getPlayerState()).toMatchObject({ playing: true, position: 0 });
    spare.at(12);
    expect(p.getPlayerState().position).toBe(12);
    // E ora fa da scorta per il brano dopo.
    spare.at(175);
    expect(streamedId(a)).toBe("c");
  });

  it("con ripeti uno non carica niente in anticipo", async () => {
    const p = await open();
    p.player.play(list("a", "b"));
    p.player.cycleRepeat();
    p.player.cycleRepeat();
    audioOf("a")!.ready(200).at(190);
    expect(FakeAudio.all).toHaveLength(1);
  });

  it("se intanto si è scelto un altro brano, quello caricato in anticipo non si usa", async () => {
    const p = await open();
    p.player.setCrossfade(0);
    p.player.play(list("a", "b", "c"));
    audioOf("a")!.ready(200).at(180);
    p.player.jumpTo(2);
    expect(currentId(p)).toBe("c");
    expect(streamedId(FakeAudio.all[0])).toBe("c");
  });
});

describe("player: volume", () => {
  it("volume scelto × ReplayGain del brano, subito anche cambiando modalità", async () => {
    const p = await open();
    p.player.play([song("a", { replayGain: { trackGain: -12, albumGain: -6 } })]);
    const a = audioOf("a")!;
    expect(a.volume).toBeCloseTo(0.501, 3);
    p.player.setVolume(0.5);
    expect(a.volume).toBeCloseTo(0.2506, 3);
    p.player.setReplayGain("track");
    expect(a.volume).toBeCloseTo(0.5 * 0.2512, 3);
    p.player.setReplayGain("off");
    expect(a.volume).toBe(0.5);
    expect(p.storage.getItem("baldmusic.volume")).toBe("0.5");
    expect(p.storage.getItem("baldmusic.replaygain")).toBe("off");
  });

  it("il volume resta tra 0 e 1 e si ricorda alla prossima apertura", async () => {
    const storage = memoryStorage();
    let p = await open(storage);
    p.player.setVolume(3);
    expect(p.getPlayerState().volume).toBe(1);
    p.player.setVolume(0.3);
    p = await open(storage);
    expect(p.getPlayerState().volume).toBe(0.3);
  });

  it("archiviazione bloccata: valori predefiniti, nessun errore", async () => {
    const p = await open(memoryStorage({}, true));
    expect(p.getPlayerState()).toMatchObject({ volume: 1, replayGain: "album", crossfade: 6 });
    expect(() => p.player.setVolume(0.4)).not.toThrow();
  });
});

describe("player: dissolvenza", () => {
  async function fading(albums: [string, string], secs = 6) {
    vi.useFakeTimers({ toFake: ["setInterval", "clearInterval", "performance"] });
    const p = await open();
    p.player.setCrossfade(secs);
    p.player.play([song("a", { albumId: albums[0] }), song("b", { albumId: albums[1] })]);
    const a = audioOf("a")!.ready(200);
    a.at(180);
    const b = audioOf("b")!.ready(200);
    return { p, a, b };
  }

  it("negli ultimi secondi il brano dopo parte e sale mentre quello prima si abbassa", async () => {
    const { p, a, b } = await fading(["x", "y"]);
    a.at(193);
    expect(currentId(p)).toBe("a");
    a.at(195);
    expect(currentId(p)).toBe("b");
    expect(b.paused).toBe(false);
    expect(a.paused).toBe(false);
    expect(b.volume).toBe(0);
    // A metà sfumatura (5 s in tutto): potenza costante.
    vi.advanceTimersByTime(2500);
    expect(a.volume ** 2 + b.volume ** 2).toBeCloseTo(1, 1);
    vi.advanceTimersByTime(3000);
    expect(a.paused).toBe(true);
    expect(a.src).toBe("");
    expect(b.volume).toBe(1);
  });

  it("la sfumatura rispetta volume e ReplayGain del brano che entra", async () => {
    vi.useFakeTimers({ toFake: ["setInterval", "clearInterval", "performance"] });
    const p = await open();
    p.player.setVolume(0.5);
    p.player.play([song("a", { albumId: "x" }), song("b", { albumId: "y", replayGain: { albumGain: -6 } })]);
    const a = audioOf("a")!.ready(200);
    a.at(180);
    const b = audioOf("b")!.ready(200);
    a.at(195);
    vi.advanceTimersByTime(10_000);
    expect(b.volume).toBeCloseTo(0.5 * 0.501, 3);
  });

  it("mai tra due brani dello stesso album", async () => {
    const { p, a } = await fading(["x", "x"]);
    a.at(196);
    expect(currentId(p)).toBe("a");
  });

  it("spenta, o col brano dopo non ancora pronto, non sfuma", async () => {
    const { p, a } = await fading(["x", "y"], 0);
    a.at(196);
    expect(currentId(p)).toBe("a");

    const second = await fading(["x", "y"]);
    second.b.readyState = 1;
    second.a.at(196);
    expect(currentId(second.p)).toBe("a");
  });

  it("la pausa interrompe la sfumatura: il brano vecchio si ferma, il nuovo a volume pieno", async () => {
    const { p, a, b } = await fading(["x", "y"]);
    a.at(195);
    vi.advanceTimersByTime(1000);
    p.player.pause();
    expect(a.src).toBe("");
    expect(b.paused).toBe(true);
    expect(b.volume).toBe(1);
  });
});

describe("usePlayerSelect", () => {
  it("legge un valore solo: la posizione che avanza non cambia quello che vede l'elenco", async () => {
    const p = await open();
    p.player.play(list("a", "b"));
    const a = audioOf("a")!.ready(200);
    expect(p.usePlayerSelect((s) => s.queue.entries[s.queue.index]?.song.id)).toBe("a");
    const pick = react.get!;
    let notified = 0;
    const stop = react.subscribe!(() => notified++);
    a.at(10);
    a.at(11);
    // Lo store avvisa, ma il valore è lo stesso: React non ridisegna.
    expect(notified).toBeGreaterThan(0);
    expect(pick()).toBe("a");
    // usePlayer invece cambia a ogni avanzamento.
    p.usePlayer();
    const whole = react.get!;
    const before = whole();
    a.at(12);
    expect(whole()).not.toBe(before);
    stop();
    a.end();
    expect(pick()).toBe("b");
  });
});
