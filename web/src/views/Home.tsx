import { Cover } from "../components/Cover";
import { Icon } from "../components/Icon";
import { ErrorState, Loading } from "../components/States";
import { AlbumCards, ArtistCards, PlayButton, PlaylistCards, Shelf, LikedArt } from "../components/Ui";
import { tileColor } from "../lib/colors";
import { useLibrary } from "../lib/library";
import { player } from "../lib/player";
import { href } from "../lib/router";
import { getClient } from "../lib/session";
import type { Album, Artist } from "../lib/subsonic";
import { useLoad } from "../lib/useLoad";

function greeting(): string {
  const h = new Date().getHours();
  if (h >= 5 && h < 13) return "Buongiorno";
  if (h >= 13 && h < 18) return "Buon pomeriggio";
  return "Buonasera";
}

export interface Mix {
  id: string;
  title: string;
  subtitle: string;
  load: () => Promise<import("../lib/subsonic").Song[]>;
}

const DECADES = [1960, 1970, 1980, 1990, 2000, 2010, 2020];

export function MixCard({ mix }: { mix: Mix }) {
  const color = tileColor(mix.id);
  return (
    <div className="mix">
      <div className="mix__art" style={{ background: `linear-gradient(135deg, ${color}, color-mix(in srgb, ${color} 45%, black))` }}>
        <span className="mix__title">{mix.title}</span>
        <span className="mix__play">
          <PlayButton id={mix.id} size={46} onPlay={async () => player.play(await mix.load(), 0, false, { label: mix.title, id: mix.id })} />
        </span>
      </div>
      <span className="card__sub">{mix.subtitle}</span>
    </div>
  );
}

export function Home() {
  const lib = useLibrary();
  const { data, error, loading, reload } = useLoad(async () => {
    const c = getClient()!;
    const [recent, newest, frequent, random, genres] = await Promise.all([
      c.albumList("recent", 20),
      c.albumList("newest", 20),
      c.albumList("frequent", 30),
      c.albumList("random", 20),
      c.genres().catch(() => []),
    ]);
    // I tuoi artisti: quelli degli album che ascolti di più.
    const artists = new Map<string, Artist>();
    for (const a of frequent) {
      if (a.artistId && !artists.has(a.artistId)) artists.set(a.artistId, { id: a.artistId, name: a.artist ?? "", coverArt: `ar-${a.artistId}` });
    }
    return { recent, newest, frequent, random, genres, artists: [...artists.values()].slice(0, 12) };
  }, []);

  if (loading && !data) return <Loading />;
  if (error) return <ErrorState message={error} onRetry={reload} />;
  const d = data!;
  const quick: (Album | "liked")[] = [...(lib.likedSongs.length ? ["liked" as const] : []), ...d.recent].slice(0, 8);

  const c = getClient()!;
  const mixes: Mix[] = [
    { id: "mix/random", title: "Mix casuale", subtitle: "Tutta la tua musica, mescolata", load: () => c.randomSongs(80) },
    ...(lib.likedSongs.length ? [{ id: "mix/liked", title: "Preferiti in shuffle", subtitle: "I brani che ti piacciono", load: async () => [...lib.likedSongs].sort(() => Math.random() - 0.5) }] : []),
    ...d.genres.slice(0, 4).map((g) => ({ id: `mix/genre/${g.value}`, title: `Mix ${g.value}`, subtitle: `${g.songCount ?? ""} brani di ${g.value}`, load: () => c.randomSongs(60, { genre: g.value }) })),
    ...DECADES.map((y) => ({ id: `mix/decade/${y}`, title: `Anni ${y >= 2000 ? y : y % 100}`, subtitle: `Musica dal ${y} al ${y + 9}`, load: () => c.randomSongs(60, { fromYear: y, toYear: y + 9 }) })),
  ];

  return (
    <div className="page page--home">
      <div className="home-head">
        <h1>{greeting()}</h1>
      </div>
      {quick.length > 0 && (
        <div className="quick">
          {quick.map((q) =>
            q === "liked" ? (
              <a key="liked" className="quick__tile" href={href({ name: "liked" })}>
                <span className="quick__art">
                  <LikedArt />
                </span>
                <span className="quick__title">Brani che ti piacciono</span>
                <span className="quick__play">
                  <PlayButton id="liked" size={40} onPlay={() => player.play(lib.likedSongs, 0, false, { label: "Brani che ti piacciono", id: "liked" })} />
                </span>
              </a>
            ) : (
              <a key={q.id} className="quick__tile" href={href({ name: "album", id: q.id })}>
                <span className="quick__art">
                  <Cover id={q.coverArt} size={120} />
                </span>
                <span className="quick__title">{q.name}</span>
                <span className="quick__play" onClick={(e) => e.preventDefault()}>
                  <PlayButton
                    id={`album/${q.id}`}
                    size={40}
                    onPlay={async () => {
                      const full = await c.album(q.id);
                      player.play(full.song ?? [], 0, false, { label: q.name, id: `album/${q.id}` });
                    }}
                  />
                </span>
              </a>
            ),
          )}
        </div>
      )}

      <Shelf title="I tuoi mix">
        <div className="row-scroll">
          {mixes.map((m) => (
            <MixCard key={m.id} mix={m} />
          ))}
        </div>
      </Shelf>
      {d.recent.length > 0 && (
        <Shelf title="Ascoltati di recente">
          <AlbumCards albums={d.recent} scroll />
        </Shelf>
      )}
      {lib.playlists.length > 0 && (
        <Shelf title="Le tue playlist" more={href({ name: "playlists" })}>
          <PlaylistCards playlists={lib.playlists.slice(0, 12)} scroll />
        </Shelf>
      )}
      {d.newest.length > 0 && (
        <Shelf title="Aggiunti di recente" more={href({ name: "albums" })}>
          <AlbumCards albums={d.newest} scroll />
        </Shelf>
      )}
      {d.frequent.length > 0 && (
        <Shelf title="I più ascoltati">
          <AlbumCards albums={d.frequent.slice(0, 20)} scroll />
        </Shelf>
      )}
      {d.artists.length > 0 && (
        <Shelf title="I tuoi artisti" more={href({ name: "artists" })}>
          <ArtistCards artists={d.artists} scroll />
        </Shelf>
      )}
      {d.random.length > 0 && (
        <Shelf title="Da riscoprire">
          <AlbumCards albums={d.random} scroll />
        </Shelf>
      )}
      {d.recent.length + d.newest.length === 0 && (
        <p className="muted pad">
          <Icon name="music" size={18} /> La libreria è vuota: metti la musica nella cartella di Navidrome e aspetta la scansione.
        </p>
      )}
    </div>
  );
}
