/** 215 → "3:35", 3725 → "1:02:05". */
export function formatDuration(seconds: number | undefined): string {
  if (seconds === undefined || !Number.isFinite(seconds) || seconds < 0) return "–:––";
  const s = Math.floor(seconds);
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  const sec = String(s % 60).padStart(2, "0");
  return h > 0 ? `${h}:${String(m).padStart(2, "0")}:${sec}` : `${m}:${sec}`;
}

/** Durata totale leggibile: "42 min", "1 h 12 min". */
export function formatTotal(seconds: number | undefined): string {
  if (!seconds) return "";
  const min = Math.round(seconds / 60);
  if (min < 60) return `${min} min`;
  return `${Math.floor(min / 60)} h ${min % 60} min`;
}

export function plural(n: number, one: string, many: string): string {
  return `${n} ${n === 1 ? one : many}`;
}
