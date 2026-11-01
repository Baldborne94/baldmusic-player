import { describe, expect, it } from "vitest";
import { crossfadeApplies, crossfadeGains, inSequence } from "./crossfade";

describe("crossfadeApplies", () => {
  it("sfuma tra brani di album diversi", () => {
    expect(crossfadeApplies(6, 200, false)).toBe(true);
  });
  it("mai dentro lo stesso album, mai se spenta, mai su brani corti", () => {
    expect(crossfadeApplies(6, 200, true)).toBe(false);
    expect(crossfadeApplies(0, 200, false)).toBe(false);
    expect(crossfadeApplies(6, 15, false)).toBe(false);
  });
});

describe("crossfadeGains", () => {
  it("parte dal brano vecchio, finisce sul nuovo, a metà la potenza resta uguale", () => {
    expect(crossfadeGains(0)).toEqual([1, 0]);
    const [o, i] = crossfadeGains(1);
    expect(o).toBeCloseTo(0, 6);
    expect(i).toBeCloseTo(1, 6);
    const [mo, mi] = crossfadeGains(0.5);
    expect(mo * mo + mi * mi).toBeCloseTo(1, 6);
  });
});

describe("inSequence", () => {
  it("attaccati solo lo stesso album con la traccia dopo, come sul telefono", () => {
    expect(inSequence(true, 1, 3, 1, 4)).toBe(true);
    expect(inSequence(true, 1, 12, 2, 1)).toBe(true);
    expect(inSequence(true, undefined, 3, undefined, 4)).toBe(true);
    expect(inSequence(true, 1, 7, 1, 2)).toBe(false);
    expect(inSequence(true, undefined, undefined, undefined, undefined)).toBe(false);
    expect(inSequence(false, 1, 3, 1, 4)).toBe(false);
  });
});
