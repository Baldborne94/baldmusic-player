import { useState } from "react";
import { Cover } from "../components/Cover";
import { Icon } from "../components/Icon";
import { ErrorState, Loading } from "../components/States";
import { AlbumCards, Hero, LikedArt, PlayButton, PlaylistPicker, startRadio, TrackTable, useCoverColor } from "../components/Ui";
import { tileColor } from "../lib/colors";
import { formatTotal, plural } from "../lib/format";
import { deletePlaylist, renamePlaylist, useLibrary } from "../lib/library";
import { player, usePlayer } from "../lib/player";
import { href, navigate } from "../lib/router";
import { getClient } from "../lib/session";
import type { Song } from "../lib/subsonic";
import { useLoad } from "../lib/useLoad";

const total = (songs: Song[]) => songs.reduce((t, s) => t + (s.duration ?? 0), 0);

/** La fascia sotto l'intestazione: play, casuale, aggiungi, menu. */
function ActionBar({ id, label, songs, extra }: { id: string; label: string; songs: Song[]; extra?: React.ReactNode }) {
  const { queue } = usePlayer();
  const [picker, setPicker] = useState(false);
  const shuffled = !!queue.unshuffled;
  return (
    <div className="action-bar">
      <PlayButton id={id} onPlay={() => player.play(songs, 0, shuffled, { label, id })} />
      <button
        className={`icon-btn icon-btn--lg ${shuffled ? "is-on" : ""}`}
        title="Riproduzione casuale"
        onClick={() => player.play(songs, Math.floor(Math.random() * songs.length), true, { label, id })}
      >
        <Icon name="shuffle" size={28} />
      </button>
      <div className="action-bar__menu">
        <button className="icon-btn icon-btn--lg" title="Aggiungi a una playlist" onClick={() => setPicker((p) => !p)}>
          <Icon name="plus" size={28} />
        </button>
        {picker && (
          <div className="ctx ctx--anchored">
            <PlaylistPicker songs={songs} onDone={() => setPicker(false)} />
          </div>
        )}
      </div>
      <button className="icon-btn icon-btn--lg" title="Aggiungi in coda" onClick={() => player.enqueue(songs)}>
        <Icon name="addQueue" size={26} />
      </button>
      {extra}
    </div>
  );
}

function Gradient({ color, children }: { color: string; children: React.ReactNode }) {
  return (
    <div className="page page--detail" style={{ "--hero": color } as React.CSSProperties}>
      {children}
    </div>
  );
}

export function AlbumPage({ id }: { id: string }) {
  const { data: album, error, loading, reload } = useLoad(() => getClient()!.album(id), [id]);
  const color = useCoverColor(album?.coverArt, album?.name ?? id);
  const more = useLoad(async () => (album?.artistId ? (await getClient()!.artist(album.artistId)).album ?? [] : []), [album?.artistId]);
  if (loading && !album) return <Loading />;
  if (error || !album) return <ErrorState message={error ?? "Album non trovato"} onRetry={reload} />;
  const songs = album.song ?? [];
  const discs = [...new Set(songs.map((s) => s.discNumber ?? 1))];
  return (
    <Gradient color={color}>
      <Hero
        color={color}
        art={<Cover id={album.coverArt} size={600} />}
        kind="Album"
        title={album.name}
        meta={
          <>
            {album.artistId ? <a href={href({ name: "artist", id: album.artistId })}><strong>{album.artist}</strong></a> : <strong>{album.artist}</strong>}
            {album.year ? ` • ${album.year}` : ""} • {plural(songs.length, "brano", "brani")}, {formatTotal(total(songs))}
          </>
        }
      />
      <ActionBar id={`album/${album.id}`} label={album.name} songs={songs} />
      {discs.length > 1 ? (
        discs.map((d) => (
          <div key={d}>
            <h3 className="disc"><Icon name="album" size={18} /> Disco {d}</h3>
            <TrackTable songs={songs.filter((s) => (s.discNumber ?? 1) === d)} from={{ label: album.name, id: `album/${album.id}` }} showAlbum={false} showCover={false} numbered />
          </div>
        ))
      ) : (
        <TrackTable songs={songs} from={{ label: album.name, id: `album/${album.id}` }} showAlbum={false} showCover={false} numbered />
      )}
      {album.genre && <p className="muted pad">Genere: <a href={href({ name: "genre", id: album.genre })}>{album.genre}</a></p>}
      {(more.data ?? []).filter((a) => a.id !== album.id).length > 0 && (
        <section className="shelf">
          <header className="shelf__head"><h2>Altro di {album.artist}</h2></header>
          <AlbumCards albums={(more.data ?? []).filter((a) => a.id !== album.id)} scroll />
        </section>
      )}
    </Gradient>
  );
}

export function ArtistPage({ id }: { id: string }) {
  const { data, error, loading, reload } = useLoad(async () => {
    const c = getClient()!;
    const artist = await c.artist(id);
    const top = await c.topSongs(artist.name, 10);
    return { artist, top };
  }, [id]);
  const color = useCoverColor(data?.artist.coverArt ?? `ar-${id}`, data?.artist.name ?? id);
  const [all, setAll] = useState(false);
  if (loading && !data) return <Loading />;
  if (error || !data) return <ErrorState message={error ?? "Artista non trovato"} onRetry={reload} />;
  const { artist, top } = data;
  const albums = [...(artist.album ?? [])].sort((a, b) => (b.year ?? 0) - (a.year ?? 0));
  const playAll = async (shuffle: boolean) => {
    const c = getClient()!;
    const full = await Promise.all(albums.map((a) => c.album(a.id)));
    const songs = full.flatMap((a) => a.song ?? []);
    player.play(songs, shuffle ? Math.floor(Math.random() * songs.length) : 0, shuffle, { label: artist.name, id: `artist/${artist.id}` });
  };
  return (
    <Gradient color={color}>
      <Hero color={color} round art={<Cover id={artist.coverArt ?? `ar-${artist.id}`} size={600} round />} kind="Artista" title={artist.name} meta={plural(albums.length, "album", "album")} />
      <div className="action-bar">
        <PlayButton id={`artist/${artist.id}`} onPlay={() => playAll(false)} />
        <button className="icon-btn icon-btn--lg" title="Casuale" onClick={() => playAll(true)}>
          <Icon name="shuffle" size={28} />
        </button>
        {top[0] && (
          <button className="btn" onClick={() => startRadio(top[0])}>
            <Icon name="radio" size={18} /> Radio
          </button>
        )}
      </div>
      {top.length > 0 && (
        <section className="shelf">
          <header className="shelf__head"><h2>Popolari</h2></header>
          <TrackTable songs={all ? top : top.slice(0, 5)} from={{ label: artist.name, id: `artist/${artist.id}` }} showAlbum={false} />
          {top.length > 5 && (
            <button className="shelf__more pad" onClick={() => setAll(!all)}>{all ? "Mostra meno" : "Altro"}</button>
          )}
        </section>
      )}
      <section className="shelf">
        <header className="shelf__head"><h2>Discografia</h2></header>
        <AlbumCards albums={albums} />
      </section>
    </Gradient>
  );
}

export function PlaylistPage({ id }: { id: string }) {
  const { data: pl, error, loading, reload } = useLoad(() => getClient()!.playlist(id), [id]);
  const lib = useLibrary();
  // Ricarica quando cambia la libreria (brano aggiunto o tolto).
  const version = lib.playlists.find((p) => p.id === id)?.songCount;
  const fresh = useLoad(() => getClient()!.playlist(id), [id, version]);
  const playlist = fresh.data ?? pl;
  const color = useCoverColor(playlist?.coverArt, playlist?.name ?? id);
  if (loading && !playlist) return <Loading />;
  if (error || !playlist) return <ErrorState message={error ?? "Playlist non trovata"} onRetry={reload} />;
  const songs = playlist.entry ?? [];
  const mine = lib.playlists.some((p) => p.id === id);
  return (
    <Gradient color={color}>
      <Hero
        color={color}
        art={songs.length ? <Mosaic songs={songs} /> : <div className="empty-art"><Icon name="music" size={64} /></div>}
        kind="Playlist"
        title={playlist.name}
        meta={<>{playlist.owner && <strong>{playlist.owner} • </strong>}{plural(songs.length, "brano", "brani")}, {formatTotal(total(songs))}</>}
      />
      <ActionBar
        id={`playlist/${playlist.id}`}
        label={playlist.name}
        songs={songs}
        extra={
          mine && (
            <>
              <button className="icon-btn icon-btn--lg" title="Rinomina" onClick={async () => { const n = prompt("Nuovo nome", playlist.name); if (n) await renamePlaylist(id, n); fresh.reload(); }}>
                <Icon name="pencil" size={22} />
              </button>
              <button className="icon-btn icon-btn--lg" title="Elimina playlist" onClick={async () => { if (confirm(`Eliminare “${playlist.name}”?`)) { await deletePlaylist(id); navigate({ name: "library" }); } }}>
                <Icon name="trash" size={22} />
              </button>
            </>
          )
        }
      />
      {songs.length === 0 ? (
        <p className="muted pad">La playlist è vuota. Aggiungi brani dal menu ⋯ di un brano o col tasto destro.</p>
      ) : (
        <TrackTable songs={songs} from={{ label: playlist.name, id: `playlist/${playlist.id}` }} playlistId={mine ? id : undefined} />
      )}
    </Gradient>
  );
}

/** Copertina a mosaico dei primi quattro album, come le playlist di Spotify. */
function Mosaic({ songs }: { songs: Song[] }) {
  const covers = [...new Set(songs.map((s) => s.coverArt).filter(Boolean))].slice(0, 4) as string[];
  if (covers.length < 4) return <Cover id={covers[0]} size={600} />;
  return (
    <div className="mosaic">
      {covers.map((c) => <Cover key={c} id={c} size={300} />)}
    </div>
  );
}

export function LikedPage() {
  const lib = useLibrary();
  const songs = lib.likedSongs;
  return (
    <Gradient color="#4a2db0">
      <Hero color="#4a2db0" art={<LikedArt />} kind="Playlist" title="Brani che ti piacciono" meta={plural(songs.length, "brano", "brani")} />
      <ActionBar id="liked" label="Brani che ti piacciono" songs={songs} />
      {!lib.loaded ? <Loading /> : songs.length === 0 ? <p className="muted pad">Tocca il cuore accanto a un brano per ritrovarlo qui.</p> : <TrackTable songs={songs} from={{ label: "Brani che ti piacciono", id: "liked" }} />}
    </Gradient>
  );
}

export function GenrePage({ id }: { id: string }) {
  const { data, error, loading } = useLoad(() => getClient()!.albumsByGenre(id, 100), [id]);
  const color = tileColor(id);
  return (
    <Gradient color={color}>
      <Hero color={color} art={<div className="empty-art" style={{ background: color }}><Icon name="music" size={64} /></div>} kind="Genere" title={id} meta={data ? plural(data.length, "album", "album") : ""} />
      <div className="action-bar">
        <PlayButton id={`mix/genre/${id}`} onPlay={async () => player.play(await getClient()!.randomSongs(80, { genre: id }), 0, false, { label: `Mix ${id}`, id: `mix/genre/${id}` })} />
        <span className="muted">Mix casuale di {id}</span>
      </div>
      {loading ? <Loading /> : error ? <ErrorState message={error} /> : <AlbumCards albums={data ?? []} />}
    </Gradient>
  );
}

export function DecadePage({ id }: { id: string }) {
  const year = Number(id);
  const { data, error, loading } = useLoad(() => getClient()!.albumsByYear(year, year + 9, 100), [id]);
  const color = tileColor(`decade${id}`);
  const title = `Anni ${year >= 2000 ? year : year % 100}`;
  return (
    <Gradient color={color}>
      <Hero color={color} art={<div className="empty-art" style={{ background: color }}><span className="empty-art__big">{year >= 2000 ? `'${String(year).slice(2)}` : `'${year % 100}`}</span></div>} kind="Decennio" title={title} meta={data ? plural(data.length, "album", "album") : ""} />
      <div className="action-bar">
        <PlayButton id={`mix/decade/${id}`} onPlay={async () => player.play(await getClient()!.randomSongs(80, { fromYear: year, toYear: year + 9 }), 0, false, { label: title, id: `mix/decade/${id}` })} />
        <span className="muted">Mix casuale degli {title.toLowerCase()}</span>
      </div>
      {loading ? <Loading /> : error ? <ErrorState message={error} /> : <AlbumCards albums={data ?? []} />}
    </Gradient>
  );
}

export function Stats() {
  const { data, error, loading } = useLoad(async () => {
    const c = getClient()!;
    const frequent = await c.albumList("frequent", 100);
    const plays = frequent.reduce((t, a) => t + (a.playCount ?? 0), 0);
    const byArtist = new Map<string, { name: string; id?: string; plays: number }>();
    for (const a of frequent) {
      const k = a.artistId ?? a.artist ?? "?";
      const e = byArtist.get(k) ?? { name: a.artist ?? "Sconosciuto", id: a.artistId, plays: 0 };
      e.plays += a.playCount ?? 0;
      byArtist.set(k, e);
    }
    const artists = [...byArtist.values()].sort((a, b) => b.plays - a.plays).slice(0, 10);
    const minutes = Math.round(frequent.reduce((t, a) => t + (a.playCount ?? 0) * ((a.duration ?? 0) / Math.max(a.songCount ?? 1, 1)), 0) / 60);
    return { frequent: frequent.slice(0, 10), plays, artists, minutes };
  }, []);
  if (loading) return <Loading />;
  if (error || !data) return <ErrorState message={error ?? "Statistiche non disponibili"} />;
  return (
    <div className="page page--stats">
      <h1>Le tue statistiche</h1>
      <div className="stats-hero">
        <span className="muted">Hai ascoltato circa</span>
        <span className="stats-hero__big">{data.minutes.toLocaleString("it-IT")} minuti</span>
        <span className="muted">{data.plays.toLocaleString("it-IT")} ascolti registrati da Navidrome</span>
      </div>
      {data.artists[0] && (
        <a className="stats-top" href={data.artists[0].id ? href({ name: "artist", id: data.artists[0].id }) : "#/"}>
          <Cover id={data.artists[0].id ? `ar-${data.artists[0].id}` : undefined} size={300} round className="stats-top__art" />
          <span>
            <span className="muted">Il tuo artista n. 1</span>
            <span className="stats-top__name">{data.artists[0].name}</span>
            <span className="muted">{data.artists[0].plays} ascolti</span>
          </span>
        </a>
      )}
      <section className="shelf">
        <header className="shelf__head"><h2>I tuoi artisti</h2></header>
        <ol className="rank">
          {data.artists.map((a, i) => (
            <li key={a.name}>
              <span className="rank__n">{i + 1}</span>
              <Cover id={a.id ? `ar-${a.id}` : undefined} size={96} round className="rank__art" />
              <span className="rank__name">{a.id ? <a href={href({ name: "artist", id: a.id })}>{a.name}</a> : a.name}</span>
              <span className="muted">{a.plays} ascolti</span>
            </li>
          ))}
        </ol>
      </section>
      <section className="shelf">
        <header className="shelf__head"><h2>I tuoi album</h2></header>
        <AlbumCards albums={data.frequent} scroll />
      </section>
    </div>
  );
}
