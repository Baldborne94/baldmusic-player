import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// base relativa: lo stesso build funziona su GitHub Pages
// (/baldmusic-player/), su un dominio proprio o aperto da una cartella.
// Le rotte sono hash (#/album/...) quindi non serve alcun rewrite lato server.
export default defineConfig({
  base: "./",
  plugins: [react()],
});
