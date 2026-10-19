import { defineConfig, devices } from "@playwright/test";

// Prove nel browser: il sito costruito come su GitHub Pages, servito da `vite preview`,
// con un Navidrome finto (e2e/fixtures.ts). In CI il browser lo installa
// `npx playwright install --with-deps chromium`; dove Chromium c'è già in un'altra
// versione, PW_CHROMIUM dice dove sta (es. /opt/pw-browsers/chromium).
const executablePath = process.env.PW_CHROMIUM || undefined;

export default defineConfig({
  testDir: "./e2e",
  testMatch: /.*\.e2e\.ts$/,
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? [["list"], ["html", { open: "never" }]] : "list",
  use: {
    baseURL: "http://localhost:4173/",
    trace: "retain-on-failure",
    acceptDownloads: true,
    launchOptions: { executablePath },
  },
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"], viewport: { width: 1400, height: 900 } } }],
  webServer: {
    command: "npx vite build && npx vite preview --port 4173 --strictPort",
    url: "http://localhost:4173/",
    reuseExistingServer: !process.env.CI,
    timeout: 120_000,
  },
});
