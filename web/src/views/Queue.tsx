import { Cover } from "../components/Cover";
import { Icon } from "../components/Icon";
import { formatDuration } from "../lib/format";
import { player, usePlayer } from "../lib/player";

export function Queue() {
  const { queue } = usePlayer();
  return (
    <section>
      <header className="page-head">
        <h1>Coda</h1>
        {queue.entries.length > 0 && (
          <button className="btn" onClick={player.clear}>
            <Icon name="trash" size={20} /> Svuota
          </button>
        )}
      </header>
      {queue.entries.length === 0 ? (
        <p className="muted">La coda è vuota.</p>
      ) : (
        <ol className="tracks">
          {queue.entries.map((e, i) => (
            <li key={e.uid} className={`track ${i === queue.index ? "track--active" : ""} ${i < queue.index ? "track--past" : ""}`}>
              <button className="track__main" onClick={() => player.jumpTo(i)}>
                <Cover id={e.song.coverArt} size={96} className="track__cover" />
                <span className="track__text">
                  <span className="track__title">{e.song.title}</span>
                  <span className="track__sub">{e.song.artist}</span>
                </span>
              </button>
              <span className="track__dur">{formatDuration(e.song.duration)}</span>
              <button className="icon-btn" disabled={i === 0} onClick={() => player.move(i, i - 1)} aria-label="Sposta su">
                <Icon name="down" size={20} className="flip" />
              </button>
              <button className="icon-btn" onClick={() => player.removeAt(i)} aria-label="Rimuovi dalla coda">
                <Icon name="close" size={20} />
              </button>
            </li>
          ))}
        </ol>
      )}
    </section>
  );
}
