import { Cover } from "../components/Cover";
import { Icon } from "../components/Icon";
import { ErrorState, Loading } from "../components/States";
import { TrackList } from "../components/TrackList";
import { formatTotal, plural } from "../lib/format";
import { player } from "../lib/player";
import { href } from "../lib/router";
import { getClient } from "../lib/session";
import { useLoad } from "../lib/useLoad";

export function AlbumView({ id }: { id: string }) {
  const { data: album, error, loading, reload } = useLoad(() => getClient()!.album(id), [id]);
  if (loading && !album) return <Loading />;
  if (error || !album) return <ErrorState message={error ?? "Album non trovato"} onRetry={reload} />;
  const songs = album.song ?? [];
  const discs = new Set(songs.map((s) => s.discNumber ?? 1)).size;

  return (
    <section>
      <header className="hero">
        <Cover id={album.coverArt} size={600} className="hero__cover" />
        <div className="hero__text">
          <span className="eyebrow">Album</span>
          <h1>{album.name}</h1>
          <p>
            {album.artistId ? <a href={href({ name: "artist", id: album.artistId })}>{album.artist}</a> : album.artist}
            {album.year ? ` · ${album.year}` : ""} · {plural(songs.length, "brano", "brani")}
            {album.duration ? ` · ${formatTotal(album.duration)}` : ""}
          </p>
          <div className="actions">
            <button className="btn btn--primary" onClick={() => player.play(songs)}>
              <Icon name="play" size={20} /> Riproduci
            </button>
            <button className="btn" onClick={() => player.play(songs, Math.floor(Math.random() * songs.length), true)}>
              <Icon name="shuffle" size={20} /> Casuale
            </button>
            <button className="btn" onClick={() => player.enqueue(songs)} title="Aggiungi alla coda">
              <Icon name="addQueue" size={20} />
            </button>
          </div>
        </div>
      </header>
      {discs > 1 ? (
        [...new Set(songs.map((s) => s.discNumber ?? 1))].map((d) => (
          <div key={d}>
            <h3 className="disc">Disco {d}</h3>
            <TrackList songs={songs.filter((s) => (s.discNumber ?? 1) === d)} />
          </div>
        ))
      ) : (
        <TrackList songs={songs} />
      )}
    </section>
  );
}
