import { cmdScript, psArray } from "./cleanup";

// "Brani nel cestino" sul PC, come TrashScreen sul telefono. Il browser non vede il
// Cestino di Windows: l'app ricorda i file che ci ha mandato (pulizia, raccolte) e
// prepara uno script che li rimette al loro posto. Quando Navidrome li ritrova, ognuno
// torna nella raccolta della sua cartella al posto della copia che lo aveva sostituito.

/** Un file mandato nel Cestino da questo browser. */
export interface TrashRecord {
  path: string;
  title: string;
  artist?: string;
  /** Il doppione tenuto al suo posto: al ripristino la raccolta torna al file buttato. */
  keptId?: string;
  /** Quando è stato preparato lo script, in millisecondi. */
  at: number;
  /** Navidrome non l'ha più trovato: lo script è partito davvero. */
  gone?: boolean;
}

/** Oltre, i più vecchi si dimenticano: il Cestino di Windows prima o poi si svuota. */
export const MAX_RECORDS = 500;

export const RESTORE_SCRIPT_NAME = "baldmusic-ripristina.cmd";

/** Aggiunge i file appena buttati: i più recenti in cima, un file una volta sola. */
export function addRecords(records: TrashRecord[], added: TrashRecord[]): TrashRecord[] {
  // Anche dentro [added]: una playlist può avere lo stesso brano due volte.
  const paths = new Set<string>();
  const fresh = added.filter((r) => !paths.has(r.path) && paths.add(r.path));
  return [...fresh, ...records.filter((r) => !paths.has(r.path))].slice(0, MAX_RECORDS);
}

/**
 * Confronta coi brani che Navidrome vede adesso. Un file sparito è davvero nel Cestino
 * (gone); un file sparito e poi ricomparso è stato ripristinato, dall'app o a mano.
 * Finché lo script non parte il file c'è ancora, e non conta come ripristinato.
 */
export function reconcile(records: TrashRecord[], present: Set<string>): { records: TrashRecord[]; restored: TrashRecord[] } {
  const out: TrashRecord[] = [];
  const restored: TrashRecord[] = [];
  for (const r of records) {
    if (!present.has(r.path)) out.push(r.gone ? r : { ...r, gone: true });
    else if (r.gone) restored.push(r);
    else out.push(r);
  }
  return { records: out, restored };
}

/**
 * I brani della raccolta dopo il ripristino di [restored]: prende il posto della copia
 * tenuta [kept], se è lì per la pulizia dei doppioni (cioè non è un file di questa stessa
 * cartella). null = niente da cambiare: il brano rientra con l'allineamento delle cartelle.
 */
export function putBack(ids: string[], restored: string, kept: string | undefined, folderIds: string[]): string[] | null {
  if (!kept || ids.includes(restored) || !ids.includes(kept) || folderIds.includes(kept)) return null;
  return ids.map((id) => (id === kept ? restored : id));
}

/**
 * Script che rimette i file dal Cestino di Windows dov'erano. Legge direttamente il
 * Cestino dell'utente su ogni disco ($Recycle.Bin\<SID>): per ogni file buttato c'è un
 * $I… col percorso originale e un $R… col contenuto. Come "Ripristina" di Esplora file:
 * sposta il $R… al suo posto e toglie il $I…. Se lo stesso percorso è stato buttato più
 * volte, torna l'ultimo; un file che c'è già non si sovrascrive mai.
 */
export function restoreScript(paths: string[]): string {
  return cmdScript([
    "# BaldMusic - ripristino dal Cestino",
    "# Rimette questi file dal Cestino di Windows dov'erano prima.",
    ...psArray(paths),
    "$sid = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value",
    "function Read-Deleted($info) {",
    "  $b = [IO.File]::ReadAllBytes($info)",
    "  if ($b.Length -lt 28) { return $null }",
    "  $when = [BitConverter]::ToInt64($b, 16)",
    "  if ([BitConverter]::ToInt64($b, 0) -ge 2) {",
    "    $n = [Math]::Min([BitConverter]::ToInt32($b, 24) * 2, $b.Length - 28)",
    "    $p = [Text.Encoding]::Unicode.GetString($b, 28, $n)",
    "  } else {",
    "    $p = [Text.Encoding]::Unicode.GetString($b, 24, [Math]::Min(520, $b.Length - 24))",
    "  }",
    "  @{ Path = $p.Split([char]0)[0]; When = $when }",
    "}",
    "$found = @{}",
    "foreach ($root in ($files | ForEach-Object { [IO.Path]::GetPathRoot($_) } | Sort-Object -Unique)) {",
    "  $bin = Join-Path (Join-Path $root '$Recycle.Bin') $sid",
    "  if (-not (Test-Path -LiteralPath $bin)) { continue }",
    "  foreach ($i in (Get-ChildItem -LiteralPath $bin -Force -File | Where-Object { $_.Name.StartsWith('$I') })) {",
    "    try { $d = Read-Deleted $i.FullName } catch { continue }",
    "    if (-not $d) { continue }",
    "    $k = $d.Path.ToLowerInvariant()",
    "    $old = $found[$k]",
    "    if ($old -and $old.When -ge $d.When) { continue }",
    "    $found[$k] = @{ Info = $i.FullName; Data = (Join-Path $bin ('$R' + $i.Name.Substring(2))); When = $d.When }",
    "  }",
    "}",
    "$ok = 0",
    "foreach ($f in $files) {",
    "  $hit = $found[$f.ToLowerInvariant()]",
    "  if (Test-Path -LiteralPath $f) {",
    '    Write-Host "Già al suo posto: $f"',
    "  } elseif (-not $hit -or -not (Test-Path -LiteralPath $hit.Data)) {",
    '    Write-Host "Non è nel Cestino (svuotato?): $f"',
    "  } else {",
    "    try {",
    "      [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($f)) | Out-Null",
    "      [IO.File]::Move($hit.Data, $f)",
    "      [IO.File]::Delete($hit.Info)",
    "      $ok++",
    "    } catch {",
    '      Write-Host "Non riesco a rimettere $f : $($_.Exception.Message)"',
    "    }",
    "  }",
    "}",
    'Write-Host "$ok file rimessi al loro posto."',
  ]);
}
