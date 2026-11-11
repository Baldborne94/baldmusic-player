import { describe, expect, it } from "vitest";
import { scanAndWait, ScanError, scanErrorMessage, SCAN_MAX_MS, SCAN_POLL_MS } from "./scan";
import { SubsonicError, type ScanStatus } from "./subsonic";

/** Un Navidrome che scansiona per [rounds] domande, leggendo 100 brani ogni volta. */
function scanner(rounds: number, start: Partial<ScanStatus> = { scanning: true, count: 0 }) {
  let left = rounds;
  let count = start.count ?? 0;
  const asked: boolean[] = [];
  return {
    asked,
    startScan: async (full: boolean) => {
      asked.push(full);
      return { scanning: start.scanning ?? true, count };
    },
    scanStatus: async () => {
      count += 100;
      left--;
      return { scanning: left > 0, count };
    },
  };
}

const noWait = async () => {};

describe("scanAndWait", () => {
  it("aspetta che Navidrome finisca e dice quanti brani ha letto", async () => {
    const s = scanner(3);
    const seen: number[] = [];
    expect(await scanAndWait(s, false, (n) => seen.push(n), noWait)).toBe(300);
    expect(seen).toEqual([0, 100, 200, 300]);
    expect(s.asked).toEqual([false]);
  });

  it("la scansione completa (dopo Picard) lo chiede a Navidrome", async () => {
    const s = scanner(1);
    await scanAndWait(s, true, () => {}, noWait);
    expect(s.asked).toEqual([true]);
  });

  it("se Navidrome risponde subito «finita» guarda comunque una volta", async () => {
    const s = scanner(1, { scanning: false, count: 0 });
    expect(await scanAndWait(s, false, () => {}, noWait)).toBe(100);
  });

  it("dopo mezz'ora smette di aspettare", async () => {
    let t = 0;
    const s = scanner(10_000);
    const sleep = async (ms: number) => {
      t += ms;
    };
    await expect(scanAndWait(s, false, () => {}, sleep, () => t)).rejects.toBeInstanceOf(ScanError);
    expect(t).toBeGreaterThan(SCAN_MAX_MS - SCAN_POLL_MS);
  });

  it("un utente che non è amministratore: lo dice in chiaro", async () => {
    const s = {
      startScan: async () => {
        throw new SubsonicError("User not authorized", 50);
      },
      scanStatus: async () => ({ scanning: false, count: 0 }),
    };
    await expect(scanAndWait(s, false, () => {}, noWait)).rejects.toThrow(/amministratore/);
    expect(scanErrorMessage(new SubsonicError("Server non raggiungibile"))).toBe("Server non raggiungibile");
  });
});
