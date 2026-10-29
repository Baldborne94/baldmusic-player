import { describe, expect, it } from "vitest";
import { autoplayNeeded, autoplayPick } from "./autoplay";
import type { Song } from "./subsonic";

const s = (id: string): Song => ({ id, title: id });

describe("Continua ad ascoltare", () => {
  it("solo se attivo, all'ultimo brano, senza ripeti e senza timer a fine brano", () => {
    expect(autoplayNeeded(true, false, true, false)).toBe(true);
    expect(autoplayNeeded(false, false, true, false)).toBe(false);
    expect(autoplayNeeded(true, true, true, false)).toBe(false);
    expect(autoplayNeeded(true, false, false, false)).toBe(false);
    expect(autoplayNeeded(true, false, true, true)).toBe(false);
  });

  it("mai brani già in coda, sentiti o saltati, né doppioni; al massimo quanti chiesti", () => {
    const picked = autoplayPick([s("a"), s("b"), s("b"), s("c"), s("d"), s("e")], new Set(["a", "c"]), 2);
    expect(picked.map((x) => x.id)).toEqual(["b", "d"]);
  });
});
