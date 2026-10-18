import { AlbumGrid } from "../components/AlbumGrid";
import { Cover } from "../components/Cover";
import { Icon } from "../components/Icon";
import { ErrorState, Loading } from "../components/States";
import { player } from "../lib/player";
import { getClient } from "../lib/session";
import type { Song } from "../lib/subsonic";
import { useLoad } from "../lib/useLoad";

async function allSongs(albumIds: string[]): Promise<Song[]> {
  const c = getClient()!;
  const albums = await Promise.all(albumIds.map((id) => c.album(id)));
  return albums.flatMap((a) => a.song ?? []);
}

export function ArtistView({ id }: { id: string }) {
  const { data: artist, error, loading, reload } = useLoad(() => getClient()!.artist(id), [id]);
  if (loading && !artist) return <Loading />;
  if (error || !artist) return <ErrorState message={error ?? "Artista non trovato"} onRetry={reload} />;
  const albums = [...(artist.album ?? [])].sort((a, b) => (b.year ?? 0) - (a.year ?? 0));
  const ids = albums.map((a) => a.id);

  return (
    <section>
      <header className="hero">
        <Cover id={artist.coverArt ?? `ar-${artist.id}`} size={600} round className="hero__cover" />
        <div className="hero__text">
          <span className="eyebrow">Artista</span>
          <h1>{artist.name}</h1>
          <p>{albums.length === 1 ? "1 album" : `${albums.length} album`}</p>
          <div className="actions">
            <button className="btn btn--primary" onClick={async () => player.play(await allSongs(ids))}>
              <Icon name="play" size={20} /> Riproduci tutto
            </button>
            <button className="btn" onClick={async () => player.play(await allSongs(ids), 0, true)}>
              <Icon name="shuffle" size={20} /> Casuale
            </button>
          </div>
        </div>
      </header>
      <AlbumGrid albums={albums} showArtist={false} />
    </section>
  );
}
