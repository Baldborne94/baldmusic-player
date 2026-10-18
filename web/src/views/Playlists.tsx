import { Cover } from "../components/Cover";
import { Icon } from "../components/Icon";
import { ErrorState, Loading } from "../components/States";
import { TrackList } from "../components/TrackList";
import { formatTotal, plural } from "../lib/format";
import { player } from "../lib/player";
import { href } from "../lib/router";
import { getClient } from "../lib/session";
import { useLoad } from "../lib/useLoad";

export function Playlists() {
  const { data, error, loading, reload } = useLoad(() => getClient()!.playlists(), []);
  if (loading && !data) return <Loading />;
  if (error) return <ErrorState message={error} onRetry={reload} />;
  return (
    <section>
      <header className="page-head">
        <h1>Playlist</h1>
      </header>
      {data!.length === 0 ? (
        <p className="muted">Nessuna playlist. Puoi crearle dall'interfaccia web di Navidrome o importare file .m3u nella cartella della musica.</p>
      ) : (
        <div className="grid">
          {data!.map((p) => (
            <a key={p.id} className="card" href={href({ name: "playlist", id: p.id })}>
              <Cover id={p.coverArt} alt="" />
              <span className="card__title">{p.name}</span>
              <span className="card__sub">{plural(p.songCount ?? 0, "brano", "brani")}</span>
            </a>
          ))}
        </div>
      )}
    </section>
  );
}

export function PlaylistView({ id }: { id: string }) {
  const { data: pl, error, loading, reload } = useLoad(() => getClient()!.playlist(id), [id]);
  if (loading && !pl) return <Loading />;
  if (error || !pl) return <ErrorState message={error ?? "Playlist non trovata"} onRetry={reload} />;
  const songs = pl.entry ?? [];
  return (
    <section>
      <header className="hero">
        <Cover id={pl.coverArt} size={600} className="hero__cover" />
        <div className="hero__text">
          <span className="eyebrow">Playlist</span>
          <h1>{pl.name}</h1>
          <p>
            {plural(songs.length, "brano", "brani")}
            {pl.duration ? ` · ${formatTotal(pl.duration)}` : ""}
          </p>
          <div className="actions">
            <button className="btn btn--primary" onClick={() => player.play(songs)}>
              <Icon name="play" size={20} /> Riproduci
            </button>
            <button className="btn" onClick={() => player.play(songs, Math.floor(Math.random() * songs.length), true)}>
              <Icon name="shuffle" size={20} /> Casuale
            </button>
          </div>
        </div>
      </header>
      <TrackList songs={songs} showCover numbered={false} showAlbum />
    </section>
  );
}
