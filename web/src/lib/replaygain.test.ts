import { describe, expect, it } from "vitest";
import { replayGainVolume } from "./replaygain";

describe("replayGainVolume", () => {
  it("abbassa i brani forti, non alza quelli piani", () => {
    expect(replayGainVolume({ trackGain: -6, albumGain: -6 }, "album")).toBeCloseTo(0.501, 3);
    expect(replayGainVolume({ trackGain: 4 }, "track")).toBe(1);
  });

  it("mai oltre il picco", () => {
    expect(replayGainVolume({ trackGain: 0, trackPeak: 1.25 }, "track")).toBeCloseTo(0.8, 3);
  });

  it("album o brano, e se manca uno si usa l'altro", () => {
    const rg = { trackGain: -10, albumGain: -4 };
    expect(replayGainVolume(rg, "album")).toBeCloseTo(0.631, 3);
    expect(replayGainVolume(rg, "track")).toBeCloseTo(0.316, 3);
    expect(replayGainVolume({ trackGain: -10 }, "album")).toBeCloseTo(0.316, 3);
  });

  it("senza tag o spento suona com'è", () => {
    expect(replayGainVolume(undefined, "album")).toBe(1);
    expect(replayGainVolume({}, "album")).toBe(1);
    expect(replayGainVolume({ trackGain: -8 }, "off")).toBe(1);
  });
});
