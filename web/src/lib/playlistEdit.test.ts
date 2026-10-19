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

describe("moveItem ai bordi", () => {
  it("posizione di arrivo oltre la fine: in fondo; partenza fuori dall'elenco: niente", () => {
    expect(moveItem(["a", "b", "c"], 0, 10)).toEqual(["b", "c", "a"]);
    expect(moveItem(["a", "b", "c"], 2, -1)).toEqual(["c", "a", "b"]);
    const list = ["a", "b"];
    expect(moveItem(list, 5, 0)).toBe(list);
    expect(moveItem(list, -1, 0)).toBe(list);
  });

  it("non tocca l'elenco di partenza", () => {
    const list = ["a", "b", "c"];
    moveItem(list, 0, 2);
    expect(list).toEqual(["a", "b", "c"]);
  });
});

describe("sortSongs ai bordi", () => {
  it("brani senza artista vanno in cima, accenti e maiuscole non contano", () => {
    const songs = [
      { id: "1", title: "Été", artist: "Zucchero" },
      { id: "2", title: "anima" },
      { id: "3", title: "Èra", artist: "àlan" },
    ];
    expect(sortSongs(songs, "artist").map((s) => s.id)).toEqual(["2", "3", "1"]);
    expect(sortSongs(songs, "title").map((s) => s.id)).toEqual(["2", "3", "1"]);
    expect(songs.map((s) => s.id)).toEqual(["1", "2", "3"]);
  });

  it("range di un solo brano", () => {
    expect(range(3, 3)).toEqual([3]);
  });
});
