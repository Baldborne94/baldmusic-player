import { useState } from "react";
import { formatDuration } from "../lib/format";
import { player } from "../lib/player";

/** Barra di avanzamento: durante il trascinamento mostra la posizione scelta, non quella reale. */
export function Seek({ position, duration, compact = false }: { position: number; duration: number; compact?: boolean }) {
  const [drag, setDrag] = useState<number | null>(null);
  const shown = drag ?? position;
  const max = duration > 0 ? duration : 1;
  return (
    <div className={`seek ${compact ? "seek--compact" : ""}`}>
      {!compact && <span className="seek__time">{formatDuration(shown)}</span>}
      <input
        type="range"
        min={0}
        max={max}
        step={0.5}
        value={Math.min(shown, max)}
        style={{ "--pct": `${(Math.min(shown, max) / max) * 100}%` } as React.CSSProperties}
        aria-label="Posizione nel brano"
        onChange={(e) => setDrag(Number(e.target.value))}
        onPointerUp={() => {
          if (drag !== null) player.seek(drag);
          setDrag(null);
        }}
        onKeyUp={() => {
          if (drag !== null) player.seek(drag);
          setDrag(null);
        }}
      />
      {!compact && <span className="seek__time">{formatDuration(duration)}</span>}
    </div>
  );
}
