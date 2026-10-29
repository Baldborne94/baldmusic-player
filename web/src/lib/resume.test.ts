import { describe, expect, it } from "vitest";
import * as Q from "./queue";
import { MAX_SAVED, restore, snapshot } from "./resume";
import type { Song } from "./subsonic";

const songs = (n: number): Song[] => Array.from({ length: n }, (_, i) => ({ id: `s${i}`, title: `Brano ${i}` }));
const ids = (q: Q.QueueState) => q.entries.map((e) => e.song.id);
const seeded = (seed = 1) => () => ((seed = (seed * 16807) % 2147483647) - 1) / 2147483646;

describe("Ripresa della coda", () => {
  it("brani, brano in corso, posizione, casuale, ripeti e da dove arriva tornano uguali", () => {
    let q = Q.playList(Q.emptyQueue, songs(6), 2, true, seeded());
    q = Q.enqueue(q, [{ id: "extra", title: "Extra" }]);
    q = Q.cycleRepeat(q);
    const back = restore(JSON.stringify(snapshot(q, 42, { label: "Raccolta", id: "playlist/1" })))!;
    expect(ids(back.queue)).toEqual(ids(q));
    expect(back.queue.index).toBe(q.index);
    expect(back.queue.repeat).toBe("all");
    expect(back.queue.unshuffled!.map((e) => e.song.id)).toEqual(q.unshuffled!.map((e) => e.song.id));
    expect(back.queue.entries.find((e) => e.song.id === "extra")?.added).toBe(true);
    expect(back).toMatchObject({ position: 42, from: { id: "playlist/1" } });
    // Spegnendo il casuale torna l'ordine di prima.
    expect(ids(Q.toggleShuffle(back.queue))).toEqual([...songs(6).map((s) => s.id), "extra"]);
  });

  it("al massimo 500 brani attorno a quello in corso", () => {
    const q = Q.jumpTo(Q.playList(Q.emptyQueue, songs(2000)), 1000);
    const back = restore(JSON.stringify(snapshot(q, 0, null)))!;
    expect(back.queue.entries).toHaveLength(MAX_SAVED);
    expect(Q.current(back.queue)?.id).toBe("s1000");
    expect(back.queue.entries[0].song.id).toBe("s950");
  });

  it("niente o un file rovinato: nessuna coda", () => {
    expect(restore(null)).toBeNull();
    expect(restore("{")).toBeNull();
    expect(restore(JSON.stringify({ songs: [], index: 0 }))).toBeNull();
    expect(snapshot(Q.emptyQueue, 0, null)).toBeNull();
  });
});
