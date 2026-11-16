import { describe, expect, it } from "vitest";
import { isPop, parseTheme, themeColor } from "./theme";

describe("tema del sito", () => {
  it("automatico è pop di giorno e scuro di sera, come il computer", () => {
    expect(isPop("auto", false)).toBe(true);
    expect(isPop("auto", true)).toBe(false);
  });

  it("scelto a mano resta quello", () => {
    expect(isPop("pop", true)).toBe(true);
    expect(isPop("dark", false)).toBe(false);
  });

  it("di serie automatico, anche con un valore sconosciuto", () => {
    expect(parseTheme(null)).toBe("auto");
    expect(parseTheme("arcobaleno")).toBe("auto");
    expect(parseTheme("pop")).toBe("pop");
  });

  it("la barra del browser prende il giallo del pop o lo scuro", () => {
    expect(themeColor(true)).toBe("#ffd23f");
    expect(themeColor(false)).toBe("#0e0f13");
  });
});
