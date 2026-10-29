import { describe, expect, it } from "vitest";
import * as L from "./listening";

const T0 = 1_000_000;

describe("isSkip", () => {
  it("saltato prima degli ultimi 30 s e dei tre quarti, come sul telefono", () => {
    expect(L.isSkip(100, 200)).toBe(true);
    expect(L.isSkip(149, 200)).toBe(true);
    expect(L.isSkip(150, 200)).toBe(false);
    // Brano corto: contano gli ultimi 30 s.
    expect(L.isSkip(29, 60)).toBe(true);
    expect(L.isSkip(30, 60)).toBe(false);
    // Durata sconosciuta: prima di 30 s.
    expect(L.isSkip(20)).toBe(true);
    expect(L.isSkip(40)).toBe(false);
    expect(L.countsAsPlay(100, 200)).toBe(false);
    expect(L.countsAsPlay(160, 200)).toBe(true);
  });
});

describe("Listening", () => {
  it("lo stesso contenitore entro due ore continua; un altro o dopo due ore ricomincia", () => {
    let l = L.startingOn(L.freshListening(), "playlist/x", T0);
    l = L.left(l, "a", 10, 200, true, false, true, T0 + 1000);
    expect(L.startingOn(l, "playlist/x", T0 + 2000).skipped).toEqual(new Set(["a"]));
    expect(L.startingOn(l, "playlist/y", T0 + 2000).skipped.size).toBe(0);
    expect(L.startingOn(l, "playlist/x", T0 + 1000 + L.GAP_MS + 1).skipped.size).toBe(0);
  });

  it("finito = sentito, lasciato presto andando avanti = saltato, indietro = niente", () => {
    let l = L.freshListening("playlist/x", T0);
    l = L.left(l, "a", 200, 200, true, true, true, T0);
    l = L.left(l, "b", 10, 200, true, false, true, T0);
    l = L.left(l, "c", 10, 200, false, false, true, T0);
    l = L.left(l, "d", 180, 200, true, false, true, T0);
    expect(l.heard).toEqual(new Set(["a", "d"]));
    expect(l.skipped).toEqual(new Set(["b"]));
    // Risentito tutto dopo un salto: non è più saltato.
    l = L.left(l, "b", 200, 200, true, true, true, T0);
    expect(l.skipped.size).toBe(0);
  });

  it("i brani aggiunti in coda e le code senza contenitore non contano", () => {
    expect(L.left(L.freshListening("playlist/x"), "a", 10, 200, true, false, false, T0).skipped.size).toBe(0);
    expect(L.left(L.freshListening(null), "a", 10, 200, true, false, true, T0).skipped.size).toBe(0);
  });

  it("una coda nuova dello stesso contenitore lascia fuori sentiti e saltati, tranne quello toccato", () => {
    const l: L.Listening = { container: "playlist/x", heard: new Set(["a"]), skipped: new Set(["c"]), lastAt: T0 };
    const ids = ["a", "b", "c", "d"];
    expect(L.keepFor(l, ids, 2, true)).toEqual({ kept: [1, 2, 3], start: 1 });
    // Senza un brano toccato (tasto play): dal primo che manca.
    expect(L.keepFor(l, ids, 0, false)).toEqual({ kept: [1, 3], start: 0 });
    // Sentita tutta: si riascolta intera.
    const all: L.Listening = { ...l, heard: new Set(["a", "b", "d"]) };
    expect(L.keepFor(all, ids, 0, false)).toEqual({ kept: [0, 1, 2, 3], start: 0 });
    expect(L.keepFor(all, ids, 2, true)).toEqual({ kept: [0, 1, 2, 3], start: 2 });
  });

  it("cosa «Continua ad ascoltare» non aggiunge: in coda, sentiti e saltati", () => {
    const l: L.Listening = { container: "playlist/x", heard: new Set(["a"]), skipped: new Set(["c"]), lastAt: T0 };
    expect(L.notAgain(l, ["b"])).toEqual(new Set(["a", "b", "c"]));
  });
});

describe("wrapsAround", () => {
  it("raccolte, playlist, preferiti e mix sì; album, artisti e ricerche no", () => {
    expect(["playlist/1", "liked", "mix/random"].map(L.wrapsAround)).toEqual([true, true, true]);
    expect(["album/1", "artist/1", "search/x", "radio/1", null].map(L.wrapsAround)).toEqual([false, false, false, false, false]);
  });
});
