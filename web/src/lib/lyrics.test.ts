import { describe, expect, it, vi } from "vitest";
import { findLyrics, lineAt, parseLrc } from "./lyrics";
import { dominantFromPixels, tileColor, tileGradient } from "./colors";

describe("parseLrc", () => {
  it("tempi, righe ripetute e offset", () => {
    const lines = parseLrc("[ar:X]\n[offset:+500]\n[00:12.30]Prima\n[00:05.5][00:20.00]Ritornello\nsenza tempo");
    expect(lines.map((l) => [Number(l.time.toFixed(2)), l.text])).toEqual([
      [5, "Ritornello"],
      [11.8, "Prima"],
      [19.5, "Ritornello"],
    ]);
  });

  it("riga corrente", () => {
    const l = [{ time: 1, text: "a" }, { time: 2, text: "b" }, { time: 3, text: "c" }];
    expect(lineAt(l, 0.5)).toBe(-1);
    expect(lineAt(l, 2.9)).toBe(1);
    expect(lineAt(l, 99)).toBe(2);
  });
});

describe("findLyrics", () => {
  it("ricerca esatta, poi libera preferendo i sincronizzati", async () => {
    const fetcher = vi.fn()
      .mockResolvedValueOnce({ ok: false })
      .mockResolvedValueOnce({ ok: true, json: async () => [{ plainLyrics: "solo testo", duration: 200 }, { syncedLyrics: "[00:01.00]Ciao", duration: 260 }] });
    const lyrics = await findLyrics("Artista", "Titolo", undefined, 200, fetcher as unknown as typeof fetch);
    expect(lyrics?.synced).toEqual([{ time: 1, text: "Ciao" }]);
    expect(String(fetcher.mock.calls[0][0])).toContain("/api/get?");
  });
});

describe("colori", () => {
  it("colore stabile per lo stesso nome", () => {
    expect(tileColor("Rock")).toBe(tileColor("Rock"));
    expect(tileGradient("Rock")).toMatch(/^linear-gradient\(135deg, #[0-9a-f]{6}, #[0-9a-f]{6}\)$/);
  });

  it("ignora nero e bianco, scurisce il colore vivo", () => {
    const px = new Uint8ClampedArray([0, 0, 0, 255, 255, 255, 255, 255, 200, 40, 40, 255]);
    expect(dominantFromPixels(px)).toBe("#8c1c1c");
  });
});
