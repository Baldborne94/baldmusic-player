import { useMemo, useState } from "react";
import { Icon } from "../components/Icon";
import { ErrorState, Loading } from "../components/States";
import { duplicates, folderName, isWindowsPath, previews, trashScript, type DuplicateGroup } from "../lib/cleanup";
import { isRealPath } from "../lib/collections";
import { formatDuration, plural } from "../lib/format";
import { mergeDuplicates } from "../lib/library";
import { getClient } from "../lib/session";
import type { Song } from "../lib/subsonic";
import { useLoad } from "../lib/useLoad";
import { CollectionsHint } from "./Library";

/**
 * "Pulizia della libreria" sul PC: anteprime di 30 secondi e doppioni, come sul telefono.
 * Il browser non può toccare i file: si scarica uno script che li sposta nel Cestino di
 * Windows. Dopo, nella cartella si rimettono i file giusti e Navidrome li trova da solo.
 */
export function CleanupPage() {
  const { data, error, loading, reload } = useLoad(() => getClient()!.allSongs(), []);
  // Righe tolte dalla pulizia: ID del brano tenuto (doppioni) o dell'anteprima.
  const [skip, setSkip] = useState<Set<string>>(new Set());
  const [busy, setBusy] = useState(false);
  const [done, setDone] = useState<string[] | null>(null);
  const report = useMemo(() => (data ? { dup: duplicates(data), prev: previews(data) } : null), [data]);

  if (loading && !data) return <Loading />;
  if (error || !data || !report) return <ErrorState message={error ?? "Libreria non disponibile"} onRetry={reload} />;
  if (!data.some((s) => s.path && isRealPath(s.path))) return <div className="page"><h1>Pulizia della libreria</h1><CollectionsHint status={{ state: "noRealPath", missing: [] }} /></div>;

  const groups = report.dup.filter((g) => !skip.has(g.keep.id));
  const prev = report.prev.filter((s) => !skip.has(s.id));
  const files = [...groups.flatMap((g) => g.others), ...prev];
  const windows = files.every((s) => s.path && isWindowsPath(s.path));
  const toggle = (id: string) => setSkip((old) => {
    const next = new Set(old);
    if (next.has(id)) next.delete(id);
    else next.add(id);
    return next;
  });

  const prepare = async () => {
    const ask =
      `Preparare lo script che sposta ${plural(files.length, "file", "file")} nel Cestino di Windows?` +
      (groups.length ? "\n\nLe raccolte, i preferiti e le playlist che avevano le copie doppie passano subito a quella che resta." : "");
    if (!confirm(ask)) return;
    setBusy(true);
    try {
      await mergeDuplicates(groups);
      download("baldmusic-pulizia.ps1", trashScript(files.map((s) => s.path!)));
      setDone(files.map((s) => s.path!));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="page cleanup">
      <header className="page-head">
        <h1>Pulizia della libreria</h1>
      </header>
      <p className="muted pad">
        Come sul telefono: brani scaricati male (anteprime di 30 secondi) e lo stesso brano in più cartelle. I file non si cancellano
        per sempre: vanno nel <strong>Cestino di Windows</strong>, da cui si ripristinano. Poi metti nella cartella i file giusti e
        Navidrome li trova da solo.
      </p>

      {done && (
        <div className="panel-box">
          <h2>Ultimo passo: sul PC</h2>
          {windows ? (
            <ol className="steps">
              <li>Apri la cartella <strong>Download</strong>: c'è <code>baldmusic-pulizia.ps1</code> (se Chrome avvisa che è un file da controllare, scegli <strong>Mantieni</strong>).</li>
              <li>
                Tasto destro sul file → <strong>Esegui con PowerShell</strong> (se chiede di cambiare i criteri di esecuzione, rispondi <strong>S</strong>). Sposta{" "}
                {plural(done.length, "file", "file")} nel Cestino e alla fine chiede di premere Invio.
              </li>
              <li>Navidrome se ne accorge da solo; se dopo un minuto li vedi ancora, in Navidrome: icona a onda → <strong>Quick Scan</strong>.</li>
              <li>Buttato qualcosa per sbaglio? Cestino di Windows → tasto destro → <strong>Ripristina</strong>.</li>
            </ol>
          ) : (
            <p className="muted">Il server non è un PC Windows: sposta a mano questi file nel cestino.</p>
          )}
          <button className="btn" onClick={() => void navigator.clipboard?.writeText(done.join("\n"))}>
            <Icon name="share" size={16} /> Copia l'elenco dei file
          </button>
        </div>
      )}

      {report.dup.length === 0 && report.prev.length === 0 && <p className="pad">Tutto in ordine: niente doppioni e niente anteprime di 30 secondi.</p>}

      {report.prev.length > 0 && (
        <section className="panel-box">
          <h2>Brani di 30 secondi</h2>
          <p className="muted">
            {plural(report.prev.length, "brano dura", "brani durano")} circa 30 secondi: quasi sempre sono anteprime scaricate al posto della canzone
            intera. Vanno buttati e riscaricati.
          </p>
          <button className="btn" onClick={() => void navigator.clipboard?.writeText(report.prev.map((s) => `${s.artist ?? ""} - ${s.title} (${folderName(s.path)})`).join("\n"))}>
            <Icon name="share" size={16} /> Copia l'elenco da riscaricare
          </button>
          {report.prev.map((s) => (
            <Row key={s.id} checked={!skip.has(s.id)} onToggle={() => toggle(s.id)} song={s} lines={[`${s.artist ?? ""} · ${formatDuration(s.duration)} · ${folderName(s.path)}`]} />
          ))}
        </section>
      )}

      {report.dup.length > 0 && (
        <section className="panel-box">
          <h2>Doppioni</h2>
          <p className="muted">
            {plural(report.dup.length, "brano è", "brani sono")} in più copie, in cartelle diverse. Si tiene la copia coi tag più completi: le
            raccolte che avevano le altre continuano ad avere il brano.
          </p>
          {report.dup.map((g: DuplicateGroup) => (
            <Row
              key={g.keep.id}
              checked={!skip.has(g.keep.id)}
              onToggle={() => toggle(g.keep.id)}
              song={g.keep}
              lines={[`${g.keep.artist ?? ""} · ${formatDuration(g.keep.duration)}`, `Resta in: ${folderName(g.keep.path)}`, `Da buttare: ${g.others.map((o) => folderName(o.path)).join(", ")}`]}
            />
          ))}
        </section>
      )}

      {files.length > 0 && (
        <div className="pad">
          <button className="btn btn--primary" disabled={busy} onClick={() => void prepare()}>
            <Icon name="trash" size={18} /> {busy ? "Preparo…" : `Prepara la pulizia (${plural(files.length, "file", "file")})`}
          </button>
        </div>
      )}
    </div>
  );
}

function Row({ song, lines, checked, onToggle }: { song: Song; lines: string[]; checked: boolean; onToggle: () => void }) {
  return (
    <label className="clean-row">
      <input type="checkbox" checked={checked} onChange={onToggle} />
      <span className="clean-row__text">
        <strong>{song.title}</strong>
        {lines.map((l, i) => (
          <span key={i} className="muted">{l}</span>
        ))}
      </span>
    </label>
  );
}

function download(name: string, text: string) {
  const url = URL.createObjectURL(new Blob([text], { type: "text/plain;charset=utf-8" }));
  const a = document.createElement("a");
  a.href = url;
  a.download = name;
  a.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
