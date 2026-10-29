import { describe, expect, it } from "vitest";
import { collectionComment, collectionFolders, deletedInStore, deletedStoreComment, folderOf, isRealPath, planFollow, planSync } from "./collections";

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

  it("una raccolta sparita da quando questo browser l'ha vista è eliminata: non si ricrea e va nell'elenco", () => {
    const plan = planSync(folders, [disney], { known: { "Disney Italia": ["a", "b"], "Sum 41": ["c"] }, v: 2 });
    expect(plan.create).toEqual([]);
    expect(plan.deleted).toEqual(["Sum 41"]);
    expect(plan.newlyDeleted).toEqual(["Sum 41"]);
    expect(plan.state.known).toEqual({ "Disney Italia": ["a", "b"] });
  });

  it("una cartella nuova diventa una raccolta", () => {
    const plan = planSync(folders, [disney], { known: { "Disney Italia": ["a", "b"] }, v: 2 });
    expect(plan.create).toEqual([{ folder: "Sum 41", songIds: ["c"] }]);
  });

  it("su un browser nuovo crea quelle che mancano, tranne le eliminate, e non aggiunge niente", () => {
    expect(planSync(folders, [disney], null).create).toEqual([{ folder: "Sum 41", songIds: ["c"] }]);
    const plan = planSync(folders, [disney], null, new Set(["Sum 41"]));
    expect(plan.create).toEqual([]);
    expect(plan.add).toEqual([]);
    expect(plan.deleted).toEqual(["Sum 41"]);
    expect(plan.newlyDeleted).toEqual([]);
    expect(plan.state.known).toEqual({ "Disney Italia": ["a", "b"] });
  });

  it("con lo stato di prima (senza versione) le cartelle senza raccolta restano da decidere", () => {
    const plan = planSync(folders, [disney], { known: { "Disney Italia": ["a", "b"], "Sum 41": ["c"] } });
    expect(plan.missing).toEqual(["Sum 41"]);
    expect(plan.create).toEqual([]);
    expect(planSync(folders, [disney], plan.state).missing).toEqual(["Sum 41"]);
  });

  it("l'elenco delle eliminate sta nel commento di una playlist sua", () => {
    const store = { comment: deletedStoreComment(["Rock", "AC/DC «live»", "Rock"]) };
    expect(deletedInStore(store)).toEqual(["AC/DC «live»", "Rock"]);
    expect(deletedInStore(disney)).toBeNull();
    expect(deletedInStore({ comment: "BaldMusic: raccolte eliminate {rotto" })).toEqual([]);
  });
});

describe("planFollow", () => {
  const t = (id: string, title: string, duration = 200) => ({ id, title, duration });

  it("la raccolta di una cartella sparita segue quella nuova con almeno metà dei suoi brani", () => {
    const folders = new Map([
      ["Linkin Park", [t("1", "Numb"), t("2", "In the End"), t("3", "Faint")]],
      ["Rock", [t("9", "Altro")]],
    ]);
    const orphan = { id: "p1", name: "LINKIN PARK", folder: "LINKIN PARK", songs: [t("1", "Numb"), t("2", "In the End")] };
    const [f] = planFollow(folders, [orphan], new Set(["Rock"]), new Set());
    expect(f).toMatchObject({ playlistId: "p1", folder: "Linkin Park", rename: "Linkin Park", songIds: ["1", "2"] });
    expect(f.newIds.size).toBe(0);
    // Nome cambiato da te: resta.
    expect(planFollow(folders, [{ ...orphan, name: "Mia" }], new Set(["Rock"]), new Set())[0].rename).toBeNull();
  });

  it("ID nuovi dei file: si ritrovano per titolo e durata entro 2 s", () => {
    const folders = new Map([["Nuova", [t("n1", "Numb", 186), t("n2", "In The End!", 217)]]]);
    const orphan = { id: "p1", name: "Vecchia", folder: "Vecchia", songs: [t("o1", "Numb", 187), t("o2", "In the End", 216), t("x", "Messo a mano")] };
    const [f] = planFollow(folders, [orphan], new Set(), new Set());
    expect(f.songIds).toEqual(["n1", "n2", "x"]);
    expect([...f.newIds]).toEqual([["o1", "n1"], ["o2", "n2"]]);
  });

  it("meno di metà dei brani, cartella già raccolta o eliminata: non segue", () => {
    const folders = new Map([["Nuova", [t("1", "A"), t("5", "E")]]]);
    const orphan = { id: "p1", name: "V", folder: "V", songs: [t("1", "A"), t("2", "B"), t("3", "C")] };
    expect(planFollow(folders, [orphan], new Set(), new Set())).toEqual([]);
    const half = { ...orphan, songs: orphan.songs.slice(0, 2) };
    expect(planFollow(folders, [half], new Set(["Nuova"]), new Set())).toEqual([]);
    expect(planFollow(folders, [half], new Set(), new Set(["Nuova"]))).toEqual([]);
    expect(planFollow(folders, [half], new Set(), new Set())).toHaveLength(1);
  });

  it("due raccolte e una cartella: la prende la più somigliante", () => {
    const folders = new Map([["Nuova", [t("1", "A"), t("2", "B")]]]);
    const a = { id: "a", name: "X", folder: "X", songs: [t("1", "A")] };
    const b = { id: "b", name: "Y", folder: "Y", songs: [t("1", "A"), t("2", "B")] };
    expect(planFollow(folders, [a, b], new Set(), new Set()).map((f) => f.playlistId)).toEqual(["b"]);
  });
});

describe("casi limite delle cartelle", () => {
  it("percorsi veri: disco di Windows con / o \\, Linux, rete; finti senza radice", () => {
    expect(isRealPath("C:/Users/B/Music/x.mp3")).toBe(true);
    expect(isRealPath("D:\\Musica\\x.flac")).toBe(true);
    expect(isRealPath("\\\\nas\\musica\\x.mp3")).toBe(true);
    expect(isRealPath("/srv/music/x.mp3")).toBe(true);
    expect(isRealPath("Disney/Album/x.mp3")).toBe(false);
  });

  it("i percorsi finti si ignorano anche mescolati a quelli veri", () => {
    const folders = collectionFolders([
      { id: "a", path: "C:\\Music\\Rock\\a.mp3" },
      { id: "b", path: "C:\\Music\\Pop\\b.mp3" },
      { id: "f", path: "Rock/Album/f.mp3" },
      { id: "n" },
    ]);
    expect([...folders.entries()]).toEqual([
      ["Pop", ["b"]],
      ["Rock", ["a"]],
    ]);
  });

  it("le sottocartelle stanno nella raccolta della prima cartella, in ordine naturale", () => {
    const folders = collectionFolders([
      { id: "2", path: "/m/Movie Soundtracks/Il Gladiatore/10 - Now We Are Free.mp3" },
      { id: "1", path: "/m/Movie Soundtracks/Il Gladiatore/2 - The Battle.mp3" },
      { id: "0", path: "/m/Movie Soundtracks/Avatar/1 - You Don't Dream.mp3" },
      { id: "x", path: "/m/Rock/x.mp3" },
    ]);
    expect(folders.get("Movie Soundtracks")).toEqual(["0", "1", "2"]);
  });

  it("due raccolte della stessa cartella: conta la prima, nessuna si ricrea", () => {
    const plan = planSync(
      new Map([["Rock", ["a", "n"]]]),
      [
        { id: "p1", comment: collectionComment("Rock") },
        { id: "p2", comment: collectionComment("Rock") },
      ],
      { known: { Rock: ["a"] } },
    );
    expect(plan.add).toEqual([{ playlistId: "p1", songIds: ["n"] }]);
    expect(plan.create).toEqual([]);
  });

  it("una cartella sparita resta ricordata, se torna senza la sua raccolta non si ricrea da sola", () => {
    const plan = planSync(new Map(), [], { known: { Vecchia: ["a"] }, v: 2 });
    expect(plan.state.known).toEqual({ Vecchia: ["a"] });
    expect(planSync(new Map([["Vecchia", ["a"]]]), [], plan.state).deleted).toEqual(["Vecchia"]);
  });

  it("nomi di cartella con caratteri strani passano dal commento", () => {
    for (const f of ["Disney «Italia»", "AC/DC", "L'ultimo (2024) [FLAC]"]) expect(folderOf({ comment: collectionComment(f) })).toBe(f);
  });
});
