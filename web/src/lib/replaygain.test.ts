import { describe, expect, it } from "vitest";
import { PREAMP_DB, replayGainVolume } from "./replaygain";

describe("replayGainVolume", () => {
  it("livello finale come Spotify (-14 LUFS), non -18 del vecchio ReplayGain", () => {
    // Un brano di oggi a -8 LUFS (guadagno -10): prima scendeva di 10 dB e sembrava basso.
    expect(replayGainVolume({ trackGain: -10 }, "track")).toBeCloseTo(0.501, 3);
    expect(replayGainVolume({ trackGain: -4 }, "track")).toBe(1);
    expect(PREAMP_DB).toBe(4);
  });

  it("abbassa i brani forti, non alza quelli piani", () => {
    expect(replayGainVolume({ trackGain: -6, albumGain: -6 }, "album")).toBeCloseTo(0.794, 3);
    expect(replayGainVolume({ trackGain: 4 }, "track")).toBe(1);
  });

  it("mai oltre il picco", () => {
    expect(replayGainVolume({ trackGain: 0, trackPeak: 1.25 }, "track")).toBeCloseTo(0.8, 3);
  });

  it("album o brano, e se manca uno si usa l'altro", () => {
    const rg = { trackGain: -10, albumGain: -6 };
    expect(replayGainVolume(rg, "album")).toBeCloseTo(0.794, 3);
    expect(replayGainVolume(rg, "track")).toBeCloseTo(0.501, 3);
    expect(replayGainVolume({ trackGain: -10 }, "album")).toBeCloseTo(0.501, 3);
  });

  it("senza tag o spento suona com'è", () => {
    expect(replayGainVolume(undefined, "album")).toBe(1);
    expect(replayGainVolume({}, "album")).toBe(1);
    expect(replayGainVolume({ trackGain: -8 }, "off")).toBe(1);
  });
});

describe("album solo ascoltando un disco", () => {
  it("in raccolte e casuale ogni brano al suo livello, come sul telefono", () => {
    const loud = { trackGain: -9, albumGain: -5 };
    const soft = { trackGain: -1, albumGain: -5 };
    expect(replayGainVolume(loud, "album", false)).toBeCloseTo(replayGainVolume(loud, "track"), 6);
    expect(replayGainVolume(soft, "album", false)).toBeCloseTo(replayGainVolume(soft, "track"), 6);
    expect(replayGainVolume(loud, "album", true)).toBeCloseTo(replayGainVolume(soft, "album", true), 6);
    // Solo il guadagno dell'album: si usa quello.
    expect(replayGainVolume({ albumGain: -6 }, "album", false)).toBeCloseTo(Math.pow(10, (-6 + PREAMP_DB) / 20), 6);
  });
});
