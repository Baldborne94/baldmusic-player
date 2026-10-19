import { useCallback, useEffect, useState } from "react";
import { Icon } from "./components/Icon";
import { Login } from "./components/Login";
import { FullPlayer, QueueList, RightPanel } from "./components/Panels";
import { PlayerBar, type Panel } from "./components/PlayerBar";
import { refreshLibrary, syncCollections } from "./lib/library";
import { getPlayerState, player, usePlayer } from "./lib/player";
import { current } from "./lib/queue";
import { href, navigate, useRoute, type Route } from "./lib/router";
import { useClient } from "./lib/session";
import { Home } from "./views/Home";
import { LibraryPage, SidebarLibrary } from "./views/Library";
import { AlbumPage, ArtistPage, DecadePage, GenrePage, LikedPage, PlaylistPage, Stats } from "./views/Pages";
import { CleanupPage } from "./views/Cleanup";
import { TrashPage } from "./views/Trash";
import { Search } from "./views/Search";
import { Settings } from "./views/Settings";

function View({ route }: { route: Route }) {
  switch (route.name) {
    case "home":
      return <Home />;
    case "search":
      return <Search query={route.query} />;
    case "library":
      return <LibraryPage initial={null} />;
    case "albums":
      return <LibraryPage initial="albums" />;
    case "artists":
      return <LibraryPage initial="artists" />;
    case "playlists":
      return <LibraryPage initial="playlists" />;
    case "collections":
      return <LibraryPage initial="collections" />;
    case "cleanup":
      return <CleanupPage />;
    case "trash":
      return <TrashPage />;
    case "album":
      return <AlbumPage id={route.id} />;
    case "artist":
      return <ArtistPage id={route.id} />;
    case "playlist":
      return <PlaylistPage id={route.id} />;
    case "liked":
      return <LikedPage />;
    case "genre":
      return <GenrePage id={route.id} />;
    case "decade":
      return <DecadePage id={route.id} />;
    case "stats":
      return <Stats />;
    case "queue":
      return (
        <div className="page">
          <h1>Coda</h1>
          <QueueList />
        </div>
      );
    case "settings":
      return <Settings />;
  }
}

function useShortcuts(openFull: () => void) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const t = e.target as HTMLElement;
      if (t.isContentEditable || ["INPUT", "TEXTAREA", "SELECT"].includes(t.tagName) || e.ctrlKey || e.metaKey || e.altKey) return;
      const s = getPlayerState();
      if (e.key === " ") {
        e.preventDefault();
        player.toggle();
      } else if (e.key === "ArrowRight") {
        e.preventDefault();
        if (e.shiftKey) player.next();
        else player.seek(s.position + 10);
      } else if (e.key === "ArrowLeft") {
        e.preventDefault();
        if (e.shiftKey) player.prev();
        else player.seek(s.position - 10);
      } else if (e.key === "/") {
        e.preventDefault();
        navigate({ name: "search", query: "" });
      } else if (e.key.toLowerCase() === "f") {
        openFull();
      } else if (e.key.toLowerCase() === "s") {
        player.toggleShuffle();
      } else if (e.key.toLowerCase() === "r") {
        player.cycleRepeat();
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [openFull]);
}

const PANEL_KEY = "baldmusic.panel";

export function App() {
  const client = useClient();
  const route = useRoute();
  const { queue } = usePlayer();
  const [full, setFull] = useState(false);
  const [panel, setPanelState] = useState<Panel>(() => {
    try {
      return (localStorage.getItem(PANEL_KEY) as Panel) || "now";
    } catch {
      return "now";
    }
  });
  const setPanel = (p: Panel) => {
    setPanelState(p);
    try {
      localStorage.setItem(PANEL_KEY, p ?? "");
    } catch {
      // ignorato
    }
  };
  const openFull = useCallback(() => setFull(true), []);
  const closeFull = useCallback(() => setFull(false), []);
  useShortcuts(openFull);

  useEffect(() => {
    if (!client) return;
    void refreshLibrary();
    // Le raccolte seguono le cartelle: si allineano a ogni apertura.
    void syncCollections();
  }, [client]);

  // Ogni cambio pagina riparte dall'alto, come in un'app nativa.
  useEffect(() => {
    document.querySelector(".main")?.scrollTo(0, 0);
  }, [route.name, "id" in route ? route.id : null]);

  const song = current(queue);
  useEffect(() => {
    document.title = song ? `${song.title} · ${song.artist ?? ""}` : "BaldMusic";
  }, [song]);

  if (!client) return <Login />;

  const at = (name: Route["name"]) => route.name === name || (name === "library" && ["library", "albums", "artists", "playlists", "collections"].includes(route.name));
  const showPanel = !!song && panel !== null;

  return (
    <div className={`app ${showPanel ? "app--panel" : ""}`}>
      <nav className="side" aria-label="Navigazione">
        <div className="side__box">
          <a className="brand" href="#/">
            <img src="./icon.svg" alt="" width={28} height={28} /> BaldMusic
          </a>
          <a className={`side__link ${at("home") ? "is-active" : ""}`} href="#/">
            <Icon name="home" /> Home
          </a>
          <a className={`side__link ${at("search") ? "is-active" : ""}`} href={href({ name: "search", query: "" })}>
            <Icon name="search" /> Cerca
          </a>
        </div>
        <div className="side__box side__box--grow">
          <SidebarLibrary route={route} />
        </div>
      </nav>

      <main className="main">
        <div className="topbar">
          <button className="round-btn" onClick={() => history.back()} aria-label="Indietro"><Icon name="back" size={20} /></button>
          <button className="round-btn" onClick={() => history.forward()} aria-label="Avanti"><Icon name="back" size={20} className="flip" /></button>
          <span className="topbar__space" />
          <a className="round-btn" href={href({ name: "stats" })} title="Le tue statistiche"><Icon name="chart" size={20} /></a>
          <a className="round-btn" href={href({ name: "settings" })} title="Impostazioni"><Icon name="settings" size={20} /></a>
        </div>
        <View route={route} />
      </main>

      {showPanel && <RightPanel panel={panel!} onClose={() => setPanel(null)} />}

      <PlayerBar panel={panel} setPanel={setPanel} onExpand={openFull} />

      <nav className="tabs" aria-label="Sezioni">
        <a className={`tabs__link ${at("home") ? "is-active" : ""}`} href="#/"><Icon name="home" /><span>Home</span></a>
        <a className={`tabs__link ${at("search") ? "is-active" : ""}`} href={href({ name: "search", query: "" })}><Icon name="search" /><span>Cerca</span></a>
        <a className={`tabs__link ${at("library") ? "is-active" : ""}`} href={href({ name: "library" })}><Icon name="library" /><span>La tua libreria</span></a>
      </nav>

      {full && <FullPlayer onClose={closeFull} />}
    </div>
  );
}
