import { useMemo, useState } from "react";
import { Cover } from "../components/Cover";
import { Icon } from "../components/Icon";
import { ErrorState, Loading } from "../components/States";
import { AlbumCards, ArtistCards, LikedArt, PlaylistCards } from "../components/Ui";
import { isRealAlbum } from "../lib/albums";
import { createCollection, createPlaylist, isCollection, useLibrary, type CollectionsStatus } from "../lib/library";
import { href, type Route } from "../lib/router";
import { getClient } from "../lib/session";
import type { Album, AlbumListType } from "../lib/subsonic";
import { useLoad } from "../lib/useLoad";

type Filter = "collections" | "playlists" | "albums" | "artists";

const FILTERS: [Filter, string][] = [
  ["collections", "Raccolte"],
  ["playlists", "Playlist"],
  ["albums", "Album"],
  ["artists", "Artisti"],
];

/** Perché non ci sono raccolte, se non è solo che non ci sono cartelle. */
export function CollectionsHint({ status }: { status: CollectionsStatus }) {
  if (status.state === "syncing" || status.state === "idle") return <p className="muted pad">Leggo le cartelle della musica…</p>;
  if (status.state === "error") return <p className="muted pad">Raccolte non aggiornate: {status.error}</p>;
  if (status.state === "noRealPath") {
    return (
      <p className="muted pad">
        Le raccolte nascono dalle cartelle, ma Navidrome non dice dove stanno i file. Si attiva una volta: in Navidrome, menu in alto a
        destra → <strong>Players</strong> (Lettori) → la riga con client <strong>baldmusic</strong> → <strong>Report Real Path</strong> (Mostra percorso reale), salva e
        ricarica questa pagina.
      </p>
    );
  }
  return <p className="muted pad">Nessuna raccolta: non ci sono cartelle dentro quella della musica di Navidrome.</p>;
}

/** La libreria della barra laterale: preferiti, playlist, album e artisti in elenco compatto. */
export function SidebarLibrary({ route }: { route: Route }) {
  const lib = useLibrary();
  const [chosen, setFilter] = useState<Filter | null>(null);
  const collections = lib.playlists.filter(isCollection);
  // Come sul telefono, prima le raccolte (se ci sono).
  const filter = chosen ?? (collections.length > 0 ? "collections" : "playlists");
  const [q, setQ] = useState("");
  // Tutti gli album veri in ordine alfabetico ("recent" erano solo quelli ascoltati di recente).
  const albums = useLoad(async () => (filter === "albums" ? (await getClient()!.albumList("alphabeticalByName", 500)).filter(isRealAlbum) : ([] as Album[])), [filter]);
  const artists = useLoad(() => (filter === "artists" ? getClient()!.artists() : Promise.resolve([])), [filter]);
  const f = q.trim().toLowerCase();
  const active = "id" in route ? `${route.name}/${route.id}` : route.name;

  return (
    <div className="side-lib">
      <header className="side-lib__head">
        <a href={href({ name: "library" })} className="side-lib__title">
          <Icon name="library" /> La tua libreria
        </a>
        <button
          className="icon-btn"
          title="Crea playlist"
          onClick={async () => {
            const name = prompt("Nome della nuova playlist", "La mia playlist");
            if (name) await createPlaylist(name);
          }}
        >
          <Icon name="plus" size={20} />
        </button>
      </header>
      <div className="chips chips--small">
        {FILTERS.map(([k, label]) => (
          <button key={k} className={`chip ${filter === k ? "is-on" : ""}`} onClick={() => setFilter(k)}>
            {label}
          </button>
        ))}
      </div>
      <input className="side-lib__filter" value={q} onChange={(e) => setQ(e.target.value)} placeholder="Cerca nella libreria" />
      <div className="side-lib__list">
        {filter === "collections" && (
          <>
            {collections.length === 0 && <CollectionsHint status={lib.collections} />}
            {collections.filter((p) => !f || p.name.toLowerCase().includes(f)).map((p) => (
              <a key={p.id} className={`lib-row ${active === `playlist/${p.id}` ? "is-active" : ""}`} href={href({ name: "playlist", id: p.id })}>
                <Cover id={p.coverArt} size={96} className="lib-row__art" />
                <span className="lib-row__text">
                  <span className="lib-row__title">{p.name}</span>
                  <span className="lib-row__sub">Raccolta · {p.songCount ?? 0} brani</span>
                </span>
              </a>
            ))}
          </>
        )}
        {filter === "playlists" && (
          <>
            <a className={`lib-row ${active === "liked" ? "is-active" : ""}`} href={href({ name: "liked" })}>
              <span className="lib-row__art"><LikedArt /></span>
              <span className="lib-row__text">
                <span className="lib-row__title">Brani che ti piacciono</span>
                <span className="lib-row__sub">📌 Playlist · {lib.likedSongs.length} brani</span>
              </span>
            </a>
            {lib.playlists.filter((p) => !isCollection(p) && (!f || p.name.toLowerCase().includes(f))).map((p) => (
              <a key={p.id} className={`lib-row ${active === `playlist/${p.id}` ? "is-active" : ""}`} href={href({ name: "playlist", id: p.id })}>
                <Cover id={p.coverArt} size={96} className="lib-row__art" />
                <span className="lib-row__text">
                  <span className="lib-row__title">{p.name}</span>
                  <span className="lib-row__sub">Playlist · {p.owner ?? ""}</span>
                </span>
              </a>
            ))}
          </>
        )}
        {filter === "albums" &&
          (albums.data ?? []).filter((a) => !f || a.name.toLowerCase().includes(f) || a.artist?.toLowerCase().includes(f)).map((a) => (
            <a key={a.id} className={`lib-row ${active === `album/${a.id}` ? "is-active" : ""}`} href={href({ name: "album", id: a.id })}>
              <Cover id={a.coverArt} size={96} className="lib-row__art" />
              <span className="lib-row__text">
                <span className="lib-row__title">{a.name}</span>
                <span className="lib-row__sub">Album · {a.artist}</span>
              </span>
            </a>
          ))}
        {filter === "artists" &&
          (artists.data ?? []).filter((a) => !f || a.name.toLowerCase().includes(f)).map((a) => (
            <a key={a.id} className={`lib-row ${active === `artist/${a.id}` ? "is-active" : ""}`} href={href({ name: "artist", id: a.id })}>
              <Cover id={a.coverArt ?? `ar-${a.id}`} size={96} round className="lib-row__art" />
              <span className="lib-row__text">
                <span className="lib-row__title">{a.name}</span>
                <span className="lib-row__sub">Artista</span>
              </span>
            </a>
          ))}
      </div>
    </div>
  );
}

const SORTS: [AlbumListType, string][] = [
  ["recent", "Ascoltati di recente"],
  ["alphabeticalByName", "Titolo"],
  ["alphabeticalByArtist", "Artista"],
  ["newest", "Aggiunti di recente"],
  ["frequent", "Più ascoltati"],
];

/** La libreria a pagina intera (telefono, tablet e "Mostra tutto"). */
export function LibraryPage({ initial }: { initial: Filter | null }) {
  const lib = useLibrary();
  const [chosen, setFilter] = useState<Filter | null>(initial);
  const collections = lib.playlists.filter(isCollection);
  const filter = chosen ?? (collections.length > 0 ? "collections" : "playlists");
  const [sort, setSort] = useState<AlbumListType>("alphabeticalByName");
  // Singoli e album di cui c'è un brano solo stanno sotto l'artista, non qui.
  const albums = useLoad(async () => (filter === "albums" ? (await getClient()!.albumList(sort, 500)).filter(isRealAlbum) : ([] as Album[])), [filter, sort]);
  const artists = useLoad(() => (filter === "artists" ? getClient()!.artists() : Promise.resolve([])), [filter]);
  const likedAsPlaylist = useMemo(() => lib.likedSongs, [lib.likedSongs]);

  return (
    <div className="page">
      <header className="page-head">
        <h1>La tua libreria</h1>
        <button
          className="btn"
          onClick={async () => {
            const name = prompt("Nome della nuova playlist", "La mia playlist");
            if (name) await createPlaylist(name);
          }}
        >
          <Icon name="plus" size={18} /> Nuova playlist
        </button>
      </header>
      <div className="chips">
        {FILTERS.map(([k, label]) => (
          <button key={k} className={`chip ${filter === k ? "is-on" : ""}`} onClick={() => setFilter(k)}>
            {label}
          </button>
        ))}
        {filter === "albums" && (
          <select value={sort} onChange={(e) => setSort(e.target.value as AlbumListType)} aria-label="Ordina">
            {SORTS.map(([k, l]) => (
              <option key={k} value={k}>{l}</option>
            ))}
          </select>
        )}
      </div>
      {filter === "collections" && (
        <>
          {collections.length === 0 ? <CollectionsHint status={lib.collections} /> : <PlaylistCards playlists={collections} />}
          {lib.collections.missing.length > 0 && (
            <div className="pad">
              <p className="muted">Cartelle senza raccolta (eliminata, o creata da un altro browser):</p>
              <div className="chips">
                {lib.collections.missing.map((folder) => (
                  <button key={folder} className="chip" onClick={() => void createCollection(folder)}>
                    <Icon name="plus" size={16} /> {folder}
                  </button>
                ))}
              </div>
            </div>
          )}
        </>
      )}
      {filter === "playlists" && (
        <div className="grid">
          <a className="card liked-card" href={href({ name: "liked" })}>
            <span className="liked-card__title">Brani che ti piacciono</span>
            <span className="liked-card__sub">{likedAsPlaylist.length} brani</span>
          </a>
          <PlaylistCards playlists={lib.playlists.filter((p) => !isCollection(p))} bare />
        </div>
      )}
      {filter === "albums" && (albums.loading ? <Loading /> : albums.error ? <ErrorState message={albums.error} /> : <AlbumCards albums={albums.data ?? []} />)}
      {filter === "artists" && (artists.loading ? <Loading /> : artists.error ? <ErrorState message={artists.error} /> : <ArtistCards artists={artists.data ?? []} />)}
    </div>
  );
}
