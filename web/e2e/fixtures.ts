import { test as base, expect, type Locator, type Page } from "@playwright/test";
import { FakeNavidrome, type FakeInit } from "../src/test/fakeNavidrome";

// Ogni prova ha il suo Navidrome finto (lo stesso dei test unitari) dietro
// http://nd.test, e alla fine controlla che la pagina non abbia avuto errori.

export const SERVER = "http://nd.test";
export const CREDENTIALS = { url: SERVER, user: "b", salt: "s", token: "t" };

const CORS = { "access-control-allow-origin": "*" };
// Una copertina di 1×1 pixel: le immagini si caricano davvero, senza 404 in console.
const PIXEL = Buffer.from("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==", "base64");

interface Fixtures {
  /** Avvia il Navidrome finto per questa pagina. */
  navidrome: (init?: FakeInit) => Promise<FakeNavidrome>;
  /** Entra come se l'accesso fosse già fatto: credenziali in localStorage e ricarica. */
  login: (hash?: string, storage?: Record<string, string>) => Promise<void>;
  /** Errori della pagina raccolti durante la prova: alla fine devono essere zero. */
  pageErrors: string[];
}

export const test = base.extend<Fixtures>({
  pageErrors: [
    async ({ page }, use) => {
      const errors: string[] = [];
      page.on("pageerror", (e) => errors.push(`pageerror: ${e.message}`));
      page.on("console", (m) => m.type() === "error" && errors.push(`console: ${m.text()}`));
      await use(errors);
      expect(errors, "errori nella pagina").toEqual([]);
    },
    { auto: true },
  ],
  navidrome: async ({ page }, use) => {
    await use(async (init) => {
      const server = new FakeNavidrome(init);
      await page.route(`${SERVER}/**`, async (route) => {
        const url = route.request().url();
        if (url.includes("/rest/getCoverArt")) return route.fulfill({ status: 200, contentType: "image/png", headers: CORS, body: PIXEL });
        if (!url.includes("/rest/")) return route.fulfill({ status: 404, headers: CORS, body: "" });
        await route.fulfill({ status: 200, contentType: "application/json", headers: CORS, body: JSON.stringify(server.handle(url)) });
      });
      return server;
    });
  },
  login: async ({ page }, use) => {
    await use(async (hash = "#/", storage = {}) => {
      await page.goto("./");
      await page.evaluate(
        ([c, extra]) => {
          localStorage.setItem("baldmusic.credentials", JSON.stringify(c));
          for (const [k, v] of Object.entries(extra)) localStorage.setItem(k, v);
        },
        [CREDENTIALS, storage] as const,
      );
      await page.goto(`./${hash}`);
      await page.reload();
    });
  },
});

export { expect };

/** Accetta le finestre di conferma (confirm/alert) e ne ricorda il testo. */
export function acceptDialogs(page: Page): string[] {
  const seen: string[] = [];
  page.on("dialog", (d) => {
    seen.push(d.message());
    void d.accept();
  });
  return seen;
}

/** Le righe dei brani della pagina aperta. */
export const rows = (page: Page) => page.locator(".main .trow");

/** Trascina come col mouse vero: giù, qualche passo, su. */
export async function dragWithMouse(page: Page, from: { x: number; y: number }, to: { x: number; y: number }) {
  await page.mouse.move(from.x, from.y);
  await page.mouse.down();
  for (let i = 1; i <= 8; i++) await page.mouse.move(from.x + ((to.x - from.x) * i) / 8, from.y + ((to.y - from.y) * i) / 8);
  await page.mouse.up();
}

/** Il centro di un elemento. */
export async function center(selector: Locator) {
  const box = await selector.boundingBox();
  if (!box) throw new Error("Elemento non visibile");
  return { x: box.x + box.width / 2, y: box.y + box.height / 2 };
}
