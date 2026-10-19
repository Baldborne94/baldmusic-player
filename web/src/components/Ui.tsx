import { Fragment, useEffect, useRef, useState, type ReactNode } from "react";
import { dominantColor, tileColor } from "../lib/colors";
import { formatDuration } from "../lib/format";
import { addToPlaylist, createPlaylist, isCollection, removeFromPlaylist, toggleLike, useLibrary } from "../lib/library";
import { player, usePlayer } from "../lib/player";
import { current } from "../lib/queue";
import { href, navigate } from "../lib/router";
import { getClient, useClient } from "../lib/session";
import type { Album, Artist, Playlist, Song } from "../lib/subsonic";
import { Cover } from "./Cover";
import { Icon } from "./Icon";

/** Il colore dominante di una copertina (o uno stabile dal nome, se non si può leggere). */
export function useCoverColor(coverId: string | undefined, fallbackKey: string): string {
  const client = useClient();
  const [color, setColor] = useState(() => tileColor(fallbackKey));
  useEffect(() => {
    let alive = true;
    setColor(tileColor(fallbackKey));
    void dominantColor(client?.coverUrl(coverId, 64)).then((c) => alive && c && setColor(c));
    return () => {
      alive = false;
    };
  }, [client, coverId, fallbackKey]);
  return color;
}

/** Il grande tasto play delle pagine: diventa pausa se quella pagina sta suonando. */
export function PlayButton({ id, onPlay, size = 56 }: { id: string; onPlay: () => void; size?: number }) {
  const { from, playing } = usePlayer();
  const here = from?.id === id;
  return (
    <button
      className="big-play"
      style={{ width: size, height: size }}
      onClick={() => (here ? player.toggle() : onPlay())}
      aria-label={here && playing ? "Pausa" : "Riproduci"}
    >
      <Icon name={here && playing ? "pause" : "play"} size={size * 0.5} />
    </button>
  );
}

export function Shelf({ title, more, children }: { title: string; more?: string; children: ReactNode }) {
  return (
    <section className="shelf">
      <header className="shelf__head">
        <h2>{title}</h2>
        {more && (
          <a className="shelf__more" href={more}>
            Mostra tutto
          </a>
        )}
      </header>
      {children}
    </section>
  );
}

/** Una scheda con copertina e tasto play che compare al passaggio del mouse. */
export function Card({ to, cover, title, subtitle, round, onPlay, playId }: { to: string; cover?: string; title: string; subtitle?: string; round?: boolean; onPlay?: () => void; playId?: string }) {
  return (
    <a className={`card ${round ? "card--round" : ""}`} href={to}>
      <div className="card__art">
        <Cover id={cover} round={round} />
        {onPlay && playId && (
          <span
            className="card__play"
            onClick={(e) => {
              e.preventDefault();
              e.stopPropagation();
            }}
          >
            <PlayButton id={playId} onPlay={onPlay} size={46} />
          </span>
        )}
      </div>
      <span className="card__title">{title}</span>
      {subtitle && <span className="card__sub">{subtitle}</span>}
    </a>
  );
}

export function AlbumCards({ albums, scroll = false }: { albums: Album[]; scroll?: boolean }) {
  return (
    <div className={scroll ? "row-scroll" : "grid"}>
      {albums.map((a) => (
        <Card
          key={a.id}
          to={href({ name: "album", id: a.id })}
          cover={a.coverArt}
          title={a.name}
          subtitle={[a.year, a.artist].filter(Boolean).join(" · ")}
          playId={`album/${a.id}`}
          onPlay={async () => {
            const full = await getClient()!.album(a.id);
            player.play(full.song ?? [], 0, false, { label: a.name, id: `album/${a.id}` });
          }}
        />
      ))}
    </div>
  );
}

export function ArtistCards({ artists, scroll = false }: { artists: Artist[]; scroll?: boolean }) {
  return (
    <div className={scroll ? "row-scroll" : "grid"}>
      {artists.map((a) => (
        <Card key={a.id} to={href({ name: "artist", id: a.id })} cover={a.coverArt ?? `ar-${a.id}`} title={a.name} subtitle="Artista" round />
      ))}
    </div>
  );
}

export function PlaylistCards({ playlists, scroll = false, bare = false }: { playlists: Playlist[]; scroll?: boolean; bare?: boolean }) {
  const cards = playlists.map((p) => (
        <Card
          key={p.id}
          to={href({ name: "playlist", id: p.id })}
          cover={p.coverArt}
          title={p.name}
          subtitle={`${isCollection(p) ? "Raccolta" : "Playlist"} · ${p.songCount ?? 0} brani`}
          playId={`playlist/${p.id}`}
          onPlay={async () => {
            const full = await getClient()!.playlist(p.id);
            player.play(full.entry ?? [], 0, false, { label: p.name, id: `playlist/${p.id}` });
          }}
        />
  ));
  return bare ? <>{cards}</> : <div className={scroll ? "row-scroll" : "grid"}>{cards}</div>;
}

/** Menu contestuale di un brano: si apre col ⋯ o col tasto destro. */
export function SongMenu({ song, x, y, playlistId, index, onClose }: { song: Song; x: number; y: number; playlistId?: string; index?: number; onClose: () => void }) {
  const { liked } = useLibrary();
  const [picker, setPicker] = useState(false);
  const [info, setInfo] = useState(false);
  const ref = useRef<HTMLDivElement>(null);
  // Con le informazioni aperte, i clic fuori dal menu non lo chiudono: li gestisce la finestra.
  const infoOpen = useRef(false);
  infoOpen.current = info;
  useEffect(() => {
    const close = (e: MouseEvent) => !infoOpen.current && !ref.current?.contains(e.target as Node) && onClose();
    const esc = (e: KeyboardEvent) => e.key === "Escape" && onClose();
    setTimeout(() => window.addEventListener("mousedown", close), 0);
    window.addEventListener("keydown", esc);
    return () => {
      window.removeEventListener("mousedown", close);
      window.removeEventListener("keydown", esc);
    };
  }, [onClose]);

  const left = Math.min(x, window.innerWidth - 260);
  const top = Math.min(y, window.innerHeight - 380);
  const act = (f: () => void) => () => {
    f();
    onClose();
  };
  const isLiked = liked.has(song.id);

  if (info) return <SongInfo song={song} onClose={onClose} />;

  return (
    <div className="ctx" ref={ref} style={{ left, top }}>
      {picker ? (
        <PlaylistPicker songs={[song]} onDone={onClose} />
      ) : (
        <>
          <button onClick={act(() => player.playNext([song]))}>
            <Icon name="playNext" size={18} /> Riproduci dopo
          </button>
          <button onClick={act(() => player.enqueue([song]))}>
            <Icon name="addQueue" size={18} /> Aggiungi in coda
          </button>
          <hr />
          <button onClick={act(() => toggleLike(song))}>
            <Icon name={isLiked ? "heart" : "heartOutline"} size={18} /> {isLiked ? "Togli da Brani che ti piacciono" : "Aggiungi a Brani che ti piacciono"}
          </button>
          <button onClick={() => setPicker(true)}>
            <Icon name="plus" size={18} /> Aggiungi a playlist
          </button>
          {playlistId && index !== undefined && (
            <button onClick={act(() => removeFromPlaylist(playlistId, index))}>
              <Icon name="trash" size={18} /> Rimuovi da questa playlist
            </button>
          )}
          <hr />
          <button onClick={act(() => startRadio(song))}>
            <Icon name="radio" size={18} /> Avvia radio
          </button>
          {song.albumId && (
            <button onClick={act(() => navigate({ name: "album", id: song.albumId! }))}>
              <Icon name="album" size={18} /> Vai all'album
            </button>
          )}
          {song.artistId && (
            <button onClick={act(() => navigate({ name: "artist", id: song.artistId! }))}>
              <Icon name="artist" size={18} /> Vai all'artista
            </button>
          )}
          <button onClick={act(() => void navigator.clipboard?.writeText(`${song.title} – ${song.artist ?? ""}`))}>
            <Icon name="share" size={18} /> Copia titolo e artista
          </button>
          <button onClick={() => setInfo(true)}>
            <Icon name="music" size={18} /> Informazioni sul brano
          </button>
        </>
      )}
    </div>
  );
}

/**
 * "Informazioni sul brano": i tag come li vede Navidrome. È il primo strumento per
 * capire perché un brano è finito tra gli sparsi o è senza volume uguale.
 */
export function SongInfo({ song, onClose }: { song: Song; onClose: () => void }) {
  useEffect(() => {
    const esc = (e: KeyboardEvent) => e.key === "Escape" && onClose();
    window.addEventListener("keydown", esc);
    return () => window.removeEventListener("keydown", esc);
  }, [onClose]);
  const rg = song.replayGain;
  const db = (v: number | undefined) => (v === undefined || v === null ? undefined : `${v > 0 ? "+" : ""}${v.toFixed(1)} dB`);
  const rows: [string, string | number | undefined][] = [
    ["Titolo", song.title],
    ["Artista", song.artist],
    ["Album", song.album],
    ["Anno", song.year || undefined],
    ["Traccia", song.track ? `${song.discNumber ? `${song.discNumber}-` : ""}${song.track}` : undefined],
    ["Genere", song.genre],
    ["Durata", formatDuration(song.duration)],
    ["Formato", [song.suffix?.toUpperCase(), song.bitRate ? `${song.bitRate} kbps` : undefined].filter(Boolean).join(" · ") || undefined],
    ["ReplayGain", [db(rg?.trackGain) && `brano ${db(rg?.trackGain)}`, db(rg?.albumGain) && `album ${db(rg?.albumGain)}`].filter(Boolean).join(" · ") || "nessuno: suona com'è"],
    ["MusicBrainz", song.musicBrainzId ? "riconosciuto" : "no (non passato da Picard?)"],
    ["File", song.path],
  ];
  return (
    <div className="modal" onMouseDown={(e) => e.target === e.currentTarget && onClose()}>
      <div className="modal__box" role="dialog" aria-label="Informazioni sul brano">
        <h2>Informazioni sul brano</h2>
        <dl className="info-list">
          {rows.filter(([, v]) => v !== undefined && v !== "").map(([k, v]) => (
            <Fragment key={k}>
              <dt>{k}</dt>
              <dd>{v}</dd>
            </Fragment>
          ))}
        </dl>
        <button className="btn" onClick={onClose}>Chiudi</button>
      </div>
    </div>
  );
}

export function PlaylistPicker({ songs, onDone }: { songs: Song[]; onDone: () => void }) {
  const { playlists } = useLibrary();
  const [name, setName] = useState("");
  return (
    <div className="picker">
      <form
        className="picker__new"
        onSubmit={async (e) => {
          e.preventDefault();
          await createPlaylist(name.trim() || "La mia playlist", songs);
          onDone();
        }}
      >
        <input value={name} onChange={(e) => setName(e.target.value)} placeholder="Nuova playlist…" autoFocus />
        <button className="icon-btn" aria-label="Crea">
          <Icon name="plus" size={18} />
        </button>
      </form>
      <div className="picker__list">
        {playlists.map((p) => (
          <button
            key={p.id}
            onClick={async () => {
              await addToPlaylist(p.id, songs);
              onDone();
            }}
          >
            {p.name}
          </button>
        ))}
      </div>
    </div>
  );
}

/**
 * Radio da un brano: i brani simili del server se li sa calcolare,
 * altrimenti i più ascoltati dell'artista e un mix dello stesso genere.
 */
export async function startRadio(song: Song): Promise<void> {
  const c = getClient();
  if (!c) return;
  let songs = await c.similarSongs(song.id, 50);
  if (songs.length < 5) {
    const [top, genre] = await Promise.all([song.artist ? c.topSongs(song.artist, 15) : [], c.randomSongs(35, song.genre ? { genre: song.genre } : {})]);
    const mix: Song[] = [];
    const a = [...top];
    const g = [...genre];
    while (a.length || g.length) {
      g.length && mix.push(g.shift()!);
      g.length && mix.push(g.shift()!);
      a.length && mix.push(a.shift()!);
    }
    songs = mix;
  }
  const unique = [song, ...songs.filter((s) => s.id !== song.id)].filter((s, i, arr) => arr.findIndex((x) => x.id === s.id) === i);
  player.play(unique, 0, false, { label: `Radio: ${song.title}`, id: `radio/${song.id}` });
}

/** Tabella dei brani, come nelle pagine di Spotify sul PC. */
/** Selezione di più brani: casella al posto del numero, Maiuscolo + clic per un intervallo. */
export interface TrackSelection {
  selected: Set<number>;
  toggle: (index: number, shift: boolean) => void;
}

export function TrackTable({
  songs,
  from,
  showAlbum = true,
  showCover = true,
  numbered = false,
  playlistId,
  selection,
  onMove,
}: {
  songs: Song[];
  from: { label: string; id: string };
  showAlbum?: boolean;
  showCover?: boolean;
  numbered?: boolean;
  playlistId?: string;
  selection?: TrackSelection;
  /** Riordino: si trascina la maniglia ≡ e al rilascio il brano va in quella posizione. */
  onMove?: (from: number, to: number) => void;
}) {
  const { queue, playing } = usePlayer();
  const { liked } = useLibrary();
  const now = current(queue);
  const [menu, setMenu] = useState<{ song: Song; index: number; x: number; y: number } | null>(null);
  const [dragFrom, setDragFrom] = useState<number | null>(null);
  const [dragOver, setDragOver] = useState<number | null>(null);
  if (songs.length === 0) return <p className="muted pad">Nessun brano.</p>;
  return (
    <div className={`tracks ${showAlbum ? "" : "tracks--noalbum"}`}>
      <div className="tracks__head">
        <span>#</span>
        <span>Titolo</span>
        {showAlbum && <span className="wide-only">Album</span>}
        <span />
        <span className="tracks__dur">
          <Icon name="clock" size={16} />
        </span>
        <span />
      </div>
      {songs.map((s, i) => {
        const active = now?.id === s.id;
        const isLiked = liked.has(s.id);
        return (
          <div
            key={`${s.id}-${i}`}
            className={`trow ${active ? "trow--active" : ""} ${selection?.selected.has(i) ? "trow--selected" : ""} ${dragOver === i && dragFrom !== i ? "trow--drop" : ""}`}
            draggable={!!onMove}
            onDragStart={(e) => {
              if (!onMove) return;
              setDragFrom(i);
              e.dataTransfer.effectAllowed = "move";
            }}
            onDragOver={(e) => {
              if (!onMove || dragFrom === null) return;
              e.preventDefault();
              setDragOver(i);
            }}
            onDrop={(e) => {
              e.preventDefault();
              if (onMove && dragFrom !== null && dragFrom !== i) onMove(dragFrom, i);
              setDragFrom(null);
              setDragOver(null);
            }}
            onDragEnd={() => {
              setDragFrom(null);
              setDragOver(null);
            }}
            onClick={(e) => {
              // Cuore, menu e link fanno il loro lavoro anche mentre si seleziona.
              if (!selection || (e.target as HTMLElement).closest("button, a")) return;
              e.preventDefault();
              selection.toggle(i, e.shiftKey);
            }}
            onDoubleClick={() => !selection && !onMove && player.play(songs, i, false, from)}
            onContextMenu={(e) => {
              e.preventDefault();
              setMenu({ song: s, index: i, x: e.clientX, y: e.clientY });
            }}
          >
            {selection ? (
              <span className="trow__num trow__num--check">
                <input type="checkbox" checked={selection.selected.has(i)} readOnly aria-label={`Seleziona ${s.title}`} />
              </span>
            ) : onMove ? (
              <span className="trow__num trow__num--handle" title="Trascina per spostare">≡</span>
            ) : (
            <span className="trow__num">
              <span className="trow__index">{active && playing ? <span className="eq"><i /><i /><i /></span> : numbered ? s.track ?? i + 1 : i + 1}</span>
              <button className="trow__playbtn" onClick={() => (active ? player.toggle() : player.play(songs, i, false, from))} aria-label="Riproduci">
                <Icon name={active && playing ? "pause" : "play"} size={16} />
              </button>
            </span>
            )}
            <span className="trow__main" onClick={() => !selection && !onMove && player.play(songs, i, false, from)}>
              {showCover && <Cover id={s.coverArt} size={96} className="trow__cover" />}
              <span className="trow__text">
                <span className="trow__title">{s.title}</span>
                <span className="trow__artist">
                  {s.artistId ? (
                    <a href={href({ name: "artist", id: s.artistId })} onClick={(e) => e.stopPropagation()}>
                      {s.artist}
                    </a>
                  ) : (
                    s.artist
                  )}
                </span>
              </span>
            </span>
            {showAlbum && (
              <span className="trow__album wide-only">{s.albumId ? <a href={href({ name: "album", id: s.albumId })}>{s.album}</a> : s.album}</span>
            )}
            <button className={`icon-btn trow__heart ${isLiked ? "is-on" : ""}`} onClick={() => toggleLike(s)} aria-label={isLiked ? "Togli da Mi piace" : "Mi piace"}>
              <Icon name={isLiked ? "heart" : "heartOutline"} size={18} />
            </button>
            <span className="trow__dur">{formatDuration(s.duration)}</span>
            <button className="icon-btn trow__more" onClick={(e) => setMenu({ song: s, index: i, x: e.clientX, y: e.clientY })} aria-label="Altre azioni">
              <Icon name="more" size={18} />
            </button>
          </div>
        );
      })}
      {menu && <SongMenu song={menu.song} x={menu.x} y={menu.y} playlistId={playlistId} index={menu.index} onClose={() => setMenu(null)} />}
    </div>
  );
}

/** Intestazione colorata di album, artisti e playlist. */
export function Hero({ color, art, kind, title, meta, round }: { color: string; art: ReactNode; kind: string; title: string; meta: ReactNode; round?: boolean }) {
  return (
    <header className="hero" style={{ "--hero": color } as React.CSSProperties}>
      <div className={`hero__art ${round ? "hero__art--round" : ""}`}>{art}</div>
      <div className="hero__text">
        <span className="hero__kind">{kind}</span>
        <h1 className={title.length > 28 ? "hero__title hero__title--long" : "hero__title"}>{title}</h1>
        <div className="hero__meta">{meta}</div>
      </div>
    </header>
  );
}

export function LikedArt() {
  return (
    <div className="liked-art">
      <Icon name="heart" size={64} />
    </div>
  );
}
