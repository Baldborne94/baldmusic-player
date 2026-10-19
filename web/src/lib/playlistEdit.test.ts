import { describe, expect, it } from "vitest";
import { moveItem, range, sortSongs } from "./playlistEdit";

describe("moveItem", () => {
  it("sposta avanti e indietro", () => {
    expect(moveItem(["a", "b", "c", "d"], 0, 2)).toEqual(["b", "c", "a", "d"]);
    expect(moveItem(["a", "b", "c", "d"], 3, 0)).toEqual(["d", "a", "b", "c"]);
    expect(moveItem(["a", "b"], 1, 1)).toEqual(["a", "b"]);
  });
});

describe("sortSongs", () => {
  const songs = [
    { id: "1", title: "Zombie", artist: "The Cranberries" },
    { id: "2", title: "Bring Me to Life", artist: "Evanescence" },
    { id: "3", title: "Anima", artist: "evanescence" },
    { id: "4", title: "10 Years", artist: "Avril" },
    { id: "5", title: "2 Hearts", artist: "Avril" },
  ];
  it("per titolo, coi numeri in ordine naturale", () => {
    expect(sortSongs(songs, "title").map((s) => s.id)).toEqual(["5", "4", "3", "2", "1"]);
  });
  it("per artista senza The, poi per titolo", () => {
    expect(sortSongs(songs, "artist").map((s) => s.id)).toEqual(["5", "4", "1", "3", "2"]);
  });
});

describe("range", () => {
  it("in tutte e due le direzioni", () => {
    expect(range(2, 4)).toEqual([2, 3, 4]);
    expect(range(4, 2)).toEqual([2, 3, 4]);
  });
});
