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
function betterCopy(a: Song, b: Song): number {
  const score = (s: Song) => [s.musicBrainzId ? 1 : 0, s.album ? 1 : 0, s.year && s.year !== 1970 ? 1 : 0];
  const [sa, sb] = [score(a), score(b)];
  for (let i = 0; i < sa.length; i++) if (sa[i] !== sb[i]) return sb[i] - sa[i];
  return (a.created ?? "").localeCompare(b.created ?? "") || a.id.localeCompare(b.id);
}

/**
 * Lo stesso brano in più file: stesso titolo e stesso artista (senza badare a maiuscole
 * e accenti) e la stessa durata, entro pochi secondi. Le anteprime non contano.
 */
export function duplicates(songs: Song[]): DuplicateGroup[] {
  const byName = new Map<string, Song[]>();
  for (const s of songs) {
    if (isPreview(s) || !s.duration) continue;
    const k = `${nameKey(s.artist ?? "")}|${nameKey(s.title)}`;
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

/**
 * Script PowerShell che sposta i file nel Cestino di Windows. Con il BOM UTF-8, così
 * PowerShell 5 legge giusti anche i percorsi con lettere accentate.
 */
export function trashScript(paths: string[]): string {
  const quoted = paths.map((p) => `  '${p.replace(/'/g, "''")}'`).join(",\r\n");
  return (
    "\uFEFF" +
    [
      "# BaldMusic - pulizia della libreria",
      "# Sposta questi file nel Cestino di Windows: se serve, si ripristinano da li'.",
      "Add-Type -AssemblyName Microsoft.VisualBasic",
      "$files = @(",
      quoted,
      ")",
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
      'Read-Host "Premi Invio per chiudere"',
      "",
    ].join("\r\n")
  );
}
