import { useState } from "react";
import { Icon } from "../components/Icon";
import { syncCollections, useLibrary } from "../lib/library";
import { player, usePlayer } from "../lib/player";
import { CROSSFADE_CHOICES } from "../lib/crossfade";
import type { ReplayGainMode } from "../lib/replaygain";
import { href } from "../lib/router";
import { CollectionsHint } from "./Library";
import { saveCredentials, useClient } from "../lib/session";

const RG_MODES: [ReplayGainMode, string][] = [
  ["album", "Album"],
  ["track", "Brano"],
  ["off", "Spento"],
];

export function Settings() {
  const client = useClient();
  const { replayGain: rg, crossfade } = usePlayer();
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
        <h2>Volume uguale tra i brani</h2>
        <p className="muted">
          Abbassa i brani più forti allo stesso livello degli altri, con i valori di ReplayGain che Navidrome legge dai file. Album: dentro un
          disco restano le differenze volute. Brano: ogni brano allo stesso livello, per mix e raccolte. I brani senza questi valori suonano
          com'è; si scrivono con Picard (plugin ReplayGain 2.0) o rsgain.
        </p>
        <div className="chips">
          {RG_MODES.map(([mode, label]) => (
            <button key={mode} className={`chip ${rg === mode ? "is-on" : ""}`} onClick={() => player.setReplayGain(mode)}>
              {label}
            </button>
          ))}
        </div>
      </div>
      <div className="panel">
        <h2>Dissolvenza tra i brani</h2>
        <p className="muted">
          Come i DJ: negli ultimi secondi il brano che finisce si abbassa mentre il successivo parte e sale. Mai tra due brani dello stesso
          album (dal vivo e colonne sonore continue restano attaccati) e solo quando un brano finisce da solo.
        </p>
        <div className="chips">
          {CROSSFADE_CHOICES.map((sec) => (
            <button key={sec} className={`chip ${crossfade === sec ? "is-on" : ""}`} onClick={() => player.setCrossfade(sec)}>
              {sec === 0 ? "Spenta" : `${sec} s`}
            </button>
          ))}
        </div>
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
          <a className="btn" href={href({ name: "cleanup" })}>
            <Icon name="trash" size={18} /> Pulizia della libreria
          </a>
          <a className="btn" href={href({ name: "trash" })}>
            <Icon name="restore" size={18} /> Brani nel cestino
          </a>
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
