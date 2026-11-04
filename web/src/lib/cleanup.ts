import type { Song } from "./subsonic";

// Pulizia della libreria sul PC, con le stesse regole del telefono (LibraryCleanup):
// anteprime di 30 secondi scaricate al posto della canzone e lo stesso brano in più
// cartelle. Il browser non può toccare i file: l'app prepara uno script che li sposta
// nel Cestino di Windows (si ripristinano da lì), mai una cancellazione definitiva.

/** Lo stesso brano in più file: [keep] resta, [others] vanno nel cestino. */
export interface DuplicateGroup {
  keep: Song;
  others: Song[];
}

/** Durata di un'anteprima: i servizi di streaming le fanno da 30 secondi. */
const PREVIEW = { min: 25, max: 35 };

/** Due copie dello stesso brano durano uguale, a meno di qualche secondo di silenzio. */
const SAME_LENGTH = 3;

const isPreview = (s: Song) => s.duration !== undefined && s.duration >= PREVIEW.min && s.duration <= PREVIEW.max;

const collator = new Intl.Collator("it", { numeric: true, sensitivity: "base" });

/** Confronto senza maiuscole, accenti e punteggiatura (come ArtistNames.key sul telefono). */
export function nameKey(s: string): string {
  return s
    .normalize("NFD")
    .replace(/\p{M}/gu, "")
    .toLowerCase()
    .replace(/[^\p{L}\p{N}]+/gu, " ")
    .trim();
}

/** La cartella del file (solo il nome), per dire dove sta. */
export function folderName(path: string | undefined): string {
  const parts = (path ?? "").split(/[\\/]+/).filter(Boolean);
  return parts.length > 1 ? parts[parts.length - 2] : "";
}

/** Brani che durano quanto un'anteprima: quasi sempre non sono la canzone intera. */
export function previews(songs: Song[]): Song[] {
  return songs.filter(isPreview).sort((a, b) => collator.compare(folderName(a.path), folderName(b.path)) || collator.compare(a.title, b.title));
}

/**
 * Quale copia tenere: quella coi tag più completi (riconosciuta da MusicBrainz, con
 * l'album, con un anno vero), poi quella arrivata per prima.
 */
const LOSSLESS = new Set(["flac", "wav", "aiff", "aif", "ape", "wv", "alac", "dsf"]);

/**
 * Quanto suona bene una copia, come `LibraryCleanup.quality` sul telefono: formato senza perdita,
 * poi i kbps che manda Navidrome. [rank] sono le fasce che si sentono (senza perdita, 320, 256,
 * 192, 128 e sotto); dentro la stessa fascia decidono i tag.
 */
export function songQuality(s: Song): { label: string; rank: number } {
  const format = (s.suffix ?? s.path?.split(".").pop() ?? "").toLowerCase() || undefined;
  const lossless = !!format && LOSSLESS.has(format);
  const kbps = s.bitRate ?? 0;
  const rank = lossless ? 5 : kbps >= 300 ? 4 : kbps >= 224 ? 3 : kbps >= 160 ? 2 : kbps >= 112 ? 1 : 0;
  const label = [format?.toUpperCase(), lossless ? "senza perdita" : kbps > 0 ? `${kbps} kbps` : undefined].filter(Boolean).join(" · ");
  return { label, rank };
}

/** Scelta a mano nella Pulizia: resta la copia [id], le altre si possono buttare. */
export function keeping(g: DuplicateGroup, id: string): DuplicateGroup {
  const all = [g.keep, ...g.others];
  const chosen = all.find((s) => s.id === id);
  if (!chosen) return g;
  return { keep: chosen, others: all.filter((s) => s.id !== id).sort((a, b) => collator.compare(folderName(a.path), folderName(b.path))) };
}

/** Prima la copia che suona meglio, poi quella coi tag più completi (prima contavano solo i tag). */
function betterCopy(a: Song, b: Song): number {
  const q = songQuality(b).rank - songQuality(a).rank;
  if (q !== 0) return q;
  const score = (s: Song) => [s.musicBrainzId ? 1 : 0, s.album ? 1 : 0, s.year && s.year !== 1970 ? 1 : 0];
  const [sa, sb] = [score(a), score(b)];
  for (let i = 0; i < sa.length; i++) if (sa[i] !== sb[i]) return sb[i] - sa[i];
  return (a.created ?? "").localeCompare(b.created ?? "") || a.id.localeCompare(b.id);
}

export type SongVersion = "clean" | "explicit" | "unknown";

const MARK = /(?:[([]|[-–—]\s*)([^)\]\-–—([]+)/g;
const EXPLICIT = /\b(explicit|dirty|uncensored|non censurat[ao])\b/;
const CLEAN = /\b(clean|censored|censurat[ao]|radio edit|radio version|radio mix|edited|edit version|clean version)\b/;

/**
 * La versione scritta tra parentesi o dopo un trattino in titolo, album o nome del file
 * («Break Stuff (Clean)», «Significant Other [Explicit]», «… - Radio Edit.mp3»), come
 * `LibraryCleanup.version` sul telefono. Due copie con versioni diverse non sono doppioni.
 */
export function songVersion(texts: (string | undefined)[]): SongVersion {
  const text = texts.filter(Boolean).join(" ").toLowerCase();
  const marks = [...text.matchAll(MARK)].map((m) => m[1].trim());
  if (marks.some((m) => EXPLICIT.test(m))) return "explicit";
  if (marks.some((m) => CLEAN.test(m))) return "clean";
  return "unknown";
}

const versionOf = (s: Song) => songVersion([s.title, s.album, s.path?.split(/[\\/]/).pop()]);

/**
 * Lo stesso brano in più file: stesso titolo e stesso artista (senza badare a maiuscole
 * e accenti), la stessa durata entro pochi secondi e la stessa versione (la censurata e
 * l'esplicita restano tutte e due). Le anteprime non contano.
 */
export function duplicates(songs: Song[]): DuplicateGroup[] {
  const byName = new Map<string, Song[]>();
  for (const s of songs) {
    if (isPreview(s) || !s.duration) continue;
    const k = `${nameKey(s.artist ?? "")}|${nameKey(s.title)}|${versionOf(s)}`;
    byName.set(k, [...(byName.get(k) ?? []), s]);
  }
  const groups: DuplicateGroup[] = [];
  for (const same of byName.values()) {
    // Una versione live o estesa non è un doppione: si divide per durata.
    const sorted = [...same].sort((a, b) => a.duration! - b.duration!);
    let run: Song[] = [];
    const flush = () => {
      if (run.length > 1) {
        const [keep, ...others] = [...run].sort(betterCopy);
        groups.push({ keep, others: others.sort((a, b) => collator.compare(folderName(a.path), folderName(b.path))) });
      }
    };
    for (const s of sorted) {
      if (run.length > 0 && s.duration! - run[0].duration! > SAME_LENGTH) {
        flush();
        run = [];
      }
      run.push(s);
    }
    flush();
  }
  return groups.sort((a, b) => collator.compare(a.keep.artist ?? "", b.keep.artist ?? "") || collator.compare(a.keep.title, b.keep.title));
}

/**
 * I brani di una playlist dopo la pulizia: ogni copia buttata diventa quella tenuta,
 * nella stessa posizione; se la tenuta c'è già, la copia sparisce e basta.
 */
export function replaceCopies(ids: string[], keptFor: Map<string, string>): string[] {
  const out: string[] = [];
  const seen = new Set<string>();
  for (const id of ids) {
    const kept = keptFor.get(id);
    if (kept === undefined) {
      out.push(id);
      seen.add(id);
    } else if (!seen.has(kept) && !ids.includes(kept)) {
      out.push(kept);
      seen.add(kept);
    }
  }
  return out;
}

/** Percorso di Windows (C:\…): lo script per il Cestino è per Windows. */
export const isWindowsPath = (path: string) => /^[A-Za-z]:\\/.test(path);

/** Il nome del file da scaricare: un .cmd parte col doppio clic, un .ps1 no. */
export const TRASH_SCRIPT_NAME = "baldmusic-pulizia.cmd";

/** Un percorso tra apici per PowerShell: l'apice dentro si raddoppia. */
export const psQuote = (p: string) => `'${p.replace(/'/g, "''")}'`;

/** L'elenco dei file come array di PowerShell. */
export const psArray = (paths: string[]) => ["$files = @(", paths.map((p) => `  ${psQuote(p)}`).join(",\r\n"), ")"];

/**
 * Uno script per Windows che parte col doppio clic: è un .cmd che contiene PowerShell.
 * La parte per il prompt dei comandi lancia PowerShell su questo stesso file (letto come
 * UTF-8, così i percorsi con le lettere accentate restano giusti) e la parte per
 * PowerShell, dentro un commento per il prompt, fa il lavoro.
 */
export function cmdScript(lines: string[]): string {
  return [
    "<# : prompt dei comandi",
    "@echo off",
    `powershell -NoProfile -ExecutionPolicy Bypass -Command "Invoke-Expression ([IO.File]::ReadAllText('%~f0', [Text.Encoding]::UTF8))"`,
    "if errorlevel 1 pause",
    "goto :EOF",
    ": da qui PowerShell #>",
    ...lines,
    'Read-Host "Premi Invio per chiudere"',
    "",
  ].join("\r\n");
}

/** Script che sposta i file nel Cestino di Windows (si ripristinano da lì o dall'app). */
export function trashScript(paths: string[]): string {
  return cmdScript([
    "# BaldMusic - pulizia della libreria",
    "# Sposta questi file nel Cestino di Windows: se serve, si ripristinano da li'.",
    "Add-Type -AssemblyName Microsoft.VisualBasic",
    ...psArray(paths),
    "$ok = 0",
    "foreach ($f in $files) {",
    "  if (Test-Path -LiteralPath $f) {",
    "    [Microsoft.VisualBasic.FileIO.FileSystem]::DeleteFile($f, 'OnlyErrorDialogs', 'SendToRecycleBin')",
    "    $ok++",
    "  } else {",
    '    Write-Host "Non trovato: $f"',
    "  }",
    "}",
    'Write-Host "$ok file spostati nel Cestino."',
  ]);
}
