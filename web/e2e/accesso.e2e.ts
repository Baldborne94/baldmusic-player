import { folderSongs } from "../src/test/fakeNavidrome";
import { expect, test } from "./fixtures";

const library = () => [...folderSongs("Disney Italia", 4, "d"), ...folderSongs("Movie Soundtracks", 2, "m")];

test("accesso dal modulo: entra, salva solo sale e token, apre la Home", async ({ page, navidrome }) => {
  const server = await navidrome({ songs: library() });
  await page.goto("./");
  await page.getByLabel("Indirizzo del server").fill("http://nd.test");
  await page.getByLabel("Utente").fill("b");
  await page.getByLabel("Password").fill("password-segreta");
  await page.getByRole("button", { name: "Entra" }).click();

  await expect(page.getByRole("heading", { level: 1, name: /^(Buongiorno|Buon pomeriggio|Buonasera)$/ })).toBeVisible();
  const saved = await page.evaluate(() => localStorage.getItem("baldmusic.credentials"));
  expect(Object.keys(JSON.parse(saved!)).sort()).toEqual(["salt", "token", "url", "user"]);
  expect(saved).not.toContain("password-segreta");
  // Le richieste portano token e sale, mai la password.
  const ping = server.callsOf("ping")[0].params;
  expect(ping.get("p")).toBeNull();
  expect(ping.get("t")).toMatch(/^[0-9a-f]{32}$/);
  expect(server.calls.every((c) => !c.params.toString().includes("password-segreta"))).toBe(true);
  // Le raccolte nascono dalle cartelle e arrivano in Home.
  await expect(page.getByRole("heading", { name: "Le tue raccolte" })).toBeVisible();
});

test("accesso rifiutato: il messaggio del server resta sul modulo", async ({ page, navidrome }) => {
  const server = await navidrome();
  server.failing.add("ping");
  await page.goto("./");
  await page.getByLabel("Indirizzo del server").fill("http://nd.test");
  await page.getByLabel("Utente").fill("b");
  await page.getByLabel("Password").fill("sbagliata");
  await page.getByRole("button", { name: "Entra" }).click();
  await expect(page.getByText("Errore finto su ping")).toBeVisible();
  expect(await page.evaluate(() => localStorage.getItem("baldmusic.credentials"))).toBeNull();
});

test("Home dopo l'accesso: saluto, raccolte e mix", async ({ page, navidrome, login }) => {
  await navidrome({ songs: library() });
  await login();
  await expect(page.getByRole("heading", { name: "Le tue raccolte" })).toBeVisible();
  await expect(page.getByRole("heading", { name: "I tuoi mix" })).toBeVisible();
  await expect(page.locator(".main .card__title", { hasText: "Disney Italia" })).toBeVisible();
});

test("Impostazioni: server, volume uguale, dissolvenza, continua ad ascoltare, raccolte, uscita", async ({ page, navidrome, login }) => {
  await navidrome({ songs: library() });
  await login("#/settings");
  await expect(page.getByRole("heading", { name: "Impostazioni" })).toBeVisible();
  await expect(page.locator(".settings code")).toHaveText("http://nd.test");

  await page.getByRole("button", { name: "Verifica connessione" }).click();
  await expect(page.getByText("Server raggiungibile ✓")).toBeVisible();
  await expect(page.getByText("Raccolte allineate alle cartelle ✓")).toBeVisible();

  const brano = page.getByRole("button", { name: "Brano", exact: true });
  await brano.click();
  await expect(brano).toHaveClass(/is-on/);
  const spenta = page.getByRole("button", { name: "Spenta", exact: true });
  await spenta.click();
  await expect(spenta).toHaveClass(/is-on/);
  // Continua ad ascoltare: attivo di serie, come sul telefono.
  const continua = page.locator(".panel", { has: page.getByRole("heading", { name: "Continua ad ascoltare" }) });
  await expect(continua.getByRole("button", { name: "Attivo" })).toHaveClass(/is-on/);
  await continua.getByRole("button", { name: "Spento" }).click();
  await expect(continua.getByRole("button", { name: "Spento" })).toHaveClass(/is-on/);
  expect(await page.evaluate(() => [localStorage.getItem("baldmusic.replaygain"), localStorage.getItem("baldmusic.crossfade"), localStorage.getItem("baldmusic.continua")])).toEqual(["track", "0", "off"]);
  // Si ricordano alla prossima apertura.
  await page.reload();
  await expect(page.getByRole("button", { name: "Brano", exact: true })).toHaveClass(/is-on/);
  await expect(page.getByRole("button", { name: "Spenta", exact: true })).toHaveClass(/is-on/);
  await expect(continua.getByRole("button", { name: "Spento" })).toHaveClass(/is-on/);

  await page.getByRole("button", { name: "Esci" }).click();
  await expect(page.getByRole("button", { name: "Entra" })).toBeVisible();
  expect(await page.evaluate(() => localStorage.getItem("baldmusic.credentials"))).toBeNull();
});

test("Cerca: risultati dal server e ricerca ricordata", async ({ page, navidrome, login }) => {
  await navidrome({ songs: library() });
  await login("#/search");
  await page.getByRole("textbox", { name: "Cerca", exact: true }).fill("Brano 3");
  await expect(page.locator(".main .trow__title", { hasText: "3 - Brano 3" })).toBeVisible();
  await expect.poll(() => page.evaluate(() => localStorage.getItem("baldmusic.searches")), { timeout: 5000 }).toBe('["Brano 3"]');
});
