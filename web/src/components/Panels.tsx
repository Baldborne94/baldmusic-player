import { useEffect } from "react";
import { formatDuration } from "../lib/format";
import { toggleLike, useLibrary } from "../lib/library";
import { player, usePlayer } from "../lib/player";
import { current } from "../lib/queue";
import { href } from "../lib/router";
import { Cover } from "./Cover";
import { Icon } from "./Icon";
import { LyricsView } from "./Lyrics";
import type { Panel } from "./PlayerBar";
import { Seek } from "./Seek";
import { useCoverColor } from "./Ui";

/** Il pannello a destra: in riproduzione, testo o coda. */
export function RightPanel({ panel, onClose }: { panel: Exclude<Panel, null>; onClose: () => void }) {
  return (
    <aside className="panel">
      <header className="panel__head">
        <strong>{panel === "now" ? "In riproduzione" : panel === "lyrics" ? "Testo" : "Coda"}</strong>
        <button className="icon-btn" onClick={onClose} aria-label="Chiudi">
          <Icon name="close" size={18} />
        </button>
      </header>
      <div className="panel__body">{panel === "now" ? <NowCard /> : panel === "lyrics" ? <LyricsView /> : <QueueList />}</div>
    </aside>
  );
}

function NowCard() {
  const s = usePlayer();
  const { liked } = useLibrary();
  const song = current(s.queue);
  const next = s.queue.entries[s.queue.index + 1]?.song;
  if (!song) return null;
  return (
    <div className="now">
      <Cover id={song.coverArt} size={600} className="now__cover" />
      <div className="now__row">
        <div>
          <div className="now__title">{song.title}</div>
          <a className="now__artist" href={song.artistId ? href({ name: "artist", id: song.artistId }) : undefined}>{song.artist}</a>
        </div>
        <button className={`icon-btn ${liked.has(song.id) ? "is-on" : ""}`} onClick={() => toggleLike(song)}>
          <Icon name={liked.has(song.id) ? "heart" : "heartOutline"} size={22} />
        </button>
      </div>
      <div className="now__lyrics"><span className="now__label">Testo</span><LyricsView /></div>
      {next && (
        <div className="now__next">
          <span className="now__label">Prossimo in coda</span>
          <button className="qrow" onClick={() => player.next()}>
            <Cover id={next.coverArt} size={96} className="qrow__cover" />
            <span className="qrow__text"><span className="qrow__title">{next.title}</span><span className="qrow__sub">{next.artist}</span></span>
          </button>
        </div>
      )}
    </div>
  );
}

export function QueueList() {
  const { queue, from } = usePlayer();
  const now = queue.entries[queue.index];
  const upcoming = queue.entries.slice(queue.index + 1);
  return (
    <div className="queue">
      {now && (
        <>
          <h3>In riproduzione</h3>
          <div className="qrow qrow--active">
            <Cover id={now.song.coverArt} size={96} className="qrow__cover" />
            <span className="qrow__text"><span className="qrow__title">{now.song.title}</span><span className="qrow__sub">{now.song.artist}</span></span>
          </div>
        </>
      )}
      {upcoming.length > 0 && (
        <>
          <h3>
            Prossimi{from ? ` da: ${from.label}` : ""}
            <button className="link" onClick={() => { for (let i = queue.entries.length - 1; i > queue.index; i--) player.removeAt(i); }}>Svuota</button>
          </h3>
          {upcoming.map((e, k) => {
            const i = queue.index + 1 + k;
            return (
              <div
                key={e.uid}
                className="qrow"
                draggable
                onDragStart={(ev) => ev.dataTransfer.setData("text/plain", String(i))}
                onDragOver={(ev) => ev.preventDefault()}
                onDrop={(ev) => {
                  ev.preventDefault();
                  const from = Number(ev.dataTransfer.getData("text/plain"));
                  if (Number.isFinite(from) && from !== i) player.move(from, i);
                }}
                onDoubleClick={() => player.jumpTo(i)}
                title="Trascina per riordinare, doppio clic per ascoltare"
              >
                <Cover id={e.song.coverArt} size={96} className="qrow__cover" />
                <span className="qrow__text"><span className="qrow__title">{e.song.title}</span><span className="qrow__sub">{e.song.artist}</span></span>
                <span className="qrow__dur">{formatDuration(e.song.duration)}</span>
                <button className="icon-btn" onClick={() => player.removeAt(i)} aria-label="Togli dalla coda"><Icon name="close" size={16} /></button>
              </div>
            );
          })}
        </>
      )}
      {!now && <p className="muted">La coda è vuota.</p>}
    </div>
  );
}

/** Player a schermo intero: sul telefono è il player, sul PC la vista "immersiva". */
export function FullPlayer({ onClose }: { onClose: () => void }) {
  const s = usePlayer();
  const { liked } = useLibrary();
  const song = current(s.queue);
  const color = useCoverColor(song?.coverArt, song?.album ?? "");
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && onClose();
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);
  useEffect(() => {
    if (!song) onClose();
  }, [song, onClose]);
  if (!song) return null;
  const isLiked = liked.has(song.id);
  return (
    <div className="full" style={{ "--np": color } as React.CSSProperties} role="dialog" aria-label="In riproduzione">
      <header className="full__head">
        <button className="icon-btn" onClick={onClose} aria-label="Chiudi"><Icon name="down" size={30} /></button>
        <span className="full__from">
          <span>IN RIPRODUZIONE DA</span>
          <strong>{s.from?.label ?? song.album}</strong>
        </span>
        <span style={{ width: 40 }} />
      </header>
      <div className="full__body">
        <div className="full__art"><Cover id={song.coverArt} size={800} /></div>
        <div className="full__lyrics"><LyricsView big /></div>
      </div>
      <div className="full__meta">
        <div>
          <h2>{song.title}</h2>
          <p>{song.artist}</p>
        </div>
        <button className={`icon-btn ${isLiked ? "is-on" : ""}`} onClick={() => toggleLike(song)}><Icon name={isLiked ? "heart" : "heartOutline"} size={28} /></button>
      </div>
      <Seek position={s.position} duration={s.duration} />
      <div className="full__controls">
        <button className={`icon-btn ${s.queue.unshuffled ? "is-on" : ""}`} onClick={player.toggleShuffle}><Icon name="shuffle" /></button>
        <button className="icon-btn icon-btn--lg" onClick={player.prev}><Icon name="prev" size={36} /></button>
        <button className="play-btn play-btn--lg" onClick={player.toggle}>{s.buffering ? <span className="spinner" /> : <Icon name={s.playing ? "pause" : "play"} size={34} />}</button>
        <button className="icon-btn icon-btn--lg" onClick={player.next}><Icon name="next" size={36} /></button>
        <button className={`icon-btn ${s.queue.repeat !== "off" ? "is-on" : ""}`} onClick={player.cycleRepeat}><Icon name={s.queue.repeat === "one" ? "repeatOne" : "repeat"} /></button>
      </div>
    </div>
  );
}
