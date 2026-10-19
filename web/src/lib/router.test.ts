import { afterEach, describe, expect, it, vi } from "vitest";
import { href, navigate, parseRoute, type Route } from "./router";

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("router", () => {
  it("andata e ritorno per ogni rotta", () => {
    const routes: Route[] = [
      { name: "home" },
      { name: "album", id: "al-1 2/3" },
      { name: "artist", id: "ar-9" },
      { name: "playlist", id: "p" },
      { name: "search", query: "lucio dalla & co" },
      { name: "queue" },
      { name: "settings" },
      { name: "liked" },
      { name: "library" },
      { name: "stats" },
      { name: "genre", id: "Rock & Roll" },
      { name: "decade", id: "1990" },
    ];
    for (const r of routes) expect(parseRoute(href(r))).toEqual(r);
  });

  it("andata e ritorno anche per libreria, raccolte, pulizia e cestino", () => {
    const routes: Route[] = [{ name: "albums" }, { name: "artists" }, { name: "playlists" }, { name: "collections" }, { name: "cleanup" }, { name: "trash" }];
    for (const r of routes) expect(parseRoute(href(r))).toEqual(r);
    expect(href({ name: "trash" })).toBe("#/trash");
    expect(href({ name: "cleanup" })).toBe("#/cleanup");
  });

  it("ID e ricerche con caratteri speciali restano uguali", () => {
    const odd = ["a/b?c#d", "100% Disney", "Café Déjà-vu", "x+y=z", "  spazi  ", "&?=#/%", "日本語", "emoji 🎵"];
    for (const id of odd) {
      for (const name of ["album", "artist", "playlist", "genre", "decade"] as const) expect(parseRoute(href({ name, id }))).toEqual({ name, id });
      expect(parseRoute(href({ name: "search", query: id }))).toEqual({ name: "search", query: id });
    }
  });

  it("la ricerca vuota non ha parametri", () => {
    expect(href({ name: "search", query: "" })).toBe("#/search");
    expect(parseRoute("#/search")).toEqual({ name: "search", query: "" });
  });

  it("hash sconosciuto o vuoto porta alla home", () => {
    expect(parseRoute("")).toEqual({ name: "home" });
    expect(parseRoute("#/boh")).toEqual({ name: "home" });
  });

  it("una rotta con ID ma senza ID porta alla home; i vecchi preferiti ai brani che ti piacciono", () => {
    expect(parseRoute("#/album")).toEqual({ name: "home" });
    expect(parseRoute("#/playlist/")).toEqual({ name: "home" });
    expect(parseRoute("#/favorites")).toEqual({ name: "liked" });
    expect(parseRoute("#trash")).toEqual({ name: "trash" });
  });

  it("un indirizzo scritto male non blocca l'app", () => {
    expect(() => parseRoute("#/genre/100%")).not.toThrow();
    expect(parseRoute("#/genre/100%")).toEqual({ name: "genre", id: "100%" });
    expect(parseRoute("#/album/%E0%A4%A")).toEqual({ name: "album", id: "%E0%A4%A" });
  });

  it("navigate con replace sostituisce l'indirizzo e avvisa chi ascolta", () => {
    const win = new EventTarget();
    const loc = { hash: "" };
    const replaced: string[] = [];
    vi.stubGlobal("window", win);
    vi.stubGlobal("location", loc);
    vi.stubGlobal("history", { replaceState: (_: unknown, __: string, url: string) => replaced.push(url) });
    vi.stubGlobal("HashChangeEvent", Event);
    let heard = 0;
    win.addEventListener("hashchange", () => heard++);
    navigate({ name: "search", query: "vasco" }, true);
    expect(replaced).toEqual(["#/search?q=vasco"]);
    expect(heard).toBe(1);
    navigate({ name: "trash" });
    expect(loc.hash).toBe("#/trash");
  });
});
