import { describe, expect, it } from "vitest";
import { duplicates, folderName, isWindowsPath, nameKey, previews, psQuote, replaceCopies, trashScript } from "./cleanup";
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
  it("si apre col doppio clic, manda i file nel Cestino, apici raddoppiati", () => {
    const s = trashScript(["C:\\Music\\Disney\\L'amore è.mp3"]);
    // Prima riga: per il prompt è un'etichetta, per PowerShell l'inizio di un commento.
    expect(s.startsWith("<# :")).toBe(true);
    expect(s).toContain("-ExecutionPolicy Bypass");
    expect(s).toContain("[Text.Encoding]::UTF8");
    expect(s).toContain("'C:\\Music\\Disney\\L''amore è.mp3'");
    expect(s).toContain("SendToRecycleBin");
  });

  it("dice in che cartella sta il file", () => {
    expect(folderName("C:\\Users\\B\\Music\\Disney Italia\\01.mp3")).toBe("Disney Italia");
    expect(folderName("/music/Sum 41/x.mp3")).toBe("Sum 41");
  });
});

describe("casi limite della pulizia", () => {
  it("anteprime da 25 a 35 secondi compresi, in ordine di cartella e titolo", () => {
    const list = previews([
      song("c", "B", "X", 35, { path: "C:\\Music\\Rock\\B.mp3" }),
      song("a", "A", "X", 25, { path: "C:\\Music\\Rock\\A.mp3" }),
      song("b", "Z", "X", 30, { path: "C:\\Music\\Disney\\Z.mp3" }),
      song("lungo", "L", "X", 35.5),
      song("corto", "C", "X", 24.9),
      { id: "senza", title: "Senza durata" },
    ]);
    expect(list.map((s) => s.id)).toEqual(["b", "a", "c"]);
  });

  it("una versione lunga non si fonde con quella corta, anche con una catena di durate vicine", () => {
    const groups = duplicates([song("a", "T", "X", 200), song("b", "T", "X", 202), song("c", "T", "X", 204), song("d", "T", "X", 206)]);
    // Ogni gruppo sta entro 3 secondi dal primo: 200-202 e 204-206.
    expect(groups.map((g) => [g.keep.id, ...g.others.map((o) => o.id)])).toEqual([
      ["a", "b"],
      ["c", "d"],
    ]);
  });

  it("si tiene la copia con l'anno vero, poi quella arrivata prima", () => {
    const [g] = duplicates([
      song("1970", "T", "X", 200, { album: "A", year: 1970, created: "2024-01-01" }),
      song("vero", "T", "X", 200, { album: "A", year: 2001, created: "2025-01-01" }),
    ]);
    expect(g.keep.id).toBe("vero");
    const [h] = duplicates([song("dopo", "T", "X", 200, { created: "2025-01-01" }), song("prima", "T", "X", 200, { created: "2024-01-01" })]);
    expect(h.keep.id).toBe("prima");
  });

  it("brani senza durata non sono doppioni", () => {
    expect(duplicates([{ id: "a", title: "T", artist: "X" }, { id: "b", title: "T", artist: "X" }])).toEqual([]);
  });

  it("due copie buttate dello stesso brano nella stessa playlist diventano una sola tenuta", () => {
    const kept = new Map([
      ["c1", "k"],
      ["c2", "k"],
    ]);
    expect(replaceCopies(["x", "c1", "y", "c2"], kept)).toEqual(["x", "k", "y"]);
  });

  it("percorsi di Windows e apici per PowerShell", () => {
    expect(isWindowsPath("C:\\Music\\a.mp3")).toBe(true);
    expect(isWindowsPath("/music/a.mp3")).toBe(false);
    expect(isWindowsPath("C:/Music/a.mp3")).toBe(false);
    expect(psQuote("L'isola d'Elba")).toBe("'L''isola d''Elba'");
  });

  it("lo script ha le righe di Windows e non cancella mai per sempre", () => {
    const s = trashScript(["C:\\Music\\a.mp3", "C:\\Music\\b.mp3"]);
    expect(s.split("\r\n").length).toBeGreaterThan(10);
    expect(s).not.toMatch(/Remove-Item|\bdel\b|DeletePermanently/i);
    expect(s).toContain("'C:\\Music\\a.mp3',\r\n  'C:\\Music\\b.mp3'");
  });
});
