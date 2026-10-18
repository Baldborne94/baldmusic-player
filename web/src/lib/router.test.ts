import { describe, expect, it } from "vitest";
import { href, parseRoute, type Route } from "./router";

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
    ];
    for (const r of routes) expect(parseRoute(href(r))).toEqual(r);
  });

  it("hash sconosciuto o vuoto porta alla home", () => {
    expect(parseRoute("")).toEqual({ name: "home" });
    expect(parseRoute("#/boh")).toEqual({ name: "home" });
  });
});
