import { useEffect, useRef, useState } from "react";
import { findLyrics, lineAt, type Lyrics } from "../lib/lyrics";
import { player, usePlayer } from "../lib/player";
import { current } from "../lib/queue";

/** Il testo del brano in corso: lo cerca a ogni cambio di brano. */
export function useLyrics(): { lyrics: Lyrics | null; loading: boolean } {
  const { queue } = usePlayer();
  const song = current(queue);
  const [state, setState] = useState<{ id?: string; lyrics: Lyrics | null; loading: boolean }>({ lyrics: null, loading: false });
  useEffect(() => {
    if (!song) return;
    let alive = true;
    setState({ id: song.id, lyrics: null, loading: true });
    void findLyrics(song.artist, song.title, song.album, song.duration).then((lyrics) => alive && setState({ id: song.id, lyrics, loading: false }));
    return () => {
      alive = false;
    };
  }, [song?.id]); // eslint-disable-line react-hooks/exhaustive-deps
  return state;
}

/** Testo sincronizzato: la riga corrente in evidenza; si clicca una riga per saltarci. */
export function LyricsView({ big = false }: { big?: boolean }) {
  const { position } = usePlayer();
  const { lyrics, loading } = useLyrics();
  const box = useRef<HTMLDivElement>(null);
  const idx = lyrics?.synced.length ? lineAt(lyrics.synced, position + 0.3) : -1;

  useEffect(() => {
    const el = box.current?.querySelector<HTMLElement>(`[data-i="${idx}"]`);
    el?.scrollIntoView({ block: "center", behavior: "smooth" });
  }, [idx]);

  if (loading) return <div className="lyrics lyrics--empty">Cerco il testo…</div>;
  if (!lyrics) return <div className="lyrics lyrics--empty">Testo non disponibile per questo brano.</div>;
  if (!lyrics.synced.length) return <div className={`lyrics ${big ? "lyrics--big" : ""}`}><p className="lyrics__plain">{lyrics.plain}</p></div>;
  return (
    <div className={`lyrics ${big ? "lyrics--big" : ""}`} ref={box}>
      {lyrics.synced.map((l, i) => (
        <p key={i} data-i={i} className={i === idx ? "is-now" : i < idx ? "is-past" : ""} onClick={() => player.seek(l.time)}>
          {l.text || "♪"}
        </p>
      ))}
      <span className="lyrics__credit">Testi da LRCLIB</span>
    </div>
  );
}
