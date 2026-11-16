import { folderSongs } from "../src/test/fakeNavidrome";
import { expect, test } from "./fixtures";

// Ogni pagina col titolo grande lascia posto ai tasti ← → in alto: prima le Impostazioni non avevano
// il margine delle altre pagine e il titolo finiva sotto i tasti, attaccato al bordo.
const pages = ["#/", "#/settings", "#/library", "#/search", "#/stats", "#/cleanup", "#/trash", "#/queue"];

for (const scheme of ["light", "dark"] as const) {
  test(`titoli delle pagine sotto i tasti in alto (${scheme === "light" ? "pop" : "scuro"})`, async ({ page, navidrome, login }) => {
    await navidrome({ songs: [...folderSongs("Disney Italia", 3, "d"), ...folderSongs("Metal", 2, "m")] });
    await page.emulateMedia({ colorScheme: scheme });
    await login();
    for (const hash of pages) {
      await page.goto("./" + hash);
      const title = page.locator(".main h1, .main .search-bar").first();
      await expect(title).toBeVisible();
      const buttons = await page.locator(".topbar .round-btn").first().boundingBox();
      const box = (await title.boundingBox())!;
      const main = (await page.locator(".main").boundingBox())!;
      expect(box.y, `titolo di ${hash} sotto i tasti`).toBeGreaterThanOrEqual(buttons!.y + buttons!.height);
      expect(box.x - main.x, `titolo di ${hash} staccato dal bordo`).toBeGreaterThanOrEqual(16);
    }
  });
}
