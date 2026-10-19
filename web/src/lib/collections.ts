// Raccolte = cartelle, come sul telefono: ogni prima cartella dentro quella della musica
// ("Music/Disney Italia") diventa una playlist di Navidrome, riconosciuta dal commento.
// Per il resto è una playlist: si rinomina, si aggiunge, si toglie, si elimina.
// I brani nuovi della cartella entrano da soli; quelli tolti a mano non tornano; una
// raccolta eliminata non si ricrea. Non si toccano mai i file.

/** Brano con il percorso del file come lo manda Navidrome. */
export interface FolderSong {
  id: string;
  path?: string;
}

/** Cosa ricorda questo browser: i brani di ogni cartella all'ultimo allineamento. */
export interface CollectionsState {
  known: Record<string, string[]>;
}

export interface SyncPlan {
  /** Raccolte da creare, con i brani in ordine di percorso. */
  create: { folder: string; songIds: string[] }[];
  /** Brani arrivati nella cartella dall'ultima volta (chi li ha già in playlist li salta). */
  add: { playlistId: string; songIds: string[] }[];
  /** Cartelle senza raccolta che non si creano da sole: eliminate, o viste per la prima volta da un altro browser. */
  missing: string[];
  state: CollectionsState;
}

const MARK = /^Raccolta della cartella «(.+)»/;

export function collectionComment(folder: string): string {
  return `Raccolta della cartella «${folder}» · BaldMusic`;
}

/** La cartella di una raccolta, o null se è una playlist normale. */
export function folderOf(playlist: { comment?: string }): string | null {
  return MARK.exec(playlist.comment ?? "")?.[1] ?? null;
}

/**
 * Percorso vero del file (C:\…, /…) o quello finto "Artista/Album/brano" che Navidrome
 * manda se nel lettore non è attivo "Mostra percorso reale".
 */
export function isRealPath(path: string): boolean {
  return /^([A-Za-z]:[\\/]|[\\/])/.test(path);
}

const collator = new Intl.Collator("it", { numeric: true, sensitivity: "base" });

/**
 * Le cartelle-raccolta: la prima cartella sotto quella che contiene tutta la musica,
 * con i brani in ordine di percorso (sottocartelle comprese). I file sciolti nella
 * cartella principale non sono di nessuna raccolta.
 */
export function collectionFolders(songs: FolderSong[]): Map<string, string[]> {
  const files = songs.flatMap((s) => (s.path && isRealPath(s.path) ? [{ id: s.id, parts: s.path.split(/[\\/]+/).filter(Boolean) }] : []));
  const out = new Map<string, string[]>();
  if (files.length === 0) return out;

  let root = files[0].parts.slice(0, -1);
  for (const f of files) {
    let i = 0;
    while (i < root.length && i < f.parts.length - 1 && root[i] === f.parts[i]) i++;
    root = root.slice(0, i);
  }

  const byFolder = new Map<string, { id: string; rel: string }[]>();
  for (const f of files) {
    const rel = f.parts.slice(root.length);
    if (rel.length < 2) continue;
    const list = byFolder.get(rel[0]) ?? [];
    list.push({ id: f.id, rel: rel.join("/") });
    byFolder.set(rel[0], list);
  }
  for (const [folder, list] of [...byFolder].sort(([a], [b]) => collator.compare(a, b))) {
    out.set(folder, list.sort((a, b) => collator.compare(a.rel, b.rel)).map((x) => x.id));
  }
  return out;
}

/**
 * Allinea le raccolte alle cartelle. [saved] null = primo allineamento su questo browser:
 * se altrove ci sono già raccolte, quelle che mancano potrebbero essere state eliminate,
 * quindi non si ricreano da sole (si possono creare a mano).
 */
export function planSync(folders: Map<string, string[]>, playlists: { id: string; comment?: string }[], saved: CollectionsState | null): SyncPlan {
  const byFolder = new Map<string, string>();
  for (const p of playlists) {
    const f = folderOf(p);
    if (f !== null && !byFolder.has(f)) byFolder.set(f, p.id);
  }
  const firstElsewhere = saved === null && byFolder.size > 0;
  const plan: SyncPlan = { create: [], add: [], missing: [], state: { known: { ...saved?.known } } };

  for (const [folder, ids] of folders) {
    const seen = saved?.known?.[folder];
    const playlistId = byFolder.get(folder);
    if (playlistId !== undefined) {
      if (seen) {
        const before = new Set(seen);
        const fresh = ids.filter((id) => !before.has(id));
        if (fresh.length > 0) plan.add.push({ playlistId, songIds: fresh });
      }
    } else if (seen || firstElsewhere) {
      plan.missing.push(folder);
    } else {
      plan.create.push({ folder, songIds: ids });
    }
    // Si ricorda solo cosa c'è ora: un file tolto e poi rimesso nella cartella rientra.
    plan.state.known[folder] = ids;
  }
  return plan;
}
