import { useEffect, useState } from "react";
import { AlbumRow, ArtistGrid } from "../components/AlbumGrid";
import { ErrorState, Loading } from "../components/States";
import { TrackList } from "../components/TrackList";
import { navigate } from "../lib/router";
import { getClient } from "../lib/session";
import type { SearchResult } from "../lib/subsonic";

export function Search({ query }: { query: string }) {
  const [text, setText] = useState(query);
  const [result, setResult] = useState<SearchResult | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  // Ricerca mentre scrivi, con una piccola pausa per non martellare il server.
  useEffect(() => {
    const q = text.trim();
    const t = setTimeout(() => navigate({ name: "search", query: q }, true), 250);
    return () => clearTimeout(t);
  }, [text]);

  useEffect(() => {
    if (!query) {
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
    return () => {
      alive = false;
    };
  }, [query]);

  const empty = result && result.song.length + result.album.length + result.artist.length === 0;
  return (
    <section>
      <header className="page-head">
        <input className="search" type="search" autoFocus value={text} onChange={(e) => setText(e.target.value)} placeholder="Artisti, album, brani…" aria-label="Cerca" />
      </header>
      {error && <ErrorState message={error} />}
      {loading && !result && <Loading />}
      {empty && <p className="muted">Nessun risultato per “{query}”.</p>}
      {result && result.artist.length > 0 && (
        <div className="shelf">
          <h2>Artisti</h2>
          <ArtistGrid artists={result.artist} />
        </div>
      )}
      {result && result.album.length > 0 && (
        <div className="shelf">
          <h2>Album</h2>
          <AlbumRow albums={result.album} />
        </div>
      )}
      {result && result.song.length > 0 && (
        <div className="shelf">
          <h2>Brani</h2>
          <TrackList songs={result.song} showCover numbered={false} showAlbum />
        </div>
      )}
    </section>
  );
}
