// Il colore dominante di una copertina, per sfondi e intestazioni.
// Serve che il server mandi gli header CORS anche per le immagini (Navidrome
// li manda); se il canvas risulta "sporco", si usa un colore calcolato dal nome.

const cache = new Map<string, string>();

/** Le sfumature di mix e categorie: una tavolozza sola, la stessa dell'app Android. */
const TILES: [string, string][] = [
  ["#ff7a59", "#e0245e"],
  ["#7c3aed", "#c026d3"],
  ["#0ea5e9", "#2563eb"],
  ["#10b981", "#0f766e"],
  ["#f59e0b", "#dc2626"],
  ["#6366f1", "#9333ea"],
  ["#14b8a6", "#0369a1"],
  ["#f43f5e", "#9f1239"],
];

export function tileColors(key: string): [string, string] {
  let h = 0;
  for (let i = 0; i < key.length; i++) h = (h * 31 + key.charCodeAt(i)) | 0;
  return TILES[Math.abs(h) % TILES.length];
}

export function tileColor(key: string): string {
  return tileColors(key)[0];
}

export function tileGradient(key: string): string {
  const [a, b] = tileColors(key);
  return `linear-gradient(135deg, ${a}, ${b})`;
}

function toHex(r: number, g: number, b: number) {
  return "#" + [r, g, b].map((v) => Math.round(v).toString(16).padStart(2, "0")).join("");
}

/** Media dei pixel "vivi" (né quasi neri né quasi bianchi), scurita per leggerci sopra il bianco. */
export function dominantFromPixels(data: Uint8ClampedArray): string | null {
  let r = 0, g = 0, b = 0, n = 0;
  for (let i = 0; i < data.length; i += 4) {
    const [pr, pg, pb, pa] = [data[i], data[i + 1], data[i + 2], data[i + 3]];
    if (pa < 128) continue;
    const max = Math.max(pr, pg, pb), min = Math.min(pr, pg, pb);
    if (max < 30 || min > 225) continue;
    // I pixel saturi pesano di più: il colore "di" una copertina non è il suo grigio.
    const w = 1 + (max - min) / 32;
    r += pr * w; g += pg * w; b += pb * w; n += w;
  }
  if (n === 0) return null;
  const k = 0.7;
  return toHex((r / n) * k, (g / n) * k, (b / n) * k);
}

export function dominantColor(url: string | undefined): Promise<string | null> {
  if (!url) return Promise.resolve(null);
  const hit = cache.get(url);
  if (hit) return Promise.resolve(hit);
  return new Promise((resolve) => {
    const img = new Image();
    img.crossOrigin = "anonymous";
    img.onload = () => {
      try {
        const c = document.createElement("canvas");
        c.width = c.height = 24;
        const ctx = c.getContext("2d");
        if (!ctx) return resolve(null);
        ctx.drawImage(img, 0, 0, 24, 24);
        const color = dominantFromPixels(ctx.getImageData(0, 0, 24, 24).data);
        if (color) cache.set(url, color);
        resolve(color);
      } catch {
        resolve(null);
      }
    };
    img.onerror = () => resolve(null);
    img.src = url;
  });
}
