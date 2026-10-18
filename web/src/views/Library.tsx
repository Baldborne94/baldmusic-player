import { useMemo, useState } from "react";
import { Cover } from "../components/Cover";
import { Icon } from "../components/Icon";
import { ErrorState, Loading } from "../components/States";
import { AlbumCards, ArtistCards, LikedArt, PlaylistCards } from "../components/Ui";
import { createPlaylist, useLibrary } from "../lib/library";
import { href, type Route } from "../lib/router";
import { getClient } from "../lib/session";
import type { Album, AlbumListType } from "../lib/subsonic";
import { useLoad } from "../lib/useLoad";

type Filter = "playlists" | "albums" | "artists";

/** La libreria della barra laterale: preferiti, playlist, album e artisti in elenco compatto. */
export function SidebarLibrary({ route }: { route: Route }) {
  const lib = useLibrary();
  const [filter, setFilter] = useState<Filter>("playlists");
  const [q, setQ] = useState("");
  const albums = useLoad(() => (filter === "albums" ? getClient()!.albumList("recent", 100) : Promise.resolve([] as Album[])), [filter]);
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
        {(["playlists", "albums", "artists"] as Filter[]).map((k) => (
          <button key={k} className={`chip ${filter === k ? "is-on" : ""}`} onClick={() => setFilter(k)}>
            {k === "playlists" ? "Playlist" : k === "albums" ? "Album" : "Artisti"}
          </button>
        ))}
      </div>
      <input className="side-lib__filter" value={q} onChange={(e) => setQ(e.target.value)} placeholder="Cerca nella libreria" />
      <div className="side-lib__list">
        {filter === "playlists" && (
          <>
            <a className={`lib-row ${active === "liked" ? "is-active" : ""}`} href={href({ name: "liked" })}>
              <span className="lib-row__art"><LikedArt /></span>
              <span className="lib-row__text">
                <span className="lib-row__title">Brani che ti piacciono</span>
                <span className="lib-row__sub">📌 Playlist · {lib.likedSongs.length} brani</span>
              </span>
            </a>
            {lib.playlists.filter((p) => !f || p.name.toLowerCase().includes(f)).map((p) => (
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
  const [filter, setFilter] = useState<Filter>(initial ?? "playlists");
  const [sort, setSort] = useState<AlbumListType>("alphabeticalByName");
  const albums = useLoad(() => (filter === "albums" ? getClient()!.albumList(sort, 500) : Promise.resolve([] as Album[])), [filter, sort]);
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
        {(["playlists", "albums", "artists"] as Filter[]).map((k) => (
          <button key={k} className={`chip ${filter === k ? "is-on" : ""}`} onClick={() => setFilter(k)}>
            {k === "playlists" ? "Playlist" : k === "albums" ? "Album" : "Artisti"}
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
      {filter === "playlists" && (
        <div className="grid">
          <a className="card liked-card" href={href({ name: "liked" })}>
            <span className="liked-card__title">Brani che ti piacciono</span>
            <span className="liked-card__sub">{likedAsPlaylist.length} brani</span>
          </a>
          <PlaylistCards playlists={lib.playlists} bare />
        </div>
      )}
      {filter === "albums" && (albums.loading ? <Loading /> : albums.error ? <ErrorState message={albums.error} /> : <AlbumCards albums={albums.data ?? []} />)}
      {filter === "artists" && (artists.loading ? <Loading /> : artists.error ? <ErrorState message={artists.error} /> : <ArtistCards artists={artists.data ?? []} />)}
    </div>
  );
}
