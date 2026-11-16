import { existsSync, readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";

// Il sito installato come app (Chrome/Edge su Windows) prende l'icona dal manifest e la vuole in PNG:
// col solo SVG, e col nome di prima, restava il vecchio disco nella barra delle applicazioni.
const pub = resolve(__dirname, "../../public");
const manifest = JSON.parse(readFileSync(resolve(pub, "manifest.webmanifest"), "utf8")) as {
  icons: { src: string; sizes: string; type: string; purpose: string }[];
};

describe("icone del sito installato", () => {
  it("PNG da 192 e 512 e una maskable, tutte presenti", () => {
    const png = manifest.icons.filter((i) => i.type === "image/png");
    expect(png.map((i) => i.sizes)).toEqual(expect.arrayContaining(["192x192", "512x512"]));
    expect(manifest.icons.some((i) => i.purpose.includes("maskable"))).toBe(true);
    for (const icon of manifest.icons) expect(existsSync(resolve(pub, icon.src)), icon.src).toBe(true);
  });

  it("niente più il vecchio icon.svg", () => {
    expect(manifest.icons.some((i) => i.src.includes("icon.svg"))).toBe(false);
    expect(readFileSync(resolve(pub, "../index.html"), "utf8")).not.toContain("icon.svg");
  });
});
