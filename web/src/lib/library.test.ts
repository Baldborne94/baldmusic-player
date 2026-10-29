import { afterEach, describe, expect, it, vi } from "vitest";
import { CREDS, fakeFetch, memoryStorage } from "../test/env";
import { FakeNavidrome, fileSong, folderSongs, MUSIC } from "../test/fakeNavidrome";
import { collectionComment, deletedInStore, DELETED_STORE_NAME } from "./collections";
import type { Song } from "./subsonic";
import type { TrashRecord } from "./trash";

// library.ts tiene lo stato in variabili del modulo: ogni test lo ricarica da capo,
// con il suo localStorage e il suo Navidrome finto.
async function open(server: FakeNavidrome, storage: Storage = memoryStorage(), creds = CREDS) {
  vi.resetModules();
  vi.stubGlobal("localStorage", storage);
  vi.stubGlobal("fetch", fakeFetch(server));
  const session = await import("./session");
  session.saveCredentials(creds);
  const lib = await import("./library");
  return { lib, storage, session };
}

afterEach(() => {
  vi.unstubAllGlobals();
});

const STATE_KEY = "baldmusic.raccolte.http://nd.test|b";
const TRASH_KEY = "baldmusic.cestino.http://nd.test|b";

// Con una cartella sola, quella sarebbe la cartella principale: ne serve sempre un'altra.
const rock = () => fileSong("r-1", "Rock", "Un altro");

const collection = (id: string, folder: string, songIds: string[], owner?: string) => ({ id, name: folder, comment: collectionComment(folder), songIds, owner });

/** Quanti songId (o songIdToAdd) porta al massimo una sola richiesta. */
const maxIdsPerCall = (server: FakeNavidrome) => Math.max(0, ...server.calls.map((c) => c.params.getAll("songId").length + c.params.getAll("songIdToAdd").length));

describe("syncCollections", () => {
  it("crea una raccolta per ogni prima cartella, riconosciuta dal commento, brani in ordine di percorso", async () => {
    const server = new FakeNavidrome({
      songs: [
        fileSong("b", "Disney Italia", "10 - Hakuna Matata"),
        fileSong("a", "Disney Italia", "2 - Il cerchio della vita"),
        fileSong("c", "Movie Soundtracks", "01 - Now We Are Free"),
        fileSong("sciolto", "", "sciolto"),
      ],
    });
    // Il file sciolto sta nella cartella principale: non è di nessuna raccolta.
    server.songs[3].path = `${MUSIC}\\sciolto.mp3`;
    const { lib, storage } = await open(server);
    await lib.syncCollections();

    expect(server.playlists.map((p) => [p.name, p.comment, p.songIds])).toEqual([
      ["Disney Italia", collectionComment("Disney Italia"), ["a", "b"]],
      ["Movie Soundtracks", collectionComment("Movie Soundtracks"), ["c"]],
    ]);
    // Lo stato è per server e utente.
    expect(JSON.parse(storage.getItem(STATE_KEY)!)).toEqual({ known: { "Disney Italia": ["a", "b"], "Movie Soundtracks": ["c"] }, v: 2, unsure: [] });
  });

  it("una cartella grande va a blocchi di 200 brani per richiesta, senza perderne", async () => {
    const songs = folderSongs("Disney Italia", 450);
    const server = new FakeNavidrome({ songs: [...songs, rock()] });
    const { lib } = await open(server);
    await lib.syncCollections();

    expect(server.playlistByName("Disney Italia")!.songIds).toEqual(songs.map((s) => s.id));
    expect(maxIdsPerCall(server)).toBe(200);
    expect(server.callsOf("createPlaylist")).toHaveLength(2);
    // Commento, poi i 250 che restano in due blocchi; poi il commento di Rock.
    expect(server.callsOf("updatePlaylist").map((c) => c.params.getAll("songIdToAdd").length)).toEqual([0, 200, 50, 0]);
  });

  it("alla riapertura entrano solo i brani nuovi; quelli tolti a mano non tornano", async () => {
    const server = new FakeNavidrome({ songs: [...folderSongs("Disney Italia", 3, "d"), rock()] });
    const storage = memoryStorage();
    await (await open(server, storage)).lib.syncCollections();
    const pl = server.playlists[0];
    // L'utente toglie a mano il secondo brano dalla raccolta; nella cartella arriva un file nuovo.
    pl.songIds = ["d-1", "d-3"];
    server.songs.push(fileSong("d-4", "Disney Italia", "4 - Nuovo"));

    await (await open(server, storage)).lib.syncCollections();
    expect(pl.songIds).toEqual(["d-1", "d-3", "d-4"]);
    expect(server.playlists).toHaveLength(2);
  });

  it("un brano nuovo già messo a mano nella raccolta non entra due volte", async () => {
    const server = new FakeNavidrome({ songs: [...folderSongs("Disney Italia", 2, "d"), rock()] });
    const storage = memoryStorage();
    await (await open(server, storage)).lib.syncCollections();
    server.songs.push(fileSong("d-3", "Disney Italia", "3 - Nuovo"));
    server.playlists[0].songIds.unshift("d-3");

    await (await open(server, storage)).lib.syncCollections();
    expect(server.playlists[0].songIds).toEqual(["d-3", "d-1", "d-2"]);
  });

  it("una raccolta eliminata fuori dall'app non si ricrea e lo si scrive su Navidrome", async () => {
    const server = new FakeNavidrome({ songs: [...folderSongs("Disney Italia", 2, "d"), ...folderSongs("Rock", 2, "r")] });
    const storage = memoryStorage();
    await (await open(server, storage)).lib.syncCollections();
    server.playlists = server.playlists.filter((p) => p.name !== "Rock");

    const { lib } = await open(server, storage);
    await lib.syncCollections();
    expect(server.playlistByName("Rock")).toBeUndefined();
    expect(deletedInStore(server.playlistByName(DELETED_STORE_NAME)!)).toEqual(["Rock"]);
    expect(stateOf(lib).collections).toEqual({ state: "ready", missing: [], deleted: ["Rock"] });
    // L'elenco è una playlist solo per l'app: non si vede tra le tue.
    expect(stateOf(lib).playlists.map((p) => p.name)).toEqual(["Disney Italia"]);

    // Ricreata a mano dalla Libreria: con tutti i brani della cartella, ed esce dall'elenco.
    await lib.createCollection("Rock");
    expect(server.playlistByName("Rock")?.songIds).toEqual(["r-1", "r-2"]);
    expect(server.playlistByName("Rock")?.comment).toBe(collectionComment("Rock"));
    expect(deletedInStore(server.playlistByName(DELETED_STORE_NAME)!)).toEqual([]);
    expect(stateOf(lib).collections.deleted).toEqual([]);
  });

  it("un browser nuovo crea le raccolte che mancano, tranne quelle eliminate dall'app (anche da un altro browser)", async () => {
    const server = new FakeNavidrome({ songs: [...folderSongs("Disney Italia", 2, "d"), ...folderSongs("Rock", 2, "r"), ...folderSongs("Pop", 1, "p")] });
    const first = await open(server);
    await first.lib.syncCollections();
    await first.lib.deletePlaylist(server.playlistByName("Pop")!.id);
    server.playlists = server.playlists.filter((p) => p.name !== "Rock");

    // Un altro browser (o il tablet): niente stato suo.
    const { lib } = await open(server);
    await lib.syncCollections();
    // Prima restavano tutte da creare a mano: ora Rock torna, Pop (eliminata dall'app) no.
    expect(server.playlistByName("Rock")?.songIds).toEqual(["r-1", "r-2"]);
    expect(server.playlistByName("Pop")).toBeUndefined();
    expect(stateOf(lib).collections.deleted).toEqual(["Pop"]);
  });

  it("stato di prima di questa versione: le cartelle senza raccolta le decidi tu (Crea tutte / Non crearle)", async () => {
    const server = new FakeNavidrome({
      songs: [...folderSongs("Disney Italia", 2, "d"), ...folderSongs("Rock", 2, "r"), ...folderSongs("Pop", 1, "p")],
      playlists: [collection("p1", "Disney Italia", ["d-1", "d-2"])],
    });
    const storage = memoryStorage({ [STATE_KEY]: JSON.stringify({ known: { "Disney Italia": ["d-1", "d-2"], Rock: ["r-1", "r-2"], Pop: ["p-1"] } }) });
    let { lib } = await open(server, storage);
    await lib.syncCollections();
    expect(stateOf(lib).collections.missing).toEqual(["Pop", "Rock"]);
    expect(server.callsOf("createPlaylist")).toHaveLength(0);
    // Riaprendo restano da decidere: non si creano da sole.
    ({ lib } = await open(server, storage));
    await lib.syncCollections();
    expect(stateOf(lib).collections.missing).toEqual(["Pop", "Rock"]);

    await lib.createCollection("Rock");
    await lib.dismissMissingCollections();
    expect(server.playlistByName("Rock")?.songIds).toEqual(["r-1", "r-2"]);
    expect(deletedInStore(server.playlistByName(DELETED_STORE_NAME)!)).toEqual(["Pop"]);
    ({ lib } = await open(server, storage));
    await lib.syncCollections();
    expect(stateOf(lib).collections).toMatchObject({ missing: [], deleted: ["Pop"] });
    expect(server.playlistByName("Pop")).toBeUndefined();
  });

  it("cartella rinominata sul PC: la raccolta la segue col nome nuovo, niente doppione", async () => {
    // «LINKIN PARK» rinominata in «Linkin Park»: Navidrome tiene gli ID dei file.
    const server = new FakeNavidrome({
      songs: [...folderSongs("Linkin Park", 3, "l"), rock()],
      playlists: [collection("p1", "LINKIN PARK", ["l-1", "l-2", "l-3"]), collection("p2", "Rock", ["r-1"])],
    });
    const storage = memoryStorage({ [STATE_KEY]: JSON.stringify({ known: { "LINKIN PARK": ["l-1", "l-2", "l-3"], Rock: ["r-1"] }, v: 2 }) });
    const { lib } = await open(server, storage);
    await lib.syncCollections();
    expect(server.callsOf("createPlaylist")).toHaveLength(0);
    expect(server.playlist("p1")).toMatchObject({ name: "Linkin Park", comment: collectionComment("Linkin Park"), songIds: ["l-1", "l-2", "l-3"] });
    expect(JSON.parse(storage.getItem(STATE_KEY)!).known).toEqual({ "Linkin Park": ["l-1", "l-2", "l-3"], Rock: ["r-1"] });
    // Un nome scelto da te resta.
    server.playlist("p1")!.name = "I miei Linkin";
    server.songs = server.songs.map((s) => ({ ...s, path: s.path!.replace("Linkin Park", "Linkin Park (2024)") }));
    await (await open(server, storage)).lib.syncCollections();
    expect(server.playlist("p1")).toMatchObject({ name: "I miei Linkin", comment: collectionComment("Linkin Park (2024)") });
  });

  it("lo stato è per server e utente: un altro utente sullo stesso browser parte da capo", async () => {
    const storage = memoryStorage();
    await (await open(new FakeNavidrome({ songs: [...folderSongs("Disney Italia", 2, "d"), rock()] }), storage)).lib.syncCollections();
    const other = new FakeNavidrome({ user: "c", songs: [...folderSongs("Disney Italia", 2, "d"), rock()] });
    await (await open(other, storage, { ...CREDS, user: "c" })).lib.syncCollections();
    expect(other.playlists.map((p) => p.owner)).toEqual(["c", "c"]);
    expect(storage.getItem("baldmusic.raccolte.http://nd.test|c")).not.toBeNull();
    expect(storage.getItem(STATE_KEY)).not.toBeNull();
  });

  it("senza «Report Real Path» Navidrome manda percorsi finti: noRealPath, niente raccolte", async () => {
    const server = new FakeNavidrome({ songs: [{ id: "a", title: "Numb", path: "Linkin Park/Meteora/01 - Numb.mp3" }] });
    const { lib } = await open(server);
    await lib.syncCollections();
    expect(stateOf(lib).collections).toEqual({ state: "noRealPath", missing: [] });
    expect(server.playlists).toEqual([]);
  });

  it("libreria vuota: pronto, non noRealPath", async () => {
    const { lib } = await open(new FakeNavidrome());
    await lib.syncCollections();
    expect(stateOf(lib).collections.state).toBe("ready");
  });

  it("chiamata due volte insieme, allinea una volta sola", async () => {
    const server = new FakeNavidrome({ songs: [...folderSongs("Disney Italia", 2, "d"), rock()] });
    const { lib } = await open(server);
    await Promise.all([lib.syncCollections(), lib.syncCollections()]);
    expect(server.callsOf("search3")).toHaveLength(1);
    expect(server.playlists).toHaveLength(2);
  });

  it("un errore del server finisce nello stato, non in un'eccezione", async () => {
    const server = new FakeNavidrome({ songs: [...folderSongs("Disney Italia", 2, "d"), rock()] });
    server.failing.add("search3");
    const { lib } = await open(server);
    await lib.syncCollections();
    expect(stateOf(lib).collections).toMatchObject({ state: "error", error: "Errore finto su search3" });
    // Le playlist si leggono comunque.
    expect(stateOf(lib).loaded).toBe(true);
  });
});

/** Lo stato della libreria, come lo vede l'interfaccia. */
const stateOf = (lib: typeof import("./library")) => lib.getLibraryState();

describe("mergeDuplicates", () => {
  const dup = (keep: Song, ...others: Song[]) => ({ keep, others });
  const k = fileSong("tenuto", "Rock", "Numb");
  const copy = fileSong("copia", "Disney Italia", "Numb");

  it("nelle playlist proprie la copia buttata diventa quella tenuta, nello stesso posto", async () => {
    const server = new FakeNavidrome({
      songs: [k, copy, fileSong("x", "Rock", "x"), fileSong("y", "Rock", "y")],
      playlists: [
        collection("p1", "Disney Italia", ["x", "copia", "y"]),
        { id: "p2", name: "Senza copie", songIds: ["x", "y"] },
        { id: "p3", name: "Di un altro", songIds: ["copia"], owner: "altro" },
      ],
    });
    const { lib } = await open(server);
    await lib.refreshLibrary();
    await lib.mergeDuplicates([dup(k, copy)]);
    expect(server.playlist("p1")!.songIds).toEqual(["x", "tenuto", "y"]);
    // Le altre non si toccano: quella senza copie non si riscrive, quella di un altro non si può.
    expect(server.callsOf("createPlaylist").map((c) => c.params.get("playlistId"))).toEqual(["p1"]);
    expect(server.playlist("p3")!.songIds).toEqual(["copia"]);
  });

  it("se la playlist ha già la copia tenuta, quella buttata sparisce e basta", async () => {
    const server = new FakeNavidrome({ songs: [k, copy], playlists: [{ id: "p1", name: "P", songIds: ["tenuto", "copia"] }] });
    const { lib } = await open(server);
    await lib.refreshLibrary();
    await lib.mergeDuplicates([dup(k, copy)]);
    expect(server.playlist("p1")!.songIds).toEqual(["tenuto"]);
  });

  it("il cuore passa alla copia tenuta, se non lo aveva già", async () => {
    const other = fileSong("copia2", "Pop", "Numb");
    const k2 = fileSong("tenuto2", "Rock", "Iris");
    const c2 = fileSong("copia3", "Pop", "Iris");
    const server = new FakeNavidrome({ songs: [k, copy, other, k2, c2], starred: ["copia", "copia2", "tenuto2", "copia3"] });
    const { lib } = await open(server);
    await lib.refreshLibrary();
    await lib.mergeDuplicates([dup(k, copy, other), dup(k2, c2)]);
    expect(server.callsOf("star").map((c) => c.params.get("id"))).toEqual(["tenuto"]);
    expect(lib.getLibraryState().liked.has("tenuto")).toBe(true);
  });

  it("senza doppioni non chiama il server", async () => {
    const server = new FakeNavidrome({ playlists: [{ id: "p1", name: "P", songIds: [] }] });
    const { lib } = await open(server);
    await lib.refreshLibrary();
    const before = server.calls.length;
    await lib.mergeDuplicates([]);
    expect(server.calls.length).toBe(before);
  });
});

describe("cestino", () => {
  it("rememberTrashed ricorda solo i file col percorso, col doppione tenuto, per server e utente", async () => {
    const { lib, storage } = await open(new FakeNavidrome());
    lib.rememberTrashed([fileSong("a", "Rock", "A"), { id: "senza", title: "Senza percorso" }, fileSong("b", "Pop", "B")], new Map([["b", "tenuto"]]));
    const saved = JSON.parse(storage.getItem(TRASH_KEY)!) as TrashRecord[];
    expect(saved.map((r) => [r.path, r.keptId])).toEqual([
      [`${MUSIC}\\Rock\\A.mp3`, undefined],
      [`${MUSIC}\\Pop\\B.mp3`, "tenuto"],
    ]);
    expect(lib.trashedFiles()).toEqual(saved);
  });

  it("forgetTrashed toglie dall'elenco, o con gone li segna come già nel Cestino", async () => {
    const { lib } = await open(new FakeNavidrome());
    lib.rememberTrashed([fileSong("a", "Rock", "A"), fileSong("b", "Rock", "B")]);
    const [a, b] = [`${MUSIC}\\Rock\\A.mp3`, `${MUSIC}\\Rock\\B.mp3`];
    lib.forgetTrashed([a], true);
    expect(lib.trashedFiles().map((r) => [r.path, r.gone])).toEqual([
      [a, true],
      [b, undefined],
    ]);
    lib.forgetTrashed([b]);
    expect(lib.trashedFiles().map((r) => r.path)).toEqual([a]);
  });

  it("senza archiviazione non si rompe niente: l'elenco resta vuoto", async () => {
    const { lib } = await open(new FakeNavidrome());
    vi.stubGlobal("localStorage", memoryStorage({}, true));
    expect(() => lib.rememberTrashed([fileSong("a", "Rock", "A")])).not.toThrow();
    expect(lib.trashedFiles()).toEqual([]);
  });

  it("un file buttato e poi ripristinato torna nella sua raccolta al posto della copia tenuta", async () => {
    // Prima della pulizia: «Numb» sta in Disney Italia (copia) e in Rock (tenuta).
    const thrown = fileSong("copia", "Disney Italia", "Numb");
    const kept = fileSong("tenuto", "Rock", "Numb");
    const server = new FakeNavidrome({ songs: [fileSong("d1", "Disney Italia", "A"), thrown, fileSong("d3", "Disney Italia", "C"), kept] });
    const storage = memoryStorage();
    let { lib } = await open(server, storage);
    await lib.syncCollections();
    const disney = server.playlistByName("Disney Italia")!;
    expect(disney.songIds).toEqual(["d1", "d3", "copia"]);

    // Pulizia: la raccolta passa alla copia tenuta, lo script butta la copia.
    await lib.mergeDuplicates([{ keep: kept, others: [thrown] }]);
    lib.rememberTrashed([thrown], new Map([["copia", "tenuto"]]));
    expect(disney.songIds).toEqual(["d1", "d3", "tenuto"]);

    // Finché lo script non parte il file c'è ancora: niente cambia.
    let present = await lib.checkTrash();
    expect(present.has(thrown.path!)).toBe(true);
    expect(lib.trashedFiles()[0].gone).toBeUndefined();

    // Lo script parte: Navidrome non vede più il file.
    server.songs = server.songs.filter((s) => s.id !== "copia");
    ({ lib } = await open(server, storage));
    present = await lib.checkTrash();
    expect(present.has(thrown.path!)).toBe(false);
    expect(lib.trashedFiles()[0].gone).toBe(true);

    // Ripristinato (dall'app o a mano): torna al posto della copia tenuta e lascia l'elenco.
    server.songs.push(thrown);
    ({ lib } = await open(server, storage));
    await lib.checkTrash();
    expect(disney.songIds).toEqual(["d1", "d3", "copia"]);
    expect(lib.trashedFiles()).toEqual([]);
    expect(server.playlistByName("Rock")!.songIds).toEqual(["tenuto"]);
  });

  it("un file buttato da una raccolta (senza doppione) torna in fondo alla raccolta quando ricompare", async () => {
    const server = new FakeNavidrome({ songs: [...folderSongs("Disney Italia", 3, "d"), rock()] });
    const storage = memoryStorage();
    let { lib } = await open(server, storage);
    await lib.syncCollections();
    const disney = server.playlistByName("Disney Italia")!;
    const thrown = server.songs[0];
    // Dalla pagina della raccolta: tolto e mandato nel Cestino.
    await lib.removeManyFromPlaylist(disney.id, [0]);
    lib.rememberTrashed([thrown]);
    // Lo script non è ancora partito: il file c'è e non rientra.
    await lib.syncCollections();
    expect(disney.songIds).toEqual(["d-2", "d-3"]);

    server.songs = server.songs.filter((s) => s !== thrown);
    ({ lib } = await open(server, storage));
    await lib.checkTrash();
    server.songs.unshift(thrown);
    ({ lib } = await open(server, storage));
    await lib.checkTrash();
    expect(disney.songIds).toEqual(["d-2", "d-3", "d-1"]);
    expect(lib.trashedFiles()).toEqual([]);
  });

  it("checkTrash dice quali file Navidrome vede adesso", async () => {
    const server = new FakeNavidrome({ songs: [fileSong("a", "Rock", "A"), { id: "b", title: "B" }] });
    const { lib } = await open(server);
    expect([...(await lib.checkTrash())]).toEqual([`${MUSIC}\\Rock\\A.mp3`]);
  });
});

describe("playlist", () => {
  it("addNewToPlaylist aggiunge in fondo solo i brani che mancano, una volta sola", async () => {
    const songs = ["a", "b", "c"].map((id) => fileSong(id, "Rock", id));
    const server = new FakeNavidrome({ songs, playlists: [{ id: "p1", name: "P", songIds: ["a"] }] });
    const { lib } = await open(server);
    expect(await lib.addNewToPlaylist("p1", [songs[0], songs[1], songs[1], songs[2]])).toBe(2);
    expect(server.playlist("p1")!.songIds).toEqual(["a", "b", "c"]);
    expect(await lib.addNewToPlaylist("p1", [songs[2]])).toBe(0);
    expect(server.callsOf("updatePlaylist")).toHaveLength(1);
  });

  it("addNewToPlaylist con tanti brani va a blocchi di 200", async () => {
    const songs = folderSongs("Rock", 401);
    const server = new FakeNavidrome({ songs, playlists: [{ id: "p1", name: "P", songIds: [] }] });
    const { lib } = await open(server);
    expect(await lib.addNewToPlaylist("p1", songs)).toBe(401);
    expect(server.playlist("p1")!.songIds).toEqual(songs.map((s) => s.id));
    expect(maxIdsPerCall(server)).toBe(200);
  });

  it("rewritePlaylist riscrive i brani nell'ordine nuovo con createPlaylist + playlistId", async () => {
    const server = new FakeNavidrome({ songs: ["a", "b", "c"].map((id) => fileSong(id, "Rock", id)), playlists: [{ id: "p1", name: "P", comment: "c", songIds: ["a", "b", "c"] }] });
    const { lib } = await open(server);
    await lib.rewritePlaylist("p1", ["c", "a", "b"]);
    expect(server.playlist("p1")).toMatchObject({ name: "P", comment: "c", songIds: ["c", "a", "b"] });
    expect(server.callsOf("createPlaylist")[0].params.get("name")).toBeNull();
  });

  it("removeManyFromPlaylist toglie per posizione", async () => {
    const server = new FakeNavidrome({ songs: ["a", "b", "c", "d"].map((id) => fileSong(id, "Rock", id)), playlists: [{ id: "p1", name: "P", songIds: ["a", "b", "c", "d"] }] });
    const { lib } = await open(server);
    await lib.removeManyFromPlaylist("p1", [1, 3]);
    expect(server.playlist("p1")!.songIds).toEqual(["a", "c"]);
  });

  it("isMine: le proprie (anche con maiuscole diverse) e quelle senza proprietario", async () => {
    const { lib } = await open(new FakeNavidrome());
    expect(lib.isMine({ id: "1", name: "x", owner: "b" })).toBe(true);
    expect(lib.isMine({ id: "1", name: "x", owner: "B" })).toBe(true);
    expect(lib.isMine({ id: "1", name: "x" })).toBe(true);
    expect(lib.isMine({ id: "1", name: "x", owner: "altro" })).toBe(false);
  });

  it("isCollection riconosce le raccolte dal commento", async () => {
    const { lib } = await open(new FakeNavidrome());
    expect(lib.isCollection({ id: "1", name: "x", comment: collectionComment("Rock") })).toBe(true);
    expect(lib.isCollection({ id: "1", name: "x", comment: "una playlist" })).toBe(false);
  });
});

describe("preferiti", () => {
  it("likeAll mette il cuore solo a quelli che non lo hanno, una volta sola", async () => {
    const songs = ["a", "b", "c"].map((id) => fileSong(id, "Rock", id));
    const server = new FakeNavidrome({ songs, starred: ["a"] });
    const { lib } = await open(server);
    await lib.refreshLibrary();
    expect(await lib.likeAll([songs[0], songs[1], songs[1], songs[2]])).toBe(2);
    expect(server.callsOf("star").map((c) => c.params.get("id"))).toEqual(["b", "c"]);
    expect([...lib.getLibraryState().liked].sort()).toEqual(["a", "b", "c"]);
    const calls = server.calls.length;
    expect(await lib.likeAll([songs[1]])).toBe(0);
    expect(server.calls.length).toBe(calls);
  });

  it("toggleLike cambia subito l'interfaccia e torna indietro se il server rifiuta", async () => {
    const song = fileSong("a", "Rock", "a");
    const server = new FakeNavidrome({ songs: [song] });
    const { lib } = await open(server);
    await lib.refreshLibrary();
    const done = lib.toggleLike(song);
    expect(lib.getLibraryState().liked.has("a")).toBe(true);
    await done;
    expect(server.starred.has("a")).toBe(true);

    server.failing.add("unstar");
    await lib.toggleLike(song);
    // Il server ha detto di no: si rilegge, e il cuore resta.
    expect(lib.getLibraryState().liked.has("a")).toBe(true);
  });
});

describe("ricerche recenti", () => {
  it("le più recenti in cima, senza doppioni (maiuscole comprese), al massimo 10", async () => {
    const { lib } = await open(new FakeNavidrome());
    lib.rememberSearch("  lucio  ");
    lib.rememberSearch("x");
    lib.rememberSearch("Vasco");
    lib.rememberSearch("LUCIO");
    expect(lib.recentSearches()).toEqual(["LUCIO", "Vasco"]);
    for (let i = 0; i < 12; i++) lib.rememberSearch(`ricerca ${i}`);
    expect(lib.recentSearches()).toHaveLength(10);
    expect(lib.recentSearches()[0]).toBe("ricerca 11");
    lib.clearSearches();
    expect(lib.recentSearches()).toEqual([]);
  });

  it("archiviazione rotta o bloccata: elenco vuoto, nessun errore", async () => {
    const { lib } = await open(new FakeNavidrome(), memoryStorage({ "baldmusic.searches": "{non è json" }));
    expect(lib.recentSearches()).toEqual([]);
    vi.stubGlobal("localStorage", memoryStorage({}, true));
    expect(() => lib.rememberSearch("lucio")).not.toThrow();
    expect(() => lib.clearSearches()).not.toThrow();
  });
});
