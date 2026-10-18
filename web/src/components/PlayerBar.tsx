import { current } from "../lib/queue";
import { player, usePlayer } from "../lib/player";
import { Cover } from "./Cover";
import { Icon } from "./Icon";
import { Seek } from "./Seek";

/** Barra in basso: compatta su telefono, completa su PC e tablet. */
export function PlayerBar({ onOpen }: { onOpen: () => void }) {
  const s = usePlayer();
  const song = current(s.queue);
  if (!song) return null;
  return (
    <footer className="bar">
      <Seek position={s.position} duration={s.duration} compact />
      <div className="bar__inner">
        <button className="bar__song" onClick={onOpen} aria-label="Apri il player">
          <Cover id={song.coverArt} size={96} className="bar__cover" />
          <span className="bar__text">
            <span className="bar__title">{song.title}</span>
            <span className="bar__artist">{song.artist}</span>
          </span>
        </button>
        <div className="bar__controls">
          <button className={`icon-btn wide-only ${s.queue.unshuffled ? "is-on" : ""}`} onClick={player.toggleShuffle} aria-label="Casuale" aria-pressed={!!s.queue.unshuffled}>
            <Icon name="shuffle" size={20} />
          </button>
          <button className="icon-btn wide-only" onClick={player.prev} aria-label="Precedente">
            <Icon name="prev" />
          </button>
          <button className="play-btn" onClick={player.toggle} aria-label={s.playing ? "Pausa" : "Riproduci"}>
            {s.buffering ? <span className="spinner" /> : <Icon name={s.playing ? "pause" : "play"} />}
          </button>
          <button className="icon-btn" onClick={player.next} aria-label="Successivo">
            <Icon name="next" />
          </button>
          <button className={`icon-btn wide-only ${s.queue.repeat !== "off" ? "is-on" : ""}`} onClick={player.cycleRepeat} aria-label={`Ripeti: ${s.queue.repeat}`}>
            <Icon name={s.queue.repeat === "one" ? "repeatOne" : "repeat"} size={20} />
          </button>
        </div>
        <div className="bar__extra wide-only">
          <a className="icon-btn" href="#/queue" aria-label="Coda">
            <Icon name="queue" size={20} />
          </a>
          <button className="icon-btn" onClick={() => player.setVolume(s.volume > 0 ? 0 : 1)} aria-label="Volume">
            <Icon name={s.volume === 0 ? "mute" : "volume"} size={20} />
          </button>
          <input className="volume" type="range" min={0} max={1} step={0.01} value={s.volume} onChange={(e) => player.setVolume(Number(e.target.value))} aria-label="Volume" style={{ "--pct": `${s.volume * 100}%` } as React.CSSProperties} />
        </div>
      </div>
      {s.error && <div className="bar__error">{s.error}</div>}
    </footer>
  );
}
