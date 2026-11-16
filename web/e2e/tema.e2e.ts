import { folderSongs } from "../src/test/fakeNavidrome";
import { expect, test } from "./fixtures";

const library = () => folderSongs("Disney Italia", 3, "d");

test("tema automatico: pop col computer in chiaro, scuro col computer in scuro", async ({ page, navidrome, login }) => {
  await navidrome({ songs: library() });
  await page.emulateMedia({ colorScheme: "light" });
  await login();
  const html = page.locator("html");
  await expect(html).toHaveAttribute("data-theme", "pop");
  await expect(page.locator('meta[name="theme-color"]')).toHaveAttribute("content", "#ffd23f");
  // Il computer passa al tema scuro: il sito lo segue senza ricaricare.
  await page.emulateMedia({ colorScheme: "dark" });
  await expect(html).toHaveAttribute("data-theme", "dark");
  await expect(page.locator('meta[name="theme-color"]')).toHaveAttribute("content", "#0e0f13");
});

test("tema scelto nelle Impostazioni: resta dopo aver ricaricato", async ({ page, navidrome, login }) => {
  await navidrome({ songs: library() });
  await page.emulateMedia({ colorScheme: "light" });
  await login("#/settings");
  const scuro = page.getByRole("button", { name: "Scuro", exact: true });
  await scuro.click();
  await expect(scuro).toHaveClass(/is-on/);
  await expect(page.locator("html")).toHaveAttribute("data-theme", "dark");
  await page.reload();
  await expect(page.locator("html")).toHaveAttribute("data-theme", "dark");

  await page.getByRole("button", { name: "Pop", exact: true }).click();
  await page.emulateMedia({ colorScheme: "dark" });
  await expect(page.locator("html")).toHaveAttribute("data-theme", "pop");
});
