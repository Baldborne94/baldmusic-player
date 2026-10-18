import { useCallback, useEffect, useState } from "react";
import { Icon, type IconName } from "./components/Icon";
import { Login } from "./components/Login";
import { NowPlaying } from "./components/NowPlaying";
import { PlayerBar } from "./components/PlayerBar";
import { getPlayerState, player, usePlayer } from "./lib/player";
import { current } from "./lib/queue";
import { href, navigate, useRoute, type Route } from "./lib/router";
import { useClient } from "./lib/session";
import { AlbumView } from "./views/AlbumView";
import { Albums } from "./views/Albums";
import { ArtistView } from "./views/ArtistView";
import { Artists } from "./views/Artists";
import { Favorites } from "./views/Favorites";
import { Home } from "./views/Home";
import { Playlists, PlaylistView } from "./views/Playlists";
import { Queue } from "./views/Queue";
import { Search } from "./views/Search";
import { Settings } from "./views/Settings";

const NAV: { route: Route; label: string; icon: IconName; mobile: boolean }[] = [
  { route: { name: "home" }, label: "Home", icon: "home", mobile: true },
  { route: { name: "search", query: "" }, label: "Cerca", icon: "search", mobile: true },
  { route: { name: "albums" }, label: "Album", icon: "album", mobile: true },
  { route: { name: "artists" }, label: "Artisti", icon: "artist", mobile: true },
  { route: { name: "playlists" }, label: "Playlist", icon: "playlist", mobile: true },
  { route: { name: "favorites" }, label: "Preferiti", icon: "heart", mobile: false },
  { route: { name: "queue" }, label: "Coda", icon: "queue", mobile: false },
  { route: { name: "settings" }, label: "Impostazioni", icon: "settings", mobile: false },
];

const SECTION: Partial<Record<Route["name"], Route["name"]>> = { album: "albums", artist: "artists", playlist: "playlists" };

function View({ route }: { route: Route }) {
  switch (route.name) {
    case "home":
      return <Home />;
    case "albums":
      return <Albums />;
    case "album":
      return <AlbumView id={route.id} />;
    case "artists":
      return <Artists />;
    case "artist":
      return <ArtistView id={route.id} />;
    case "playlists":
      return <Playlists />;
    case "playlist":
      return <PlaylistView id={route.id} />;
    case "favorites":
      return <Favorites />;
    case "search":
      return <Search query={route.query} />;
    case "queue":
      return <Queue />;
    case "settings":
      return <Settings />;
  }
}

function useShortcuts() {
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
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, []);
}

export function App() {
  const client = useClient();
  const route = useRoute();
  const { queue } = usePlayer();
  const [npOpen, setNpOpen] = useState(false);
  const closeNp = useCallback(() => setNpOpen(false), []);
  useShortcuts();

  // Ogni cambio pagina riparte dall'alto, come in un'app nativa.
  useEffect(() => {
    document.querySelector(".content")?.scrollTo(0, 0);
  }, [route.name, "id" in route ? route.id : null]);

  const song = current(queue);
  useEffect(() => {
    document.title = song ? `${song.title} · ${song.artist ?? ""} — BaldMusic` : "BaldMusic";
  }, [song]);

  if (!client) return <Login />;

  const active = SECTION[route.name] ?? route.name;
  return (
    <div className={`app ${song ? "app--playing" : ""}`}>
      <nav className="side" aria-label="Sezioni">
        <a className="brand" href="#/">
          <img src="./icon.svg" alt="" width={32} height={32} /> BaldMusic
        </a>
        {NAV.map((n) => (
          <a key={n.label} className={`side__link ${active === n.route.name ? "is-active" : ""}`} href={href(n.route)}>
            <Icon name={n.icon} size={22} /> {n.label}
          </a>
        ))}
      </nav>
      <main className="content">
        <View route={route} />
      </main>
      <PlayerBar onOpen={() => setNpOpen(true)} />
      <nav className="tabs" aria-label="Sezioni">
        {NAV.filter((n) => n.mobile).map((n) => (
          <a key={n.label} className={`tabs__link ${active === n.route.name ? "is-active" : ""}`} href={href(n.route)}>
            <Icon name={n.icon} size={24} />
            <span>{n.label}</span>
          </a>
        ))}
      </nav>
      {npOpen && <NowPlaying onClose={closeNp} />}
    </div>
  );
}
