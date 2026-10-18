import { useEffect, useState } from "react";
import { Cover } from "../components/Cover";
import { Icon } from "../components/Icon";
import { ErrorState, Loading } from "../components/States";
import { AlbumCards, ArtistCards, PlaylistCards, TrackTable } from "../components/Ui";
import { tileGradient } from "../lib/colors";
import { clearSearches, recentSearches, rememberSearch, useLibrary } from "../lib/library";
import { player } from "../lib/player";
import { href, navigate } from "../lib/router";
import { getClient } from "../lib/session";
import type { SearchResult } from "../lib/subsonic";
import { useLoad } from "../lib/useLoad";

const DECADES = [2020, 2010, 2000, 1990, 1980, 1970, 1960];

export function Search({ query }: { query: string }) {
  const [text, setText] = useState(query);
  const [result, setResult] = useState<SearchResult | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [recent, setRecent] = useState(recentSearches);
  const lib = useLibrary();
  const genres = useLoad(() => getClient()!.genres(), []);
  // Solo i decenni in cui c'è davvero qualcosa.
  const decades = useLoad(async () => {
    const c = getClient()!;
    const hits = await Promise.all(DECADES.map((y) => c.albumsByYear(y, y + 9, 1).then((a) => a.length > 0).catch(() => false)));
    return DECADES.filter((_, i) => hits[i]);
  }, []);

  // Ricerca mentre scrivi, con una piccola pausa per non martellare il server.
  useEffect(() => {
    const t = setTimeout(() => navigate({ name: "search", query: text.trim() }, true), 250);
    return () => clearTimeout(t);
  }, [text]);

  useEffect(() => {
    if (query.length < 2) {
      setResult(null);
      return;
    }
    let alive = true;
    setLoading(true);
    getClient()!
      .search(query)
      .then(
        (r) => alive && (setResult(r), setError(null)),
        (e: unknown) => alive && setError(e instanceof Error ? e.message : String(e)),
      )
      .finally(() => alive && setLoading(false));
    // La ricerca si ricorda dopo un attimo di calma, non lettera per lettera.
    const t = setTimeout(() => {
      rememberSearch(query);
      setRecent(recentSearches());
    }, 1500);
    return () => {
      alive = false;
      clearTimeout(t);
    };
  }, [query]);

  const playlists = result ? lib.playlists.filter((p) => p.name.toLowerCase().includes(query.toLowerCase())) : [];
  const top = result?.artist[0] ? { kind: "Artista", title: result.artist[0].name, cover: result.artist[0].coverArt ?? `ar-${result.artist[0].id}`, to: href({ name: "artist", id: result.artist[0].id }), round: true } : result?.album[0] ? { kind: "Album", title: result.album[0].name, cover: result.album[0].coverArt, to: href({ name: "album", id: result.album[0].id }), round: false, sub: result.album[0].artist } : null;

  return (
    <div className="page">
      <div className="search-bar">
        <Icon name="search" size={22} />
        <input type="text" enterKeyHint="search" autoFocus value={text} onChange={(e) => setText(e.target.value)} placeholder="Cosa vuoi ascoltare?" aria-label="Cerca" />
        {text && (
          <button className="icon-btn" onClick={() => setText("")} aria-label="Cancella">
            <Icon name="close" size={20} />
          </button>
        )}
      </div>

      {query.length < 2 && (
        <>
          {recent.length > 0 && (
            <section className="shelf">
              <header className="shelf__head">
                <h2>Ricerche recenti</h2>
                <button className="shelf__more" onClick={() => (clearSearches(), setRecent([]))}>
                  Cancella
                </button>
              </header>
              <div className="chips">
                {recent.map((s) => (
                  <button key={s} className="chip" onClick={() => setText(s)}>
                    <Icon name="clock" size={16} /> {s}
                  </button>
                ))}
              </div>
            </section>
          )}
          <section className="shelf">
            <header className="shelf__head">
              <h2>Sfoglia tutto</h2>
            </header>
            <div className="browse">
              <BrowseTile to={href({ name: "liked" })} title="Brani che ti piacciono" />
              <BrowseTile to={href({ name: "albums" })} title="Album" />
              <BrowseTile to={href({ name: "artists" })} title="Artisti" />
              <BrowseTile to={href({ name: "playlists" })} title="Playlist" />
              <BrowseTile to={href({ name: "stats" })} title="Le tue statistiche" />
              {(decades.data ?? []).map((y) => (
                <BrowseTile key={y} to={href({ name: "decade", id: String(y) })} title={`Anni ${y >= 2000 ? y : y % 100}`} />
              ))}
              {(genres.data ?? []).map((g) => (
                <BrowseTile key={g.value} to={href({ name: "genre", id: g.value })} title={g.value} />
              ))}
            </div>
          </section>
        </>
      )}

      {error && <ErrorState message={error} />}
      {loading && !result && <Loading />}
      {result && query.length >= 2 && (
        <>
          {result.artist.length + result.album.length + result.song.length + playlists.length === 0 ? (
            <p className="muted pad">Nessun risultato per “{query}”.</p>
          ) : (
            <div className="search-top">
              {top && (
                <section>
                  <h2>Risultato migliore</h2>
                  <a className="top-result" href={top.to}>
                    <Cover id={top.cover} size={200} round={top.round} className="top-result__art" />
                    <span className="top-result__title">{top.title}</span>
                    <span className="top-result__kind">{top.kind}{top.sub ? ` · ${top.sub}` : ""}</span>
                  </a>
                </section>
              )}
              {result.song.length > 0 && (
                <section className="search-top__songs">
                  <h2>Brani</h2>
                  <TrackTable songs={result.song.slice(0, 5)} from={{ label: `Ricerca: ${query}`, id: `search/${query}` }} showAlbum={false} />
                </section>
              )}
            </div>
          )}
          {result.song.length > 5 && (
            <section className="shelf">
              <header className="shelf__head">
                <h2>Altri brani</h2>
                <button className="shelf__more" onClick={() => player.play(result.song, 0, false, { label: `Ricerca: ${query}`, id: `search/${query}` })}>
                  Riproduci tutti
                </button>
              </header>
              <TrackTable songs={result.song.slice(5)} from={{ label: `Ricerca: ${query}`, id: `search/${query}` }} />
            </section>
          )}
          {result.artist.length > 0 && (
            <section className="shelf">
              <header className="shelf__head"><h2>Artisti</h2></header>
              <ArtistCards artists={result.artist} scroll />
            </section>
          )}
          {result.album.length > 0 && (
            <section className="shelf">
              <header className="shelf__head"><h2>Album</h2></header>
              <AlbumCards albums={result.album} scroll />
            </section>
          )}
          {playlists.length > 0 && (
            <section className="shelf">
              <header className="shelf__head"><h2>Playlist</h2></header>
              <PlaylistCards playlists={playlists} scroll />
            </section>
          )}
        </>
      )}
    </div>
  );
}

function BrowseTile({ to, title }: { to: string; title: string }) {
  return (
    <a className="browse__tile" href={to} style={{ background: tileGradient(title) }}>
      <span>{title}</span>
      <i />
    </a>
  );
}
