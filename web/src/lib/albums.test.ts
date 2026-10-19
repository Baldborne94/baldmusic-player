import { describe, expect, it } from "vitest";
import { albumYear, discography, isRealAlbum } from "./albums";

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

describe("discography", () => {
  it("divide per tipo, ognuno dal più recente", () => {
    const groups = discography([
      { id: "1", name: "Hybrid Theory", year: 2000, releaseTypes: ["album"] },
      { id: "2", name: "Meteora", year: 2003, releaseTypes: ["Album"] },
      { id: "3", name: "Road to Revolution", year: 2008, releaseTypes: ["album", "live"] },
      { id: "4", name: "Reanimation", year: 2002, releaseTypes: ["album", "remix"] },
      { id: "5", name: "Senza tag", year: 1999 },
      { id: "6", name: "Transformers", year: 2007, releaseTypes: ["album", "soundtrack"] },
    ]);
    expect(groups.map((g) => [g.kind, g.albums.map((a) => a.id).join(",")])).toEqual([
      ["Album", "2,1,5"],
      ["Dal vivo", "3"],
      ["Colonne sonore", "6"],
      ["Altro", "4"],
    ]);
  });
});
