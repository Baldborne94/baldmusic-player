import { useMemo, useRef, useState } from "react";
import { Icon } from "../components/Icon";
import { ErrorState, Loading } from "../components/States";
import { folderName, isWindowsPath } from "../lib/cleanup";
import { plural } from "../lib/format";
import { checkTrash, forgetTrashed, trashedFiles } from "../lib/library";
import { href } from "../lib/router";
import { RESTORE_SCRIPT_NAME, restoreScript } from "../lib/trash";
import { useLoad } from "../lib/useLoad";
import { CopyButton, download, Row, SelectAll } from "./Cleanup";

/**
 * "Brani nel cestino" sul PC, come sul telefono: i file che l'app ha mandato nel Cestino
 * di Windows (pulizia, raccolte). Si scarica uno script che li rimette al loro posto; quando
 * Navidrome li ritrova, ognuno torna nella raccolta della sua cartella.
 */
export function TrashPage() {
  // Prima si allinea: chi è già tornato (anche ripristinato a mano) esce dall'elenco.
  const { data: present, error, loading, reload } = useLoad(() => checkTrash(), []);
  const [version, setVersion] = useState(0);
  // Righe tolte dal ripristino, per percorso.
  const [skip, setSkip] = useState<Set<string>>(new Set());
  const [done, setDone] = useState<string[] | null>(null);
  const anchor = useRef<number | null>(null);
  const records = useMemo(() => trashedFiles(), [present, version]);

  if (loading && !present) return <Loading />;
  if (error || !present) return <ErrorState message={error ?? "Libreria non disponibile"} onRetry={reload} />;

  const inBin = records.filter((r) => !present.has(r.path));
  const waiting = records.filter((r) => present.has(r.path));
  const paths = inBin.map((r) => r.path);
  const chosen = paths.filter((p) => !skip.has(p));
  const windows = chosen.every(isWindowsPath);

  const setMany = (list: string[], on: boolean) =>
    setSkip((old) => {
      const next = new Set(old);
      list.forEach((p) => (on ? next.delete(p) : next.add(p)));
      return next;
    });
  const click = (index: number, shift: boolean) => {
    const on = skip.has(paths[index]);
    const a = anchor.current;
    setMany(shift && a !== null ? paths.slice(Math.min(a, index), Math.max(a, index) + 1) : [paths[index]], on);
    anchor.current = index;
  };
  const prepare = () => {
    if (!confirm(`Preparare lo script che rimette ${plural(chosen.length, "file", "file")} dal Cestino di Windows al suo posto?`)) return;
    // Sono spariti da Navidrome: quando ricompaiono, sono stati ripristinati.
    forgetTrashed(chosen, true);
    download(RESTORE_SCRIPT_NAME, restoreScript(chosen));
    setDone(chosen);
  };
  const forget = (list: string[]) => {
    if (!confirm(`Togliere ${plural(list.length, "file", "file")} da questo elenco? Restano nel Cestino di Windows, da cui si ripristinano a mano.`)) return;
    forgetTrashed(list);
    setSkip(new Set());
    setVersion((v) => v + 1);
  };

  return (
    <div className="page cleanup">
      <header className="page-head">
        <h1>Brani nel cestino</h1>
        <span className="page-head__actions">
          <a className="btn" href={href({ name: "cleanup" })}>
            <Icon name="trash" size={18} /> Pulizia
          </a>
        </span>
      </header>
      <p className="muted pad">
        I file che BaldMusic ha mandato nel <strong>Cestino di Windows</strong> da questo browser, dalla pulizia o da una raccolta. Si
        rimettono nella loro cartella e tornano nella loro raccolta, al posto della copia che li aveva sostituiti.
      </p>

      {done && (
        <div className="panel-box">
          <h2>Ultimo passo: sul PC</h2>
          {windows ? (
            <ol className="steps">
              <li>
                Apri la cartella <strong>Download</strong>: c'è <code>{RESTORE_SCRIPT_NAME}</code> (se Chrome avvisa che è un file da controllare,
                scegli <strong>Mantieni</strong>).
              </li>
              <li>
                <strong>Doppio clic</strong> sul file. Se Windows dice «Windows ha protetto il PC», premi <strong>Ulteriori informazioni</strong> →{" "}
                <strong>Esegui comunque</strong>. Si apre una finestra che rimette {plural(done.length, "file", "file")} al suo posto e alla fine chiede
                di premere Invio.
              </li>
              <li>
                Navidrome li ritrova da solo (se dopo un minuto non li vedi: icona a onda → <strong>Quick Scan</strong>). Poi premi{" "}
                <strong>Ho fatto: aggiorna</strong> qui sotto e ogni brano torna nella sua raccolta.
              </li>
            </ol>
          ) : (
            <p className="muted">Il server non è un PC Windows: rimetti a mano questi file al loro posto.</p>
          )}
          <div className="actions">
            <button
              className="btn btn--primary"
              disabled={loading}
              onClick={() => {
                setDone(null);
                reload();
              }}
            >
              <Icon name="check" size={16} /> Ho fatto: aggiorna
            </button>
            <CopyButton label="Copia l'elenco dei file" text={done.join("\n")} />
          </div>
        </div>
      )}

      {records.length === 0 && (
        <p className="pad">
          Il cestino è vuoto. I file buttati da Esplora file o da un altro browser si ripristinano dal Cestino di Windows: tasto destro →{" "}
          <strong>Ripristina</strong>. Anche così, quando Navidrome li ritrova, tornano nella loro raccolta da soli.
        </p>
      )}

      {inBin.length > 0 && (
        <section className="panel-box">
          <h2>Nel Cestino di Windows</h2>
          <SelectAll ids={paths} skip={skip} onChange={(on) => setMany(paths, on)} />
          {inBin.map((r, i) => (
            <Row
              key={r.path}
              title={r.title}
              checked={!skip.has(r.path)}
              onClick={(shift) => click(i, shift)}
              lines={[`${r.artist ?? ""} · ${folderName(r.path)} · buttato il ${new Date(r.at).toLocaleDateString("it-IT")}`]}
            />
          ))}
          <div className="actions">
            <button className="btn btn--primary" disabled={chosen.length === 0} onClick={prepare}>
              <Icon name="restore" size={18} /> Prepara il ripristino ({plural(chosen.length, "file", "file")})
            </button>
            <button className="btn" disabled={chosen.length === 0} onClick={() => forget(chosen)}>
              Dimentica
            </button>
          </div>
        </section>
      )}

      {waiting.length > 0 && (
        <section className="panel-box">
          <h2>Ancora nella libreria</h2>
          <p className="muted">
            Lo script per il Cestino non è partito, o Navidrome non ha ancora visto che mancano: quando spariscono, compaiono qui sopra.
          </p>
          {waiting.map((r) => (
            <Row key={r.path} title={r.title} checked={false} disabled onClick={() => undefined} lines={[`${r.artist ?? ""} · ${folderName(r.path)}`]} />
          ))}
          <div className="actions">
            <button className="btn" onClick={() => forget(waiting.map((r) => r.path))}>
              Dimentica questi
            </button>
          </div>
        </section>
      )}
    </div>
  );
}
