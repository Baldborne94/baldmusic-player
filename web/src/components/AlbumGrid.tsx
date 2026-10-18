import type { Album, Artist } from "../lib/subsonic";
import { href } from "../lib/router";
import { Cover } from "./Cover";

export function AlbumGrid({ albums, showArtist = true }: { albums: Album[]; showArtist?: boolean }) {
  if (albums.length === 0) return <p className="muted">Nessun album.</p>;
  return (
    <div className="grid">
      {albums.map((a) => (
        <a key={a.id} className="card" href={href({ name: "album", id: a.id })}>
          <Cover id={a.coverArt} alt="" />
          <span className="card__title">{a.name}</span>
          <span className="card__sub">{showArtist ? a.artist : a.year ?? ""}</span>
        </a>
      ))}
    </div>
  );
}

export function AlbumRow({ albums }: { albums: Album[] }) {
  return (
    <div className="row-scroll">
      {albums.map((a) => (
        <a key={a.id} className="card card--row" href={href({ name: "album", id: a.id })}>
          <Cover id={a.coverArt} alt="" />
          <span className="card__title">{a.name}</span>
          <span className="card__sub">{a.artist}</span>
        </a>
      ))}
    </div>
  );
}

export function ArtistGrid({ artists }: { artists: Artist[] }) {
  if (artists.length === 0) return <p className="muted">Nessun artista.</p>;
  return (
    <div className="grid grid--artists">
      {artists.map((a) => (
        <a key={a.id} className="card card--artist" href={href({ name: "artist", id: a.id })}>
          <Cover id={a.coverArt ?? `ar-${a.id}`} round alt="" />
          <span className="card__title">{a.name}</span>
          {a.albumCount !== undefined && <span className="card__sub">{a.albumCount === 1 ? "1 album" : `${a.albumCount} album`}</span>}
        </a>
      ))}
    </div>
  );
}
