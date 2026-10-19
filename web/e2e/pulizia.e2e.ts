import { readFile } from "node:fs/promises";
import { collectionComment } from "../src/lib/collections";
import type { TrashRecord } from "../src/lib/trash";
import { fileSong } from "../src/test/fakeNavidrome";
import { acceptDialogs, expect, test } from "./fixtures";

const TRASH_KEY = "baldmusic.cestino.http://nd.test|b";

// «Numb» due volte: la copia povera in Disney Italia, quella coi tag buoni in Rock.
const copy = fileSong("copia", "Disney Italia", "Numb", { artist: "Linkin Park", duration: 185 });
const kept = fileSong("tenuto", "Rock", "Numb", { artist: "Linkin Park", duration: 186, album: "Meteora", musicBrainzId: "mb-1" });
const preview = fileSong("anteprima", "Disney Italia", "Hakuna Matata", { duration: 30 });
const other = fileSong("altro", "Disney Italia", "Il cerchio della vita");

test("Pulizia: doppioni e anteprime, le raccolte passano alla copia tenuta, si scarica lo script", async ({ page, navidrome, login }) => {
  const server = await navidrome({
    songs: [copy, kept, preview, other],
    playlists: [{ id: "pd", name: "Disney Italia", comment: collectionComment("Disney Italia"), songIds: ["altro", "copia", "anteprima"] }],
    starred: ["copia"],
  });
  const dialogs = acceptDialogs(page);
  await login("#/cleanup");

  await expect(page.getByRole("heading", { name: "Doppioni" })).toBeVisible();
  await expect(page.getByRole("heading", { name: "Brani di 30 secondi" })).toBeVisible();
  const dup = page.locator(".clean-row", { hasText: "Resta in: Rock" });
  await expect(dup).toContainText("Da buttare: Disney Italia");
  await expect(page.locator(".clean-row", { hasText: "Hakuna Matata" })).toContainText("0:30");

  const download = page.waitForEvent("download");
  await page.getByRole("button", { name: "Prepara la pulizia (2 file)" }).click();
  const file = await download;
  expect(file.suggestedFilename()).toBe("baldmusic-pulizia.cmd");
  const script = await readFile(await file.path(), "utf8");
  expect(script.startsWith("<# :")).toBe(true);
  expect(script).toContain(`'${copy.path}'`);
  expect(script).toContain(`'${preview.path}'`);
  expect(script).not.toContain(`'${kept.path}'`);
  expect(script).toContain("SendToRecycleBin");
  expect(dialogs[0]).toContain("2 file nel Cestino di Windows");
  await expect(page.getByRole("heading", { name: "Ultimo passo: sul PC" })).toBeVisible();

  // La raccolta ha la copia tenuta allo stesso posto, il cuore è passato.
  expect(server.playlist("pd")!.songIds).toEqual(["altro", "tenuto", "anteprima"]);
  expect(server.starred.has("tenuto")).toBe(true);
  // E «Brani nel cestino» se li ricorda, col doppione tenuto.
  const saved = JSON.parse((await page.evaluate((k) => localStorage.getItem(k), TRASH_KEY))!) as TrashRecord[];
  expect(saved.map((r) => [r.path, r.keptId])).toEqual([
    [copy.path, "tenuto"],
    [preview.path, undefined],
  ]);
});

test("Pulizia: deselezionare un'anteprima la toglie dallo script", async ({ page, navidrome, login }) => {
  const second = fileSong("anteprima2", "Rock", "Intro", { duration: 28 });
  await navidrome({ songs: [preview, second, other, kept] });
  acceptDialogs(page);
  await login("#/cleanup");
  await page.locator(".clean-row", { hasText: "Hakuna Matata" }).click();
  await expect(page.locator(".clean-row--all")).toContainText("1 di 2 selezionati");
  const download = page.waitForEvent("download");
  await page.getByRole("button", { name: "Prepara la pulizia (1 file)" }).click();
  const script = await readFile(await (await download).path(), "utf8");
  expect(script).toContain(`'${second.path}'`);
  expect(script).not.toContain(`'${preview.path}'`);
});

test("Brani nel cestino: si scarica lo script di ripristino e il file torna nella sua raccolta", async ({ page, navidrome, login }) => {
  const records: TrashRecord[] = [
    { path: copy.path!, title: copy.title, artist: copy.artist, keptId: "tenuto", at: Date.UTC(2026, 9, 1) },
    { path: other.path!, title: other.title, at: Date.UTC(2026, 9, 1) },
  ];
  const server = await navidrome({
    // La copia è già nel Cestino di Windows: Navidrome non la vede. «Il cerchio della vita» invece c'è ancora.
    songs: [kept, other, preview],
    playlists: [{ id: "pd", name: "Disney Italia", comment: collectionComment("Disney Italia"), songIds: ["altro", "tenuto", "anteprima"] }],
  });
  const dialogs = acceptDialogs(page);
  await login("#/trash", { [TRASH_KEY]: JSON.stringify(records) });

  await expect(page.getByRole("heading", { name: "Nel Cestino di Windows" })).toBeVisible();
  await expect(page.locator(".clean-row", { hasText: "Numb" })).toContainText("Disney Italia");
  await expect(page.getByRole("heading", { name: "Ancora nella libreria" })).toBeVisible();

  const download = page.waitForEvent("download");
  await page.getByRole("button", { name: "Prepara il ripristino (1 file)" }).click();
  const file = await download;
  expect(file.suggestedFilename()).toBe("baldmusic-ripristina.cmd");
  const script = await readFile(await file.path(), "utf8");
  expect(script).toContain(`'${copy.path}'`);
  expect(script).not.toContain(`'${other.path}'`);
  expect(script).toContain("$Recycle.Bin");
  expect(dialogs[0]).toContain("rimette 1 file");

  // Lo script ha rimesso il file: Navidrome lo ritrova e la raccolta torna com'era.
  server.songs.push(copy);
  await page.getByRole("button", { name: "Ho fatto: aggiorna" }).click();
  await expect(page.getByRole("heading", { name: "Nel Cestino di Windows" })).toHaveCount(0);
  expect(server.playlist("pd")!.songIds).toEqual(["altro", "copia", "anteprima"]);
  const left = JSON.parse((await page.evaluate((k) => localStorage.getItem(k), TRASH_KEY))!) as TrashRecord[];
  expect(left.map((r) => r.path)).toEqual([other.path]);
});

test("Brani nel cestino vuoto: spiega come ripristinare a mano", async ({ page, navidrome, login }) => {
  await navidrome({ songs: [other, kept] });
  await login("#/trash");
  await expect(page.getByText("Il cestino è vuoto.")).toBeVisible();
});
