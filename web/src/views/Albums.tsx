import { useEffect, useRef, useState } from "react";
import { AlbumGrid } from "../components/AlbumGrid";
import { ErrorState, Loading } from "../components/States";
import { getClient } from "../lib/session";
import type { Album, AlbumListType } from "../lib/subsonic";

const PAGE = 60;
const SORTS: [AlbumListType, string][] = [
  ["alphabeticalByName", "Titolo"],
  ["alphabeticalByArtist", "Artista"],
  ["newest", "Aggiunti di recente"],
  ["frequent", "Più ascoltati"],
  ["random", "Casuale"],
];

export function Albums() {
  const [sort, setSort] = useState<AlbumListType>("alphabeticalByName");
  const [albums, setAlbums] = useState<Album[]>([]);
  const [done, setDone] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const sentinel = useRef<HTMLDivElement>(null);
  const gen = useRef(0);

  const loadMore = async (reset: boolean) => {
    const myGen = reset ? ++gen.current : gen.current;
    setLoading(true);
    try {
      const page = await getClient()!.albumList(sort, PAGE, reset ? 0 : albums.length);
      if (myGen !== gen.current) return;
      setAlbums((prev) => (reset ? page : [...prev, ...page]));
      setDone(page.length < PAGE);
      setError(null);
    } catch (e) {
      if (myGen === gen.current) setError(e instanceof Error ? e.message : String(e));
    } finally {
      if (myGen === gen.current) setLoading(false);
    }
  };

  useEffect(() => {
    setAlbums([]);
    setDone(false);
    void loadMore(true);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [sort]);

  // Scorrimento infinito: carica la pagina successiva quando la fine entra in vista.
  useEffect(() => {
    const el = sentinel.current;
    if (!el || done || loading || error) return;
    const io = new IntersectionObserver((e) => e[0].isIntersecting && void loadMore(false), { rootMargin: "600px" });
    io.observe(el);
    return () => io.disconnect();
  });

  return (
    <section>
      <header className="page-head">
        <h1>Album</h1>
        <select value={sort} onChange={(e) => setSort(e.target.value as AlbumListType)} aria-label="Ordina per">
          {SORTS.map(([k, label]) => (
            <option key={k} value={k}>
              {label}
            </option>
          ))}
        </select>
      </header>
      {error && albums.length === 0 ? <ErrorState message={error} onRetry={() => loadMore(true)} /> : <AlbumGrid albums={albums} />}
      {loading && <Loading />}
      <div ref={sentinel} />
    </section>
  );
}
