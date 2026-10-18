import { useEffect } from "react";
import { current } from "../lib/queue";
import { player, usePlayer } from "../lib/player";
import { href } from "../lib/router";
import { useClient } from "../lib/session";
import { Cover } from "./Cover";
import { Icon } from "./Icon";
import { Seek } from "./Seek";

/** Player a schermo intero (soprattutto per il telefono). */
export function NowPlaying({ onClose }: { onClose: () => void }) {
  const s = usePlayer();
  const song = current(s.queue);
  const bg = useClient()?.coverUrl(song?.coverArt, 300);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && onClose();
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);

  useEffect(() => {
    if (!song) onClose();
  }, [song, onClose]);

  if (!song) return null;
  return (
    <div className="np" role="dialog" aria-label="In riproduzione">
      {bg && <div className="np__bg" style={{ backgroundImage: `url("${bg}")` }} />}
      <header className="np__head">
        <button className="icon-btn" onClick={onClose} aria-label="Chiudi">
          <Icon name="down" size={28} />
        </button>
        <span className="np__from">In riproduzione</span>
        <a className="icon-btn" href="#/queue" onClick={onClose} aria-label="Coda">
          <Icon name="queue" />
        </a>
      </header>
      <Cover id={song.coverArt} size={800} className="np__cover" />
      <div className="np__meta">
        <h2 className="np__title">{song.title}</h2>
        <p className="np__artist">
          {song.artistId ? (
            <a href={href({ name: "artist", id: song.artistId })} onClick={onClose}>
              {song.artist}
            </a>
          ) : (
            song.artist
          )}
          {song.album && song.albumId && (
            <>
              {" · "}
              <a href={href({ name: "album", id: song.albumId })} onClick={onClose}>
                {song.album}
              </a>
            </>
          )}
        </p>
      </div>
      <Seek position={s.position} duration={s.duration} />
      <div className="np__controls">
        <button className={`icon-btn ${s.queue.unshuffled ? "is-on" : ""}`} onClick={player.toggleShuffle} aria-label="Casuale" aria-pressed={!!s.queue.unshuffled}>
          <Icon name="shuffle" />
        </button>
        <button className="icon-btn icon-btn--lg" onClick={player.prev} aria-label="Precedente">
          <Icon name="prev" size={36} />
        </button>
        <button className="play-btn play-btn--lg" onClick={player.toggle} aria-label={s.playing ? "Pausa" : "Riproduci"}>
          {s.buffering ? <span className="spinner" /> : <Icon name={s.playing ? "pause" : "play"} size={36} />}
        </button>
        <button className="icon-btn icon-btn--lg" onClick={player.next} aria-label="Successivo">
          <Icon name="next" size={36} />
        </button>
        <button className={`icon-btn ${s.queue.repeat !== "off" ? "is-on" : ""}`} onClick={player.cycleRepeat} aria-label={`Ripeti: ${s.queue.repeat}`}>
          <Icon name={s.queue.repeat === "one" ? "repeatOne" : "repeat"} />
        </button>
      </div>
      {s.error && <p className="np__error">{s.error}</p>}
    </div>
  );
}
