import { collectionComment } from "../src/lib/collections";
import type { Song } from "../src/lib/subsonic";
import { fileSong, folderSongs } from "../src/test/fakeNavidrome";
import { acceptDialogs, center, dragWithMouse, expect, rows, test } from "./fixtures";

const collection = (id: string, folder: string, songs: Song[]) => ({ id, name: folder, comment: collectionComment(folder), songIds: songs.map((s) => s.id) });
const titles = (page: import("@playwright/test").Page) => page.locator(".main .trow__title").allTextContents();

test.describe("Libreria e raccolte", () => {
  test("le raccolte nascono dalle cartelle (percorsi veri di Windows)", async ({ page, navidrome, login }) => {
    const disney = folderSongs("Disney Italia", 6, "d");
    const movies = folderSongs("Movie Soundtracks", 4, "m");
    const server = await navidrome({ songs: [...movies, ...disney] });
    await login("#/library");

    const cards = page.locator(".main .card");
    await expect(cards).toHaveCount(2);
    await expect(cards.nth(0)).toContainText("Disney Italia");
    await expect(cards.nth(0)).toContainText("Raccolta · 6 brani");
    await expect(cards.nth(1)).toContainText("Movie Soundtracks");
    // Anche nella barra laterale, che parte dalle raccolte.
    await expect(page.locator(".side .lib-row", { hasText: "Disney Italia" })).toContainText("Raccolta · 6 brani");
    expect(server.playlists.map((p) => [p.name, p.comment, p.songIds.length])).toEqual([
      ["Disney Italia", collectionComment("Disney Italia"), 6],
      ["Movie Soundtracks", collectionComment("Movie Soundtracks"), 4],
    ]);
    expect(server.playlistByName("Disney Italia")!.songIds).toEqual(disney.map((s) => s.id));
    // Ricaricando non si ricreano.
    await page.reload();
    await expect(cards).toHaveCount(2);
    expect(server.callsOf("createPlaylist")).toHaveLength(2);
  });

  test("senza percorso reale spiega come attivare «Report Real Path»", async ({ page, navidrome, login }) => {
    await navidrome({ songs: [{ id: "a", title: "Numb", artist: "Linkin Park", path: "Linkin Park/Meteora/01 - Numb.mp3", duration: 185 }] });
    await login("#/library");
    // Senza raccolte la Libreria parte dalle playlist: la spiegazione sta sotto «Raccolte».
    await page.locator(".main .chip", { hasText: "Raccolte" }).click();
    await expect(page.locator(".main").getByText("Report Real Path")).toBeVisible();
    await expect(page.locator(".main").getByText("baldmusic", { exact: true })).toBeVisible();
    // E nelle Impostazioni, al posto di «Raccolte allineate».
    await page.goto("./#/settings");
    await expect(page.locator(".main").getByText("Report Real Path")).toBeVisible();
    // Anche la Pulizia lo dice, invece di proporre file finti.
    await page.goto("./#/cleanup");
    await expect(page.locator(".main").getByText("Report Real Path")).toBeVisible();
  });

  test("una raccolta di 3200 brani si apre subito e si scorre fino in fondo", async ({ page, navidrome, login }) => {
    const songs = folderSongs("Tutto Disney", 3200, "t");
    await navidrome({ songs: [...songs, fileSong("r", "Rock", "Altro")], playlists: [collection("big", "Tutto Disney", songs)] });
    await login("#/playlist/big");
    await expect(page.getByRole("heading", { level: 1, name: "Tutto Disney" })).toBeVisible();
    await expect(page.locator(".hero__meta")).toContainText("3200 brani");
    await expect(rows(page).first()).toContainText("0001 - Brano 0001");
    // Le righe arrivano a blocchi: alla fine ci sono tutte.
    await expect(rows(page)).toHaveCount(3200, { timeout: 15_000 });
    await page.locator(".main").evaluate((el) => el.scrollTo(0, el.scrollHeight));
    await expect(page.getByText("3200 - Brano 3200")).toBeInViewport();
    await page.locator(".main").evaluate((el) => el.scrollTo(0, el.scrollHeight / 2));
    await expect(page.getByText("1600 - Brano 1600")).toBeVisible();
  });

  test("selezione multipla con Maiuscolo + clic, poi «Togli da questa raccolta»", async ({ page, navidrome, login }) => {
    const songs = folderSongs("Disney Italia", 10, "d");
    const server = await navidrome({ songs: [...songs, fileSong("r", "Rock", "Altro")], playlists: [collection("pd", "Disney Italia", songs)] });
    const dialogs = acceptDialogs(page);
    await login("#/playlist/pd");
    await expect(rows(page)).toHaveCount(10);

    await page.getByTitle("Seleziona più brani").click();
    await rows(page).nth(1).locator(".trow__title").click();
    await rows(page).nth(4).locator(".trow__title").click({ modifiers: ["Shift"] });
    await expect(page.locator(".select-bar__count")).toHaveText("4 brani selezionati");
    for (const i of [0, 1, 4, 5]) await expect(rows(page).nth(i).getByRole("checkbox")).toBeChecked({ checked: i === 1 || i === 4 });
    await expect(page.locator(".main .trow--selected")).toHaveCount(4);

    await page.getByRole("button", { name: "Togli da questa raccolta" }).click();
    await expect(rows(page)).toHaveCount(6);
    expect(dialogs[0]).toContain("Togliere 4 brani");
    expect(server.callsOf("updatePlaylist").at(-1)!.params.getAll("songIndexToRemove")).toEqual(["1", "2", "3", "4"]);
    expect(server.playlist("pd")!.songIds).toEqual(["d-01", "d-06", "d-07", "d-08", "d-09", "d-10"]);
  });

  test("riordino trascinando la maniglia: si salva al rilascio", async ({ page, navidrome, login }) => {
    const songs = folderSongs("Disney Italia", 5, "d");
    const server = await navidrome({ songs: [...songs, fileSong("r", "Rock", "Altro")], playlists: [collection("pd", "Disney Italia", songs)] });
    await login("#/playlist/pd");
    await page.getByTitle("Riordina brani (trascina la maniglia ≡)").click();
    await expect(rows(page).first().locator(".trow__num--handle")).toBeVisible();

    await rows(page).nth(0).dragTo(rows(page).nth(2));
    await expect.poll(() => server.playlist("pd")!.songIds).toEqual(["d-2", "d-3", "d-1", "d-4", "d-5"]);
    const rewrite = server.callsOf("createPlaylist").at(-1)!.params;
    expect(rewrite.get("playlistId")).toBe("pd");
    expect(rewrite.getAll("songId")).toEqual(["d-2", "d-3", "d-1", "d-4", "d-5"]);
    await expect.poll(() => titles(page)).toEqual(["2 - Brano 2", "3 - Brano 3", "1 - Brano 1", "4 - Brano 4", "5 - Brano 5"]);
    // E all'indietro.
    await rows(page).nth(4).dragTo(rows(page).nth(0));
    await expect.poll(() => server.playlist("pd")!.songIds).toEqual(["d-5", "d-2", "d-3", "d-1", "d-4"]);
  });

  test("ordina per titolo: chiede conferma e riscrive la playlist", async ({ page, navidrome, login }) => {
    const songs = ["Zombie", "anima", "Bring Me to Life", "10 Years", "2 Hearts"].map((t, i) => fileSong(`s${i}`, "Rock", t));
    const server = await navidrome({ songs: [...songs, fileSong("x", "Pop", "Altro")], playlists: [{ id: "pl", name: "Mista", songIds: songs.map((s) => s.id) }] });
    const dialogs = acceptDialogs(page);
    await login("#/playlist/pl");
    await expect(rows(page)).toHaveCount(5);
    await page.getByLabel("Ordina i brani").selectOption("title");
    await expect.poll(() => titles(page)).toEqual(["2 Hearts", "10 Years", "anima", "Bring Me to Life", "Zombie"]);
    expect(dialogs[0]).toContain("Ordinare la playlist per titolo?");
    expect(server.playlist("pl")!.songIds).toEqual(["s4", "s3", "s1", "s2", "s0"]);
    expect(server.callsOf("createPlaylist").at(-1)!.params.get("playlistId")).toBe("pl");
  });

  test("trascinare brani sulla barra laterale: playlist e «Brani che ti piacciono»", async ({ page, navidrome, login }) => {
    const songs = folderSongs("Disney Italia", 6, "d");
    const server = await navidrome({
      songs: [...songs, fileSong("r", "Rock", "Altro")],
      playlists: [collection("pd", "Disney Italia", songs), { id: "auto", name: "In auto", songIds: ["d-1"] }],
    });
    await login("#/playlist/pd");
    await expect(rows(page)).toHaveCount(6);
    await page.locator(".side .chip", { hasText: "Playlist" }).click();
    const target = page.locator(".side .lib-row", { hasText: "In auto" });
    await expect(target).toBeVisible();

    await dragWithMouse(page, await center(rows(page).nth(2).locator(".trow__title")), await center(target));
    await expect(target).toContainText("✓ 1 brano aggiunto");
    expect(server.playlist("auto")!.songIds).toEqual(["d-1", "d-3"]);
    const add = server.callsOf("updatePlaylist").at(-1)!.params;
    expect(add.get("playlistId")).toBe("auto");
    expect(add.getAll("songIdToAdd")).toEqual(["d-3"]);

    // Di nuovo lo stesso: c'è già, il server non riceve niente.
    const before = server.callsOf("updatePlaylist").length;
    await dragWithMouse(page, await center(rows(page).nth(2).locator(".trow__title")), await center(target));
    await expect(target).toContainText("C'è già");
    expect(server.callsOf("updatePlaylist")).toHaveLength(before);

    // Con la selezione si portano tutti i selezionati; entrano solo quelli che mancano.
    await page.getByTitle("Seleziona più brani").click();
    for (const i of [0, 3, 4]) await rows(page).nth(i).locator(".trow__title").click();
    await dragWithMouse(page, await center(rows(page).nth(3).locator(".trow__title")), await center(target));
    await expect(target).toContainText("✓ 2 brani aggiunti");
    expect(server.playlist("auto")!.songIds).toEqual(["d-1", "d-3", "d-4", "d-5"]);

    // Su «Brani che ti piacciono»: il cuore.
    const liked = page.locator(".side .lib-row", { hasText: "Brani che ti piacciono" });
    await dragWithMouse(page, await center(rows(page).nth(3).locator(".trow__title")), await center(liked));
    await expect(liked).toContainText("✓ 3 brani aggiunti");
    expect([...server.starred].sort()).toEqual(["d-1", "d-4", "d-5"]);
  });

  test("una playlist di un altro utente si ascolta ma non si modifica", async ({ page, navidrome, login }) => {
    const songs = folderSongs("Rock", 3, "r");
    await navidrome({
      songs: [...songs, fileSong("p", "Pop", "Altro")],
      playlists: [
        { id: "altrui", name: "Di Marco", owner: "marco", songIds: songs.map((s) => s.id) },
        { id: "mia", name: "La mia", songIds: [] },
      ],
    });
    await login("#/playlist/altrui");
    await expect(rows(page)).toHaveCount(3);
    await expect(page.getByTitle("Seleziona più brani")).toBeVisible();
    await expect(page.getByTitle("Riordina brani (trascina la maniglia ≡)")).toHaveCount(0);
    await expect(page.getByLabel("Ordina i brani")).toHaveCount(0);
    await expect(page.getByTitle("Rinomina")).toHaveCount(0);
    await expect(page.getByTitle("Elimina playlist")).toHaveCount(0);

    // Nel menu del brano (tasto destro) non c'è "Rimuovi", e tra le playlist dove aggiungere solo le proprie.
    await rows(page).nth(0).click({ button: "right" });
    const menu = page.locator(".ctx");
    await expect(menu.getByRole("button", { name: "Riproduci dopo" })).toBeVisible();
    await expect(menu.getByRole("button", { name: "Rimuovi da questa playlist" })).toHaveCount(0);
    await menu.getByRole("button", { name: "Aggiungi a playlist" }).click();
    await expect(menu.getByRole("button", { name: "La mia" })).toBeVisible();
    await expect(menu.getByRole("button", { name: "Di Marco" })).toHaveCount(0);
  });

  test("selezionati «Nel Cestino di Windows»: escono dalla raccolta, si scarica lo script, si ricordano", async ({ page, navidrome, login }) => {
    const songs = folderSongs("Disney Italia", 4, "d");
    const server = await navidrome({ songs: [...songs, fileSong("r", "Rock", "Altro")], playlists: [collection("pd", "Disney Italia", songs)] });
    acceptDialogs(page);
    await login("#/playlist/pd");
    await page.getByTitle("Seleziona più brani").click();
    await rows(page).nth(1).locator(".trow__title").click();
    await rows(page).nth(2).locator(".trow__title").click();
    const download = page.waitForEvent("download");
    await page.getByRole("button", { name: "Nel Cestino di Windows" }).click();
    expect((await download).suggestedFilename()).toBe("baldmusic-pulizia.cmd");
    await expect(page.getByRole("heading", { name: "Ultimo passo: sul PC" })).toBeVisible();
    await expect(rows(page)).toHaveCount(2);
    expect(server.playlist("pd")!.songIds).toEqual(["d-1", "d-4"]);
    const saved = await page.evaluate(() => JSON.parse(localStorage.getItem("baldmusic.cestino.http://nd.test|b") ?? "[]") as { path: string }[]);
    expect(saved.map((r) => r.path)).toEqual([songs[1].path, songs[2].path]);
  });

  test("dalla playlist di un altro utente i file vanno nel Cestino senza toccare la sua playlist", async ({ page, navidrome, login }) => {
    const songs = folderSongs("Rock", 3, "r");
    const server = await navidrome({ songs: [...songs, fileSong("p", "Pop", "Altro")], playlists: [{ id: "altrui", name: "Di Marco", owner: "marco", songIds: songs.map((s) => s.id) }] });
    acceptDialogs(page);
    await login("#/playlist/altrui");
    await page.getByTitle("Seleziona più brani").click();
    await rows(page).nth(0).locator(".trow__title").click();
    await expect(page.getByRole("button", { name: /Togli da questa/ })).toHaveCount(0);
    const download = page.waitForEvent("download");
    await page.getByRole("button", { name: "Nel Cestino di Windows" }).click();
    expect((await download).suggestedFilename()).toBe("baldmusic-pulizia.cmd");
    await expect(page.getByRole("heading", { name: "Ultimo passo: sul PC" })).toBeVisible();
    expect(server.callsOf("updatePlaylist").filter((c) => c.params.get("playlistId") === "altrui")).toEqual([]);
    expect(server.playlist("altrui")!.songIds).toEqual(["r-1", "r-2", "r-3"]);
  });

  test("menu del brano: «Informazioni sul brano» mostra i tag e il file", async ({ page, navidrome, login }) => {
    const songs = folderSongs("Disney Italia", 2, "d");
    await navidrome({ songs: [...songs, fileSong("r", "Rock", "Altro")], playlists: [collection("pd", "Disney Italia", songs)] });
    await login("#/playlist/pd");
    await rows(page).nth(1).click({ button: "right" });
    await page.locator(".ctx").getByRole("button", { name: "Informazioni sul brano" }).click();
    const info = page.getByRole("dialog", { name: "Informazioni sul brano" });
    await expect(info).toContainText("2 - Brano 2");
    await expect(info).toContainText("C:\\Users\\B\\Music\\Disney Italia\\2 - Brano 2.mp3");
    await expect(info).toContainText("nessuno: suona com'è");
    await info.getByRole("button", { name: "Chiudi" }).click();
    await expect(info).toHaveCount(0);
  });
});
