import { describe, expect, it } from "vitest";
import { crossfadeApplies, crossfadeGains } from "./crossfade";

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
