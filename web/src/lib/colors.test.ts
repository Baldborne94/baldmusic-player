import { afterEach, describe, expect, it, vi } from "vitest";
import { dominantColor, dominantFromPixels, tileColor, tileColors, tileGradient } from "./colors";

/** Pixel RGBA in fila. */
const pixels = (...rgba: [number, number, number, number][]) => new Uint8ClampedArray(rgba.flat());

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("tileColors", () => {
  it("lo stesso nome dà sempre la stessa sfumatura, della tavolozza", () => {
    expect(tileColors("mix/random")).toEqual(tileColors("mix/random"));
    expect(tileColor("Rock")).toBe(tileColors("Rock")[0]);
    expect(tileGradient("Rock")).toBe(`linear-gradient(135deg, ${tileColors("Rock")[0]}, ${tileColors("Rock")[1]})`);
    for (const k of ["", "a", "Disney Italia", "日本語", "x".repeat(500)]) expect(tileColors(k)[0]).toMatch(/^#[0-9a-f]{6}$/);
  });

  it("nomi diversi usano più colori della tavolozza", () => {
    const seen = new Set(Array.from({ length: 50 }, (_, i) => tileColor(`genere ${i}`)));
    expect(seen.size).toBeGreaterThan(4);
  });
});

describe("dominantFromPixels", () => {
  it("media dei pixel vivi, scurita per leggerci sopra il bianco", () => {
    expect(dominantFromPixels(pixels([200, 100, 0, 255]))).toBe("#8c4600");
  });

  it("ignora trasparenti, quasi neri e quasi bianchi; se non resta niente, null", () => {
    expect(dominantFromPixels(pixels([0, 0, 0, 255], [250, 250, 250, 255], [200, 0, 0, 10]))).toBeNull();
    expect(dominantFromPixels(new Uint8ClampedArray())).toBeNull();
    expect(dominantFromPixels(pixels([0, 0, 0, 255], [200, 100, 0, 255]))).toBe("#8c4600");
  });

  it("i pixel saturi pesano più del grigio", () => {
    const hex = dominantFromPixels(pixels([128, 128, 128, 255], [220, 0, 0, 255]))!;
    const [r, g] = [parseInt(hex.slice(1, 3), 16), parseInt(hex.slice(3, 5), 16)];
    // A metà strada sarebbe (174, 64) × 0.7: il rosso vince.
    expect(r).toBeGreaterThan(Math.round(174 * 0.7));
    expect(g).toBeLessThan(Math.round(64 * 0.7));
  });
});

describe("dominantColor", () => {
  /** Image e canvas finti: [onload] dice se l'immagine si carica. */
  function fakeBrowser(loads: boolean, data = pixels([200, 100, 0, 255])) {
    const made: { src: string }[] = [];
    class FakeImage {
      crossOrigin = "";
      onload: (() => void) | null = null;
      onerror: (() => void) | null = null;
      private s = "";
      constructor() {
        made.push(this);
      }
      get src() {
        return this.s;
      }
      set src(v: string) {
        this.s = v;
        queueMicrotask(() => (loads ? this.onload?.() : this.onerror?.()));
      }
    }
    vi.stubGlobal("Image", FakeImage);
    vi.stubGlobal("document", {
      createElement: () => ({ width: 0, height: 0, getContext: () => ({ drawImage: () => undefined, getImageData: () => ({ data }) }) }),
    });
    return made;
  }

  it("senza copertina, o se non si carica, nessun colore", async () => {
    expect(await dominantColor(undefined)).toBeNull();
    fakeBrowser(false);
    expect(await dominantColor("http://nd.test/cover-rotta")).toBeNull();
  });

  it("legge il colore una volta e poi lo ricorda", async () => {
    const made = fakeBrowser(true);
    expect(await dominantColor("http://nd.test/cover-1")).toBe("#8c4600");
    expect(await dominantColor("http://nd.test/cover-1")).toBe("#8c4600");
    expect(made).toHaveLength(1);
  });

  it("un canvas «sporco» (niente CORS) non rompe niente", async () => {
    fakeBrowser(true);
    vi.stubGlobal("document", {
      createElement: () => ({
        getContext: () => ({
          drawImage: () => undefined,
          getImageData: () => {
            throw new DOMException("tainted", "SecurityError");
          },
        }),
      }),
    });
    expect(await dominantColor("http://nd.test/cover-2")).toBeNull();
  });
});
