import { AlbumRow } from "../components/AlbumGrid";
import { Icon } from "../components/Icon";
import { ErrorState, Loading } from "../components/States";
import { player } from "../lib/player";
import { getClient } from "../lib/session";
import { useLoad } from "../lib/useLoad";

export function Home() {
  const { data, error, loading, reload } = useLoad(async () => {
    const c = getClient()!;
    const [newest, recent, frequent, random] = await Promise.all([c.albumList("newest", 20), c.albumList("recent", 20), c.albumList("frequent", 20), c.albumList("random", 20)]);
    return { newest, recent, frequent, random };
  }, []);

  const shuffleAll = async () => {
    const songs = await getClient()!.randomSongs(100);
    player.play(songs);
  };

  if (loading && !data) return <Loading />;
  if (error) return <ErrorState message={error} onRetry={reload} />;
  const sections = [
    ["Ascoltati di recente", data!.recent],
    ["Aggiunti di recente", data!.newest],
    ["I più ascoltati", data!.frequent],
    ["Da riscoprire", data!.random],
  ] as const;
  const empty = sections.every(([, list]) => list.length === 0);

  return (
    <section>
      <header className="page-head">
        <h1>Ciao!</h1>
        <button className="btn btn--primary" onClick={shuffleAll}>
          <Icon name="dice" size={20} /> Mix casuale
        </button>
      </header>
      <div className="chips">
        <a className="chip" href="#/favorites">
          <Icon name="heart" size={18} /> Preferiti
        </a>
        <a className="chip" href="#/queue">
          <Icon name="queue" size={18} /> Coda
        </a>
        <a className="chip" href="#/settings">
          <Icon name="settings" size={18} /> Impostazioni
        </a>
      </div>
      {empty && <p className="muted">La libreria è vuota: aggiungi la musica nella cartella di Navidrome sul PC e attendi la scansione.</p>}
      {sections.map(([title, list]) =>
        list.length ? (
          <div key={title} className="shelf">
            <h2>{title}</h2>
            <AlbumRow albums={list} />
          </div>
        ) : null,
      )}
    </section>
  );
}
