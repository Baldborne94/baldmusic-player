import { AlbumRow, ArtistGrid } from "../components/AlbumGrid";
import { Icon } from "../components/Icon";
import { ErrorState, Loading } from "../components/States";
import { TrackList } from "../components/TrackList";
import { player } from "../lib/player";
import { getClient } from "../lib/session";
import { useLoad } from "../lib/useLoad";

export function Favorites() {
  const { data, error, loading, reload } = useLoad(() => getClient()!.starred(), []);
  if (loading && !data) return <Loading />;
  if (error) return <ErrorState message={error} onRetry={reload} />;
  const { song, album, artist } = data!;
  return (
    <section>
      <header className="page-head">
        <h1>Preferiti</h1>
        {song.length > 0 && (
          <button className="btn btn--primary" onClick={() => player.play(song, 0)}>
            <Icon name="play" size={20} /> Riproduci
          </button>
        )}
      </header>
      {song.length + album.length + artist.length === 0 && <p className="muted">Tocca il cuore accanto a un brano per ritrovarlo qui.</p>}
      {album.length > 0 && (
        <div className="shelf">
          <h2>Album</h2>
          <AlbumRow albums={album} />
        </div>
      )}
      {artist.length > 0 && (
        <div className="shelf">
          <h2>Artisti</h2>
          <ArtistGrid artists={artist} />
        </div>
      )}
      {song.length > 0 && (
        <div className="shelf">
          <h2>Brani</h2>
          <TrackList songs={song} showCover numbered={false} showAlbum />
        </div>
      )}
    </section>
  );
}
