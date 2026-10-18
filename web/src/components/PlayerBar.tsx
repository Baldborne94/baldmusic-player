import { useState } from "react";
import { toggleLike, useLibrary } from "../lib/library";
import { player, usePlayer } from "../lib/player";
import { current } from "../lib/queue";
import { href } from "../lib/router";
import { Cover } from "./Cover";
import { Icon } from "./Icon";
import { Seek } from "./Seek";
import { useCoverColor } from "./Ui";

export type Panel = "now" | "lyrics" | "queue" | null;

const SPEEDS = [0.75, 1, 1.25, 1.5, 2];
const SLEEPS: [number | null, string][] = [[15, "15 minuti"], [30, "30 minuti"], [45, "45 minuti"], [60, "1 ora"], [0, "Fine del brano"], [null, "Spento"]];

/** La barra in basso, come in Spotify sul PC. */
export function PlayerBar({ panel, setPanel, onExpand }: { panel: Panel; setPanel: (p: Panel) => void; onExpand: () => void }) {
  const s = usePlayer();
  const { liked } = useLibrary();
  const song = current(s.queue);
  const [menu, setMenu] = useState<"speed" | "sleep" | null>(null);
  const color = useCoverColor(song?.coverArt, song?.album ?? "");
  if (!song) return <footer className="bar bar--empty" />;
  const isLiked = liked.has(song.id);
  const toggle = (p: Panel) => setPanel(panel === p ? null : p);

  return (
    <footer className="bar" style={{ "--np": color } as React.CSSProperties}>
      {/* Telefono: mini-player compatto che apre il player a schermo intero */}
      <button className="bar__mobile" onClick={onExpand} aria-label="Apri il player">
        <Cover id={song.coverArt} size={96} className="bar__cover" />
        <span className="bar__text">
          <span className="bar__title">{song.title}</span>
          <span className="bar__artist">{song.artist}</span>
        </span>
      </button>

      <div className="bar__song">
        <Cover id={song.coverArt} size={96} className="bar__cover" />
        <span className="bar__text">
          <a className="bar__title" href={song.albumId ? href({ name: "album", id: song.albumId }) : undefined}>{song.title}</a>
          <a className="bar__artist" href={song.artistId ? href({ name: "artist", id: song.artistId }) : undefined}>{song.artist}</a>
        </span>
        <button className={`icon-btn ${isLiked ? "is-on" : ""}`} onClick={() => toggleLike(song)} aria-label={isLiked ? "Togli da Mi piace" : "Mi piace"}>
          <Icon name={isLiked ? "heart" : "heartOutline"} size={18} />
        </button>
      </div>

      <div className="bar__center">
        <div className="bar__controls">
          <button className={`icon-btn wide-only ${s.queue.unshuffled ? "is-on dot" : ""}`} onClick={player.toggleShuffle} aria-label="Casuale" aria-pressed={!!s.queue.unshuffled}>
            <Icon name="shuffle" size={18} />
          </button>
          <button className="icon-btn wide-only" onClick={player.prev} aria-label="Precedente">
            <Icon name="prev" size={22} />
          </button>
          <button className="play-btn" onClick={player.toggle} aria-label={s.playing ? "Pausa" : "Riproduci"}>
            {s.buffering ? <span className="spinner" /> : <Icon name={s.playing ? "pause" : "play"} size={20} />}
          </button>
          <button className="icon-btn" onClick={player.next} aria-label="Successivo">
            <Icon name="next" size={22} />
          </button>
          <button className={`icon-btn wide-only ${s.queue.repeat !== "off" ? "is-on dot" : ""}`} onClick={player.cycleRepeat} aria-label={`Ripeti: ${s.queue.repeat}`}>
            <Icon name={s.queue.repeat === "one" ? "repeatOne" : "repeat"} size={18} />
          </button>
        </div>
        <Seek position={s.position} duration={s.duration} />
      </div>

      <div className="bar__extra">
        <div className="bar__pop">
          <button className={`icon-btn speed ${s.speed !== 1 ? "is-on" : ""}`} onClick={() => setMenu(menu === "speed" ? null : "speed")} title="Velocità">
            {s.speed}×
          </button>
          {menu === "speed" && (
            <div className="pop">
              {SPEEDS.map((v) => (
                <button key={v} className={v === s.speed ? "is-on" : ""} onClick={() => (player.setSpeed(v), setMenu(null))}>{v}×</button>
              ))}
            </div>
          )}
        </div>
        <div className="bar__pop">
          <button className={`icon-btn ${s.sleepAt !== null ? "is-on dot" : ""}`} onClick={() => setMenu(menu === "sleep" ? null : "sleep")} title="Timer di spegnimento">
            <Icon name="timer" size={18} />
          </button>
          {menu === "sleep" && (
            <div className="pop">
              <span className="pop__title">Timer di spegnimento</span>
              {SLEEPS.map(([v, l]) => (
                <button key={l} onClick={() => (player.setSleep(v), setMenu(null))}>{l}</button>
              ))}
            </div>
          )}
        </div>
        <button className={`icon-btn ${panel === "now" ? "is-on dot" : ""}`} onClick={() => toggle("now")} title="In riproduzione">
          <Icon name="music" size={18} />
        </button>
        <button className={`icon-btn ${panel === "lyrics" ? "is-on dot" : ""}`} onClick={() => toggle("lyrics")} title="Testo">
          <Icon name="mic" size={18} />
        </button>
        <button className={`icon-btn ${panel === "queue" ? "is-on dot" : ""}`} onClick={() => toggle("queue")} title="Coda">
          <Icon name="queue" size={18} />
        </button>
        <button className="icon-btn" onClick={() => player.setVolume(s.volume > 0 ? 0 : 1)} aria-label="Volume">
          <Icon name={s.volume === 0 ? "mute" : "volume"} size={18} />
        </button>
        <input className="volume" type="range" min={0} max={1} step={0.01} value={s.volume} onChange={(e) => player.setVolume(Number(e.target.value))} aria-label="Volume" style={{ "--pct": `${s.volume * 100}%` } as React.CSSProperties} />
        <button className="icon-btn" onClick={onExpand} title="Schermo intero">
          <Icon name="expand" size={18} />
        </button>
      </div>

      <div className="bar__mobile-controls">
        <button className={`icon-btn ${isLiked ? "is-on" : ""}`} onClick={() => toggleLike(song)} aria-label="Mi piace">
          <Icon name={isLiked ? "heart" : "heartOutline"} size={22} />
        </button>
        <button className="icon-btn" onClick={player.toggle} aria-label={s.playing ? "Pausa" : "Riproduci"}>
          <Icon name={s.playing ? "pause" : "play"} size={26} />
        </button>
      </div>
      <div className="bar__progress" style={{ width: `${s.duration ? (s.position / s.duration) * 100 : 0}%` }} />
      {s.error && <div className="bar__error">{s.error}</div>}
    </footer>
  );
}
