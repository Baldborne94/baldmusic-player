import { useState } from "react";
import { current } from "../lib/queue";
import { player, usePlayer } from "../lib/player";
import { formatDuration } from "../lib/format";
import { href } from "../lib/router";
import type { Song } from "../lib/subsonic";
import { getClient } from "../lib/session";
import { Icon } from "./Icon";
import { Cover } from "./Cover";

export function TrackList({ songs, showCover = false, numbered = true, showAlbum = false }: { songs: Song[]; showCover?: boolean; numbered?: boolean; showAlbum?: boolean }) {
  const { queue, playing } = usePlayer();
  const now = current(queue);
  const [menu, setMenu] = useState<number | null>(null);
  const [stars, setStars] = useState<Record<string, boolean>>({});

  if (songs.length === 0) return <p className="muted">Nessun brano.</p>;

  const isStarred = (s: Song) => stars[s.id] ?? Boolean(s.starred);
  const toggleStar = async (s: Song) => {
    const next = !isStarred(s);
    setStars((m) => ({ ...m, [s.id]: next }));
    try {
      await getClient()?.setStar(s.id, next);
      player.patchSong(s.id, { starred: next ? new Date().toISOString() : undefined });
    } catch {
      setStars((m) => ({ ...m, [s.id]: !next }));
    }
  };

  return (
    <ol className="tracks">
      {songs.map((s, i) => {
        const active = now?.id === s.id;
        return (
          <li key={`${s.id}-${i}`} className={`track ${active ? "track--active" : ""}`}>
            <button className="track__main" onClick={() => player.play(songs, i)} title={`Riproduci ${s.title}`}>
              {showCover ? (
                <Cover id={s.coverArt} size={96} className="track__cover" />
              ) : (
                numbered && <span className="track__num">{active && playing ? <span className="eq" aria-label="In riproduzione"><i /><i /><i /></span> : s.track ?? i + 1}</span>
              )}
              <span className="track__text">
                <span className="track__title">{s.title}</span>
                <span className="track__sub">
                  {s.artist}
                  {showAlbum && s.album ? ` · ${s.album}` : ""}
                </span>
              </span>
            </button>
            <button className={`icon-btn track__star ${isStarred(s) ? "is-on" : ""}`} onClick={() => toggleStar(s)} aria-label={isStarred(s) ? "Togli dai preferiti" : "Aggiungi ai preferiti"}>
              <Icon name={isStarred(s) ? "heart" : "heartOutline"} size={20} />
            </button>
            <span className="track__dur">{formatDuration(s.duration)}</span>
            <div className="track__menu">
              <button className="icon-btn" onClick={() => setMenu(menu === i ? null : i)} aria-label="Altre azioni" aria-expanded={menu === i}>
                <Icon name="more" size={20} />
              </button>
              {menu === i && (
                <div className="menu" onMouseLeave={() => setMenu(null)}>
                  <button onClick={() => { player.playNext([s]); setMenu(null); }}>
                    <Icon name="playNext" size={18} /> Riproduci dopo
                  </button>
                  <button onClick={() => { player.enqueue([s]); setMenu(null); }}>
                    <Icon name="addQueue" size={18} /> Aggiungi alla coda
                  </button>
                  {s.albumId && (
                    <a href={href({ name: "album", id: s.albumId })} onClick={() => setMenu(null)}>
                      <Icon name="album" size={18} /> Vai all'album
                    </a>
                  )}
                  {s.artistId && (
                    <a href={href({ name: "artist", id: s.artistId })} onClick={() => setMenu(null)}>
                      <Icon name="artist" size={18} /> Vai all'artista
                    </a>
                  )}
                </div>
              )}
            </div>
          </li>
        );
      })}
    </ol>
  );
}
