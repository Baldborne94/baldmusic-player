import { useMemo, useState } from "react";
import { ArtistGrid } from "../components/AlbumGrid";
import { ErrorState, Loading } from "../components/States";
import { getClient } from "../lib/session";
import { useLoad } from "../lib/useLoad";

export function Artists() {
  const { data, error, loading, reload } = useLoad(() => getClient()!.artists(), []);
  const [filter, setFilter] = useState("");
  const shown = useMemo(() => {
    const f = filter.trim().toLowerCase();
    return (data ?? []).filter((a) => !f || a.name.toLowerCase().includes(f));
  }, [data, filter]);

  if (loading && !data) return <Loading />;
  if (error) return <ErrorState message={error} onRetry={reload} />;
  return (
    <section>
      <header className="page-head">
        <h1>Artisti</h1>
        <input className="filter" value={filter} onChange={(e) => setFilter(e.target.value)} placeholder="Filtra…" aria-label="Filtra artisti" />
      </header>
      <ArtistGrid artists={shown} />
    </section>
  );
}
