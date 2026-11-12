import { describe, expect, it } from "vitest";
import { MAX_RECENT, quickTiles, routeOf, withRecent, type RecentItem } from "./recent";
import type { Album, Playlist } from "./subsonic";

const album = (id: string, name: string, songCount = 10): Album => ({ id, name, songCount, coverArt: `al-${id}` });
const playlist = (id: string, name: string): Playlist => ({ id, name, coverArt: `pl-${id}` });

describe("ascoltati di recente in Home", () => {
  it("si ricorda quello che hai fatto partire, l'ultimo in cima, senza doppioni", () => {
    let list: RecentItem[] = [];
    list = withRecent(list, { id: "playlist/p1", title: "90s Metal Classics" });
    list = withRecent(list, { id: "album/a1", title: "Meteora", cover: "al-a1" });
    list = withRecent(list, { id: "playlist/p1", title: "90s Metal Classics" });
    expect(list.map((r) => r.id)).toEqual(["playlist/p1", "album/a1"]);
    // Radio, ricerche e mix casuale non hanno una pagina: non si ricordano.
    expect(withRecent(list, { id: "radio/s1", title: "Radio" })).toBe(list);
    expect(withRecent(list, { id: "search/linkin", title: "Ricerca" })).toBe(list);
    expect(withRecent(list, { id: "mix/random", title: "Mix casuale" })).toBe(list);
    const many = Array.from({ length: 20 }, (_, i) => ({ id: `album/${i}`, title: `${i}` })).reduce(withRecent, [] as RecentItem[]);
    expect(many).toHaveLength(MAX_RECENT);
    expect(many[0].id).toBe("album/19");
  });

  it("dove porta ogni contenitore", () => {
    expect(routeOf("playlist/p1")).toEqual({ name: "playlist", id: "p1" });
    expect(routeOf("mix/genre/Rock")).toEqual({ name: "genre", id: "Rock" });
    expect(routeOf("mix/decade/1990")).toEqual({ name: "decade", id: "1990" });
    expect(routeOf("liked")).toEqual({ name: "liked" });
    expect(routeOf("cleanup/x")).toBeNull();
  });

  it("prima i preferiti, poi quello che hai fatto partire, poi gli album veri di Navidrome (mai i singoli)", () => {
    const recent: RecentItem[] = [
      { id: "playlist/p1", title: "Nome vecchio", cover: "al-x" },
      { id: "playlist/gone", title: "Eliminata" },
      { id: "artist/ar1", title: "Linkin Park", cover: "ar-ar1" },
    ];
    const navidrome = [album("j", "Jörmungandr", 1), album("m", "Meteora"), album("h", "Hybrid Theory")];
    const tiles = quickTiles(recent, [playlist("p1", "90s Metal Classics")], 3, navidrome);
    expect(tiles.map((t) => t.title)).toEqual(["Brani che ti piacciono", "90s Metal Classics", "Linkin Park", "Meteora", "Hybrid Theory"]);
    // La raccolta col nome e la copertina di adesso, e il ▶ che la suona.
    expect(tiles[1]).toMatchObject({ cover: "pl-p1", play: { kind: "playlist", id: "p1" }, route: { name: "playlist", id: "p1" } });
    expect(tiles[2]).toMatchObject({ cover: "ar-ar1", play: undefined });
  });

  it("al massimo otto, e senza preferiti non c'è il loro riquadro", () => {
    const recent = Array.from({ length: 10 }, (_, i) => ({ id: `album/${i}`, title: `${i}` }));
    const tiles = quickTiles(recent, [], 0, []);
    expect(tiles).toHaveLength(8);
    expect(tiles.some((t) => t.liked)).toBe(false);
  });
});
