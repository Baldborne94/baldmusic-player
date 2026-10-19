import type { DragEvent } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { draggedSongs, endSongDrag, isSongDrag, SONGS_TYPE, startSongDrag } from "./songDrag";
import type { Song } from "./subsonic";

/** Un DragEvent con un dataTransfer finto, come quello del browser. */
function dragEvent(types: string[] = []) {
  const data = new Map(types.map((t) => [t, ""]));
  const dataTransfer = {
    effectAllowed: "none",
    dropEffect: "none",
    get types() {
      return [...data.keys()];
    },
    setData: (t: string, v: string) => void data.set(t, v),
    getData: (t: string) => data.get(t) ?? "",
    setDragImage: vi.fn(),
  };
  return { e: { dataTransfer } as unknown as DragEvent, data, dataTransfer };
}

/** Il document minimo che serve per l'etichetta trascinata. */
function fakeDocument() {
  const tags: { className: string; textContent: string; removed: boolean; remove: () => void }[] = [];
  vi.stubGlobal("document", {
    createElement: () => {
      const tag = { className: "", textContent: "", removed: false, remove: () => void (tag.removed = true) };
      tags.push(tag);
      return tag;
    },
    body: { appendChild: () => undefined },
  });
  return tags;
}

const songs: Song[] = [
  { id: "a", title: "Numb", artist: "Linkin Park" },
  { id: "b", title: "Senza artista" },
];

afterEach(() => {
  endSongDrag();
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

describe("trascinare i brani", () => {
  it("porta gli ID nel suo tipo e «Artista - Titolo» come testo", () => {
    fakeDocument();
    const { e, data, dataTransfer } = dragEvent();
    startSongDrag(e, songs);
    expect(data.get(SONGS_TYPE)).toBe("a,b");
    expect(data.get("text/plain")).toBe("Linkin Park - Numb\nSenza artista");
    expect(dataTransfer.effectAllowed).toBe("copyMove");
    expect(draggedSongs()).toEqual(songs);
  });

  it("l'etichetta dice il titolo, o quanti sono, e sparisce subito dopo", () => {
    vi.useFakeTimers();
    const tags = fakeDocument();
    startSongDrag(dragEvent().e, [songs[0]]);
    startSongDrag(dragEvent().e, songs);
    expect(tags.map((t) => [t.className, t.textContent])).toEqual([
      ["drag-tag", "Numb"],
      ["drag-tag", "2 brani"],
    ]);
    expect(tags.every((t) => !t.removed)).toBe(true);
    vi.runAllTimers();
    expect(tags.every((t) => t.removed)).toBe(true);
  });

  it("riconosce solo i trascinamenti di brani, e alla fine li dimentica", () => {
    fakeDocument();
    expect(isSongDrag(dragEvent(["Files"]).e)).toBe(false);
    expect(isSongDrag(dragEvent([SONGS_TYPE, "text/plain"]).e)).toBe(true);
    startSongDrag(dragEvent().e, songs);
    endSongDrag();
    expect(draggedSongs()).toEqual([]);
  });
});
