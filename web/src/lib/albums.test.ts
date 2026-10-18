import { describe, expect, it } from "vitest";
import { albumYear, isRealAlbum } from "./albums";

describe("isRealAlbum", () => {
  it("vuole almeno due brani e non un singolo", () => {
    expect(isRealAlbum({ id: "a", name: "Issues", songCount: 16, releaseTypes: ["Album"] })).toBe(true);
    expect(isRealAlbum({ id: "b", name: "Blind", songCount: 2, releaseTypes: ["Single"] })).toBe(false);
    expect(isRealAlbum({ id: "c", name: "Hits", songCount: 1 })).toBe(false);
    expect(isRealAlbum({ id: "d", name: "Senza conteggio" })).toBe(true);
  });
});

describe("albumYear", () => {
  it("preferisce l'anno della prima uscita", () => {
    expect(albumYear({ id: "a", name: "Ristampa", year: 2011, originalReleaseDate: { year: 1977 } })).toBe(1977);
    expect(albumYear({ id: "b", name: "Normale", year: 1999, originalReleaseDate: {} })).toBe(1999);
    expect(albumYear({ id: "c", name: "Senza anno" })).toBe(0);
  });
});
