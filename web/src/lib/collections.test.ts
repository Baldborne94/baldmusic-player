import { describe, expect, it } from "vitest";
import { collectionComment, collectionFolders, folderOf, isRealPath, planSync } from "./collections";

describe("collectionFolders", () => {
  it("una raccolta per ogni prima cartella, brani in ordine di percorso", () => {
    const folders = collectionFolders([
      { id: "b", path: "C:\\Users\\Baldo\\Music\\Disney Italia\\10 - Hakuna Matata.mp3" },
      { id: "a", path: "C:\\Users\\Baldo\\Music\\Disney Italia\\2 - Il cerchio della vita.mp3" },
      { id: "c", path: "C:\\Users\\Baldo\\Music\\SLIPKNOT\\Iowa\\01 - People = Shit.flac" },
      { id: "d", path: "C:\\Users\\Baldo\\Music\\sciolto.mp3" },
    ]);
    expect([...folders.keys()]).toEqual(["Disney Italia", "SLIPKNOT"]);
    expect(folders.get("Disney Italia")).toEqual(["a", "b"]);
    expect(folders.get("SLIPKNOT")).toEqual(["c"]);
  });

  it("funziona con i percorsi di Linux e Mac", () => {
    const folders = collectionFolders([
      { id: "a", path: "/music/Sum 41/In Too Deep.mp3" },
      { id: "b", path: "/music/Linkin Park/Numb.mp3" },
    ]);
    expect([...folders.keys()]).toEqual(["Linkin Park", "Sum 41"]);
  });

  it("niente raccolte se Navidrome manda i percorsi finti", () => {
    expect(isRealPath("Avril Lavigne/Let Go/01 - Losing Grip.mp3")).toBe(false);
    expect(collectionFolders([{ id: "a", path: "Avril Lavigne/Let Go/01 - Losing Grip.mp3" }]).size).toBe(0);
  });
});

describe("planSync", () => {
  const folders = new Map([
    ["Disney Italia", ["a", "b"]],
    ["Sum 41", ["c"]],
  ]);
  const disney = { id: "p1", comment: collectionComment("Disney Italia") };

  it("riconosce le raccolte dal commento", () => {
    expect(folderOf(disney)).toBe("Disney Italia");
    expect(folderOf({ comment: "La mia playlist" })).toBeNull();
    expect(folderOf({})).toBeNull();
  });

  it("al primo giro crea una raccolta per cartella", () => {
    const plan = planSync(folders, [{ id: "x" }], null);
    expect(plan.create).toEqual([
      { folder: "Disney Italia", songIds: ["a", "b"] },
      { folder: "Sum 41", songIds: ["c"] },
    ]);
    expect(plan.state.known["Sum 41"]).toEqual(["c"]);
  });

  it("aggiunge solo i brani nuovi della cartella: quelli tolti a mano non tornano", () => {
    const plan = planSync(new Map([["Disney Italia", ["a", "b", "n"]]]), [disney], { known: { "Disney Italia": ["a", "b"] } });
    expect(plan.add).toEqual([{ playlistId: "p1", songIds: ["n"] }]);
    expect(plan.create).toEqual([]);
  });

  it("una raccolta eliminata non si ricrea", () => {
    const plan = planSync(folders, [disney], { known: { "Disney Italia": ["a", "b"], "Sum 41": ["c"] } });
    expect(plan.create).toEqual([]);
    expect(plan.missing).toEqual(["Sum 41"]);
  });

  it("una cartella nuova diventa una raccolta", () => {
    const plan = planSync(folders, [disney], { known: { "Disney Italia": ["a", "b"] } });
    expect(plan.create).toEqual([{ folder: "Sum 41", songIds: ["c"] }]);
  });

  it("su un browser nuovo non ricrea quelle che mancano e non aggiunge niente", () => {
    const plan = planSync(folders, [disney], null);
    expect(plan.create).toEqual([]);
    expect(plan.add).toEqual([]);
    expect(plan.missing).toEqual(["Sum 41"]);
    expect(plan.state.known).toEqual({ "Disney Italia": ["a", "b"], "Sum 41": ["c"] });
  });
});
