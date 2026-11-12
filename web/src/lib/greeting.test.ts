import { describe, expect, it } from "vitest";
import { displayName, greeting } from "./greeting";

describe("saluto in Home", () => {
  it("dice l'ora del giorno e il nome dell'utente collegato", () => {
    expect(greeting(9, "alessandro")).toBe("Buongiorno, Alessandro");
    expect(greeting(15, "alessandro")).toBe("Buon pomeriggio, Alessandro");
    expect(greeting(22, "alessandro")).toBe("Buonasera, Alessandro");
    expect(greeting(3, "alessandro")).toBe("Buonasera, Alessandro");
  });

  it("senza utente resta il saluto di prima", () => {
    expect(greeting(9)).toBe("Buongiorno");
    expect(greeting(9, "  ")).toBe("Buongiorno");
  });

  it("rende leggibile il nome con cui ci si collega", () => {
    expect(displayName("mario.rossi")).toBe("Mario Rossi");
    expect(displayName("mario_rossi-2")).toBe("Mario Rossi 2");
    expect(displayName("mario@example.com")).toBe("Mario");
    expect(displayName("DJBald")).toBe("DJBald");
    expect(displayName("élodie")).toBe("Élodie");
  });
});
