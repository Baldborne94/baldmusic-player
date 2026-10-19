import { useState } from "react";
import { Icon } from "../components/Icon";
import { syncCollections, useLibrary } from "../lib/library";
import { player } from "../lib/player";
import { CollectionsHint } from "./Library";
import { saveCredentials, useClient } from "../lib/session";

export function Settings() {
  const client = useClient();
  const lib = useLibrary();
  const [status, setStatus] = useState<string | null>(null);
  if (!client) return null;
  const { url, user } = client.creds;

  const test = async () => {
    setStatus("Verifica…");
    try {
      await client.ping();
      setStatus("Server raggiungibile ✓");
    } catch (e) {
      setStatus(e instanceof Error ? e.message : String(e));
    }
  };

  return (
    <section className="settings">
      <header className="page-head">
        <h1>Impostazioni</h1>
      </header>
      <div className="panel">
        <h2>Server</h2>
        <p>
          <strong>{user}</strong> su <code>{url}</code>
        </p>
        <div className="actions">
          <button className="btn" onClick={test}>
            Verifica connessione
          </button>
          <a className="btn" href={url} target="_blank" rel="noreferrer">
            Apri Navidrome
          </a>
          <button
            className="btn btn--danger"
            onClick={() => {
              player.clear();
              saveCredentials(null);
            }}
          >
            <Icon name="close" size={18} /> Esci
          </button>
        </div>
        {status && <p className="muted">{status}</p>}
      </div>
      <div className="panel">
        <h2>Raccolte dalle cartelle</h2>
        <p className="muted">
          Come sul telefono: ogni cartella dentro quella della musica di Navidrome è una raccolta. I brani nuovi della cartella entrano da
          soli a ogni apertura; quelli tolti a mano non tornano; una raccolta eliminata non si ricrea. I file non si toccano mai.
        </p>
        {lib.collections.state === "ready" ? <p className="muted">Raccolte allineate alle cartelle ✓</p> : <CollectionsHint status={lib.collections} />}
        <div className="actions">
          <button className="btn" disabled={lib.collections.state === "syncing"} onClick={() => void syncCollections()}>
            Aggiorna ora
          </button>
        </div>
      </div>
      <div className="panel">
        <h2>Scorciatoie da tastiera</h2>
        <ul className="keys">
          <li><kbd>Spazio</kbd> riproduci / pausa</li>
          <li><kbd>Shift</kbd> + <kbd>→</kbd> / <kbd>←</kbd> brano successivo / precedente</li>
          <li><kbd>→</kbd> / <kbd>←</kbd> avanti / indietro di 10 secondi</li>
          <li><kbd>/</kbd> cerca</li>
        </ul>
      </div>
      <div className="panel">
        <h2>Informazioni</h2>
        <p className="muted">
          BaldMusic è open source. Codice e guida all'installazione su{" "}
          <a href="https://github.com/Baldborne94/baldmusic-player" target="_blank" rel="noreferrer">
            GitHub
          </a>
          .
        </p>
      </div>
    </section>
  );
}
