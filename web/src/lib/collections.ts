// Raccolte = cartelle, come sul telefono: ogni prima cartella dentro quella della musica
// ("Music/Disney Italia") diventa una playlist di Navidrome, riconosciuta dal commento.
// Per il resto è una playlist: si rinomina, si aggiunge, si toglie, si elimina.
// I brani nuovi della cartella entrano da soli; quelli tolti a mano non tornano; una
// raccolta eliminata non si ricrea (l'elenco delle eliminate sta su Navidrome, così vale per
// ogni browser); una cartella rinominata si porta dietro la sua raccolta. Non si toccano mai i file.

/** Brano con il percorso del file come lo manda Navidrome. */
export interface FolderSong {
  id: string;
  path?: string;
}

/**
 * Cosa ricorda questo browser. [known]: i brani di ogni cartella che ha una raccolta,
 * all'ultimo allineamento. Prima di [v] 2 c'erano tutte le cartelle, anche senza raccolta:
 * da quelle non si capisce se una raccolta era stata eliminata.
 */
export interface CollectionsState {
  known: Record<string, string[]>;
  v?: 2;
  /** Cartelle senza raccolta che decidi tu (crearle o no): forse eliminate prima che l'app se lo ricordasse. */
  unsure?: string[];
}

export interface SyncPlan {
  /** Raccolte da creare, con i brani in ordine di percorso. */
  create: { folder: string; songIds: string[] }[];
  /** Brani arrivati nella cartella dall'ultima volta (chi li ha già in playlist li salta). */
  add: { playlistId: string; songIds: string[] }[];
  /** Cartelle senza raccolta da decidere tu («Crea tutte» / «Non crearle»). */
  missing: string[];
  /** Cartelle delle raccolte che hai eliminato: non si ricreano da sole (si possono ricreare a mano). */
  deleted: string[];
  /** Raccolte sparite da quando questo browser le ha viste (eliminate altrove): vanno nell'elenco su Navidrome. */
  newlyDeleted: string[];
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
 * Allinea le raccolte alle cartelle. [deleted]: le cartelle delle raccolte eliminate, dall'elenco
 * su Navidrome. Una cartella senza raccolta diventa raccolta, anche su un browser nuovo, tranne
 * se la sua raccolta è stata eliminata; con lo stato vecchio di questo browser (senza [v]) non si
 * sa, e la scelta resta a te ([SyncPlan.missing]).
 */
export function planSync(
  folders: Map<string, string[]>,
  playlists: { id: string; comment?: string }[],
  saved: CollectionsState | null,
  deleted: ReadonlySet<string> = new Set(),
): SyncPlan {
  const byFolder = new Map<string, string>();
  for (const p of playlists) {
    const f = folderOf(p);
    if (f !== null && !byFolder.has(f)) byFolder.set(f, p.id);
  }
  const current = saved?.v === 2;
  const unsure = new Set(saved?.unsure ?? []);
  const plan: SyncPlan = { create: [], add: [], missing: [], deleted: [], newlyDeleted: [], state: { known: {}, v: 2, unsure: [] } };
  // Le cartelle sparite che avevano una raccolta restano ricordate: se tornano, la raccolta c'è o è stata eliminata.
  for (const [folder, ids] of Object.entries(saved?.known ?? {})) {
    if (!folders.has(folder) && (current || byFolder.has(folder)) && !deleted.has(folder)) plan.state.known[folder] = ids;
  }

  for (const [folder, ids] of folders) {
    const seen = saved?.known?.[folder];
    const playlistId = byFolder.get(folder);
    if (playlistId !== undefined) {
      if (seen) {
        const before = new Set(seen);
        const fresh = ids.filter((id) => !before.has(id));
        if (fresh.length > 0) plan.add.push({ playlistId, songIds: fresh });
      }
    } else if (deleted.has(folder)) {
      plan.deleted.push(folder);
      continue;
    } else if (unsure.has(folder) || (seen && !current)) {
      plan.missing.push(folder);
      plan.state.unsure!.push(folder);
      continue;
    } else if (seen) {
      // Aveva una raccolta e non c'è più: eliminata (fuori dall'app, o da un altro browser).
      plan.newlyDeleted.push(folder);
      plan.deleted.push(folder);
      continue;
    } else {
      plan.create.push({ folder, songIds: ids });
    }
    // Si ricorda solo cosa c'è ora: un file tolto e poi rimesso nella cartella rientra.
    plan.state.known[folder] = ids;
  }
  return plan;
}

// L'elenco delle raccolte eliminate sta su Navidrome, nel commento di una playlist vuota tutta
// sua: così un browser nuovo (o il tablet) sa quali non ricreare. Le app la nascondono.
export const DELETED_STORE_NAME = "BaldMusic · raccolte eliminate";
const STORE_MARK = "BaldMusic: raccolte eliminate ";

export function deletedStoreComment(folders: Iterable<string>): string {
  return STORE_MARK + JSON.stringify([...new Set(folders)].sort());
}

/** Le cartelle eliminate scritte nella playlist dell'elenco, o null se non è quella playlist. */
export function deletedInStore(p: { comment?: string }): string[] | null {
  const c = p.comment ?? "";
  if (!c.startsWith(STORE_MARK)) return null;
  try {
    const v: unknown = JSON.parse(c.slice(STORE_MARK.length));
    return Array.isArray(v) ? v.filter((x): x is string => typeof x === "string") : [];
  } catch {
    return [];
  }
}

/** Un brano per riconoscere lo stesso file in un'altra cartella. */
export interface TrackKey {
  id: string;
  title: string;
  duration?: number;
}

export interface FollowPlan {
  playlistId: string;
  /** La cartella nuova. */
  folder: string;
  /** Il nome nuovo, se la raccolta aveva ancora il nome della cartella; null = lo tieni. */
  rename: string | null;
  /** I brani della raccolta con gli ID nuovi dei file (se sono cambiati), nello stesso ordine. */
  songIds: string[];
  /** ID vecchio → nuovo, per preferiti e altre playlist. */
  newIds: Map<string, string>;
}

/** Stesso file: entro 2 s, come sul telefono. */
const SAME_FILE_S = 2;
const titleKey = (t: string) =>
  t
    .normalize("NFD")
    .replace(/[\u0300-\u036f]/g, "")
    .toLowerCase()
    .replace(/[^\p{L}\p{N}]+/gu, " ")
    .trim();

/**
 * Cartelle rinominate o spostate (come `followMovedFolders` sul telefono): una raccolta la cui
 * cartella non c'è più ([orphans]) segue la cartella nuova, ancora senza raccolta e non eliminata,
 * che ha almeno metà dei suoi brani (stesso ID, o stesso titolo e durata entro 2 s se Navidrome
 * ha dato ID nuovi). Ogni cartella ne prende una sola, dalla più somigliante.
 */
export function planFollow(
  folders: Map<string, TrackKey[]>,
  orphans: { id: string; name: string; folder: string; songs: TrackKey[] }[],
  taken: ReadonlySet<string>,
  deleted: ReadonlySet<string>,
): FollowPlan[] {
  const fresh = [...folders].filter(([f, songs]) => !taken.has(f) && !deleted.has(f) && songs.length > 0);
  if (fresh.length === 0 || orphans.length === 0) return [];
  const lookups = new Map(
    fresh.map(([f, songs]) => {
      const byId = new Map(songs.map((s) => [s.id, s]));
      const byTitle = new Map<string, TrackKey[]>();
      for (const s of songs) byTitle.set(titleKey(s.title), [...(byTitle.get(titleKey(s.title)) ?? []), s]);
      const find = (t: TrackKey) =>
        byId.get(t.id) ??
        byTitle.get(titleKey(t.title))?.find((o) => t.duration !== undefined && o.duration !== undefined && Math.abs(t.duration - o.duration) <= SAME_FILE_S);
      return [f, find] as const;
    }),
  );
  const matches = orphans
    .filter((o) => o.songs.length > 0)
    .flatMap((o) =>
      fresh.flatMap(([f]) => {
        const found = new Map<string, string>();
        for (const t of o.songs) {
          const m = lookups.get(f)!(t);
          if (m) found.set(t.id, m.id);
        }
        return found.size * 2 >= o.songs.length ? [{ o, f, found }] : [];
      }),
    )
    .sort((a, b) => b.found.size - a.found.size);
  const usedPlaylists = new Set<string>();
  const usedFolders = new Set<string>();
  const out: FollowPlan[] = [];
  for (const { o, f, found } of matches) {
    if (usedPlaylists.has(o.id) || usedFolders.has(f)) continue;
    usedPlaylists.add(o.id);
    usedFolders.add(f);
    const newIds = new Map([...found].filter(([a, b]) => a !== b));
    out.push({ playlistId: o.id, folder: f, rename: o.name === o.folder ? f : null, songIds: o.songs.map((s) => newIds.get(s.id) ?? s.id), newIds });
  }
  return out;
}
