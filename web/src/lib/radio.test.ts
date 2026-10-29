import { describe, expect, it } from "vitest";
import { Affinity, genres, moodSong, pickRadio, score, type MoodSong } from "./radio";

/** Generatore deterministico. */
const seeded = (seed = 1) => () => ((seed = (seed * 16807) % 2147483647) - 1) / 2147483646;
const m = (id: string, extra: Partial<MoodSong> = {}): MoodSong => ({ id, genres: new Set(), ...extra });

describe("radio con mood e stile", () => {
  it("prima i brani della stessa raccolta, non brani a caso", () => {
    const seed = m("seed", { artist: "x", raccolta: "Heavy Workout" });
    const gym = Array.from({ length: 12 }, (_, i) => m(`gym${i}`, { artist: `g${i}`, raccolta: "Heavy Workout" }));
    const disney = Array.from({ length: 12 }, (_, i) => m(`dis${i}`, { artist: `d${i}`, raccolta: "Disney Italia" }));
    const ids = pickRadio(seed, [seed, ...disney, ...gym], seeded());
    expect(ids[0]).toBe("seed");
    expect(ids.slice(1, 11).every((id) => id.startsWith("gym"))).toBe(true);
  });

  it("genere in comune conta, genere diverso allontana, energia troppo diversa anche", () => {
    const seed = m("s", { genres: genres("Rock; Alternative"), gainDb: -9 });
    const aff = new Affinity([]);
    expect(score(seed, m("a", { genres: genres("rock") }), aff)).toBe(3);
    expect(score(seed, m("b", { genres: genres("Pop") }), aff)).toBe(-2);
    expect(score(seed, m("c", { gainDb: 0 }), aff)).toBe(-3);
    expect(score(seed, m("d", { gainDb: -3 }), aff)).toBe(-1.5);
    // I simili secondo Navidrome (Last.fm), se li sa.
    expect(score(seed, m("e"), aff, new Set(["e"]))).toBe(3);
  });

  it("gli artisti delle stesse raccolte stanno insieme", () => {
    const all = [m("1", { artist: "a", raccolta: "R" }), m("2", { artist: "b", raccolta: "R" }), m("3", { artist: "c", raccolta: "Q" })];
    const aff = new Affinity(all);
    expect(aff.related("a", "b")).toBe(true);
    expect(aff.related("a", "c")).toBe(false);
  });

  it("due simili e uno dello stesso artista, senza due di fila dello stesso artista", () => {
    const seed = m("seed", { artist: "x", raccolta: "R" });
    const mine = Array.from({ length: 5 }, (_, i) => m(`x${i}`, { artist: "x", raccolta: "R" }));
    const others = Array.from({ length: 20 }, (_, i) => m(`o${i}`, { artist: `a${i % 4}`, raccolta: "R" }));
    const ids = pickRadio(seed, [seed, ...mine, ...others], seeded(3));
    expect(ids.slice(1, 4).filter((id) => id.startsWith("x"))).toHaveLength(1);
    const artist = new Map([seed, ...mine, ...others].map((s) => [s.id, s.artist]));
    for (let i = 1; i < 12; i++) expect(artist.get(ids[i])).not.toBe(artist.get(ids[i - 1]));
    expect(new Set(ids).size).toBe(ids.length);
  });

  it("dal brano: artista e genere confrontabili, decennio, ReplayGain", () => {
    const s = moodSong({ id: "1", title: "t", artist: "Beyoncé", genre: "Pop/R&B", year: 2003, replayGain: { trackGain: -7 } }, "R");
    expect(s).toMatchObject({ artist: "beyonce", raccolta: "R", decade: 2000, gainDb: -7 });
    expect(s.genres).toEqual(new Set(["pop", "r b"]));
  });
});
