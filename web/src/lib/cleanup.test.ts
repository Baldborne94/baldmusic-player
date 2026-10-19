import { describe, expect, it } from "vitest";
import { duplicates, folderName, nameKey, previews, replaceCopies, trashScript } from "./cleanup";
import type { Song } from "./subsonic";

const song = (id: string, title: string, artist: string, duration: number, extra: Partial<Song> = {}): Song => ({
  id,
  title,
  artist,
  duration,
  path: `C:\\Music\\${extra.album ?? "Cartella"}\\${title}.mp3`,
  ...extra,
});

describe("previews", () => {
  it("trova i brani di 25-35 secondi", () => {
    const list = previews([song("a", "Intera", "X", 200), song("b", "Anteprima", "X", 29), song("c", "Corta", "X", 20)]);
    expect(list.map((s) => s.id)).toEqual(["b"]);
  });
});

describe("duplicates", () => {
  it("stesso artista e titolo, durata entro 3 secondi: tiene la copia coi tag migliori", () => {
    const groups = duplicates([
      song("povera", "Numb", "Linkin Park", 185, { path: "C:\\Music\\Linkin Park\\Numb.mp3" }),
      song("buona", "NUMB", "linkin park", 187, { musicBrainzId: "mb", album: "Meteora", path: "C:\\Music\\Movie\\Numb.mp3" }),
      song("live", "Numb", "Linkin Park", 240),
      song("altro", "In the End", "Linkin Park", 216),
    ]);
    expect(groups).toHaveLength(1);
    expect(groups[0].keep.id).toBe("buona");
    expect(groups[0].others.map((s) => s.id)).toEqual(["povera"]);
  });

  it("le anteprime non sono doppioni", () => {
    expect(duplicates([song("a", "Numb", "LP", 30), song("b", "Numb", "LP", 30)])).toEqual([]);
  });

  it("confronta senza accenti e punteggiatura", () => {
    expect(nameKey("Café — Déjà Vu!")).toBe(nameKey("cafe deja vu"));
  });
});

describe("replaceCopies", () => {
  it("la copia buttata diventa quella tenuta, nella stessa posizione", () => {
    expect(replaceCopies(["a", "doppio", "c"], new Map([["doppio", "tenuto"]]))).toEqual(["a", "tenuto", "c"]);
  });

  it("se la tenuta c'è già, la copia sparisce e basta", () => {
    expect(replaceCopies(["tenuto", "a", "doppio"], new Map([["doppio", "tenuto"]]))).toEqual(["tenuto", "a"]);
    expect(replaceCopies(["doppio", "a", "tenuto"], new Map([["doppio", "tenuto"]]))).toEqual(["a", "tenuto"]);
  });
});

describe("trashScript", () => {
  it("manda i file nel Cestino, con BOM e apici raddoppiati", () => {
    const s = trashScript(["C:\\Music\\Disney\\L'amore è.mp3"]);
    expect(s.startsWith("\uFEFF")).toBe(true);
    expect(s).toContain("'C:\\Music\\Disney\\L''amore è.mp3'");
    expect(s).toContain("SendToRecycleBin");
  });

  it("dice in che cartella sta il file", () => {
    expect(folderName("C:\\Users\\B\\Music\\Disney Italia\\01.mp3")).toBe("Disney Italia");
    expect(folderName("/music/Sum 41/x.mp3")).toBe("Sum 41");
  });
});
