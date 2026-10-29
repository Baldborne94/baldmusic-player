import { describe, expect, it } from "vitest";
import * as Q from "./queue";
import type { Song } from "./subsonic";

const songs = (n: number): Song[] => Array.from({ length: n }, (_, i) => ({ id: `s${i}`, title: `Brano ${i}` }));
const ids = (q: Q.QueueState) => q.entries.map((e) => e.song.id);
/** Generatore deterministico per rendere ripetibile lo shuffle. */
const seeded = (seed = 1) => () => ((seed = (seed * 16807) % 2147483647) - 1) / 2147483646;

describe("playList", () => {
  it("parte dal brano scelto", () => {
    const q = Q.playList(Q.emptyQueue, songs(5), 2);
    expect(Q.current(q)?.id).toBe("s2");
    expect(ids(q)).toEqual(["s0", "s1", "s2", "s3", "s4"]);
  });

  it("con lo shuffle il brano scelto è il primo e nessuno si perde", () => {
    const q = Q.playList(Q.emptyQueue, songs(10), 4, true, seeded());
    expect(q.index).toBe(0);
    expect(Q.current(q)?.id).toBe("s4");
    expect([...ids(q)].sort()).toEqual(ids(Q.playList(Q.emptyQueue, songs(10))).sort());
  });

  it("una lista vuota svuota la coda ma conserva la ripetizione", () => {
    const q = Q.playList({ ...Q.emptyQueue, repeat: "all" }, []);
    expect(q.index).toBe(-1);
    expect(q.repeat).toBe("all");
  });
});

describe("nextIndex / prevIndex", () => {
  const base = Q.jumpTo(Q.playList(Q.emptyQueue, songs(3)), 2);

  it("a fine coda si ferma, o ricomincia con ripeti tutto", () => {
    expect(Q.nextIndex(base, true)).toBeNull();
    expect(Q.nextIndex({ ...base, repeat: "all" }, true)).toBe(0);
  });

  it("ripeti uno rifà il brano solo alla fine naturale", () => {
    const one = { ...base, index: 1, repeat: "one" as const };
    expect(Q.nextIndex(one, true)).toBe(1);
    expect(Q.nextIndex(one, false)).toBe(2);
  });

  it("indietro dal primo resta sul primo, o va all'ultimo con ripeti tutto", () => {
    const first = Q.jumpTo(base, 0);
    expect(Q.prevIndex(first)).toBe(0);
    expect(Q.prevIndex({ ...first, repeat: "all" })).toBe(2);
  });
});

describe("toggleShuffle", () => {
  it("accendere e spegnere riporta all'ordine originale sul brano corrente", () => {
    const start = Q.jumpTo(Q.playList(Q.emptyQueue, songs(8)), 3);
    const on = Q.toggleShuffle(start, seeded(7));
    expect(Q.current(on)?.id).toBe("s3");
    expect(ids(on).slice(0, 4)).toEqual(["s0", "s1", "s2", "s3"]);
    const off = Q.toggleShuffle(on);
    expect(ids(off)).toEqual(ids(start));
    expect(Q.current(off)?.id).toBe("s3");
  });

  it("i brani aggiunti durante lo shuffle restano dopo averlo spento", () => {
    const on = Q.toggleShuffle(Q.playList(Q.emptyQueue, songs(3)), seeded());
    const added = Q.enqueue(on, [{ id: "x", title: "X" }]);
    const off = Q.toggleShuffle(added);
    expect(ids(off)).toEqual(["s0", "s1", "s2", "x"]);
  });
});

describe("modifiche alla coda", () => {
  it("riproduci dopo inserisce subito dopo il brano corrente", () => {
    const q = Q.playNext(Q.jumpTo(Q.playList(Q.emptyQueue, songs(3)), 0), [{ id: "x", title: "X" }]);
    expect(ids(q)).toEqual(["s0", "x", "s1", "s2"]);
  });

  it("enqueue su coda vuota punta al primo brano", () => {
    expect(Q.enqueue(Q.emptyQueue, songs(2)).index).toBe(0);
  });

  it("rimuovere un brano prima del corrente non cambia il brano in ascolto", () => {
    const q = Q.removeAt(Q.jumpTo(Q.playList(Q.emptyQueue, songs(4)), 2), 0);
    expect(Q.current(q)?.id).toBe("s2");
  });

  it("rimuovere l'ultimo brano rimasto svuota la coda", () => {
    expect(Q.removeAt(Q.playList(Q.emptyQueue, songs(1)), 0).index).toBe(-1);
  });

  it("move segue il brano corrente", () => {
    const q = Q.move(Q.jumpTo(Q.playList(Q.emptyQueue, songs(4)), 1), 1, 3);
    expect(ids(q)).toEqual(["s0", "s2", "s3", "s1"]);
    expect(Q.current(q)?.id).toBe("s1");
  });

  it("lo stesso brano due volte resta distinguibile", () => {
    const one: Song = { id: "a", title: "A" };
    const q = Q.removeAt(Q.playList(Q.emptyQueue, [one, one]), 1);
    expect(q.entries).toHaveLength(1);
  });
});

describe("casi limite della coda", () => {
  it("ripeti gira off → tutto → uno → off", () => {
    let q = Q.emptyQueue;
    const seen = [];
    for (let i = 0; i < 3; i++) seen.push((q = Q.cycleRepeat(q)).repeat);
    expect(seen).toEqual(["all", "one", "off"]);
  });

  it("indici fuori dalla coda non cambiano niente", () => {
    const q = Q.playList(Q.emptyQueue, songs(3));
    expect(Q.jumpTo(q, 5)).toBe(q);
    expect(Q.jumpTo(q, -1)).toBe(q);
    expect(Q.removeAt(q, 3)).toBe(q);
    expect(Q.move(q, 0, 3)).toBe(q);
    expect(Q.nextIndex(Q.emptyQueue, true)).toBeNull();
  });

  it("riproduci dopo su coda vuota fa da coda", () => {
    const q = Q.playNext(Q.emptyQueue, songs(2));
    expect(ids(q)).toEqual(["s0", "s1"]);
    expect(q.index).toBe(0);
  });

  it("togliere il brano in ascolto (l'ultimo) passa a quello prima", () => {
    const q = Q.removeAt(Q.jumpTo(Q.playList(Q.emptyQueue, songs(3)), 2), 2);
    expect(Q.current(q)?.id).toBe("s1");
  });

  it("con lo shuffle acceso, togliere e aggiungere tiene allineato l'ordine originale", () => {
    const on = Q.toggleShuffle(Q.playList(Q.emptyQueue, songs(4)), seeded(3));
    const removed = Q.removeAt(on, on.entries.findIndex((e) => e.song.id === "s2"));
    const next = Q.playNext(removed, [{ id: "x", title: "X" }]);
    expect(Q.toggleShuffle(next).entries.map((e) => e.song.id)).toEqual(["s0", "s1", "s3", "x"]);
  });

  it("lo shuffle su una coda vuota non rompe niente", () => {
    const on = Q.toggleShuffle(Q.emptyQueue);
    expect(on.entries).toEqual([]);
    expect(Q.toggleShuffle(on).index).toBe(-1);
  });
});

describe("come sul telefono", () => {
  it("raccolte e playlist dal brano toccato alla fine e poi dall'inizio: anche dall'ultimo suonano tutte", () => {
    const q = Q.playList(Q.emptyQueue, songs(4), 3, false, Math.random, true);
    expect(ids(q)).toEqual(["s3", "s0", "s1", "s2"]);
    expect(Q.current(q)?.id).toBe("s3");
    // Un album resta in ordine dal brano toccato.
    expect(ids(Q.playList(Q.emptyQueue, songs(4), 3))).toEqual(["s0", "s1", "s2", "s3"]);
  });

  it("i già sentiti restano prima del brano in corso, accendendo o spegnendo il casuale", () => {
    const heard = (id: string) => id === "s3" || id === "s4";
    const q = Q.playList(Q.emptyQueue, songs(6), 1);
    const on = Q.toggleShuffle(q, seeded(5), heard);
    expect(Q.current(on)?.id).toBe("s1");
    expect(ids(on).slice(on.index + 1).some(heard)).toBe(false);
    const off = Q.toggleShuffle(on, seeded(5), heard);
    expect(ids(off)).toEqual(["s0", "s3", "s4", "s1", "s2", "s5"]);
    expect(Q.current(off)?.id).toBe("s1");
  });

  it("«Riproduci dopo» e «Aggiungi in coda» sono aggiunte, non brani del contenitore", () => {
    const q = Q.playNext(Q.enqueue(Q.playList(Q.emptyQueue, songs(2)), songs(1)), songs(1));
    expect(q.entries.map((e) => !!e.added)).toEqual([false, true, false, true]);
  });

  it("un brano saltato si toglie restando su quello in corso", () => {
    const q = Q.jumpTo(Q.playList(Q.emptyQueue, songs(3)), 1);
    const out = Q.removeUid(q, q.entries[0].uid);
    expect(ids(out)).toEqual(["s1", "s2"]);
    expect(Q.current(out)?.id).toBe("s1");
  });
});
