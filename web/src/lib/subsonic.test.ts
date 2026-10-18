import { createHash } from "node:crypto";
import { describe, expect, it } from "vitest";
import { blockedAsMixedContent, buildUrl, makeCredentials, normalizeServerUrl, parseResponse, SubsonicClient, SubsonicError } from "./subsonic";

describe("normalizeServerUrl", () => {
  it("aggiunge https e toglie gli slash finali", () => {
    expect(normalizeServerUrl(" pc.tail1234.ts.net/ ")).toBe("https://pc.tail1234.ts.net");
    expect(normalizeServerUrl("http://192.168.1.10:4533//")).toBe("http://192.168.1.10:4533");
  });

  it("aggiunge la porta di Navidrome a IP e localhost che non ce l'hanno", () => {
    expect(normalizeServerUrl("http://192.168.1.191")).toBe("http://192.168.1.191:4533");
    expect(normalizeServerUrl("localhost")).toBe("http://localhost:4533");
    expect(normalizeServerUrl("http://localhost:8080")).toBe("http://localhost:8080");
    expect(normalizeServerUrl("https://pc.tail1.ts.net")).toBe("https://pc.tail1.ts.net");
  });
});

describe("makeCredentials", () => {
  it("usa t = md5(password + sale), come chiede Subsonic", () => {
    const c = makeCredentials("server", " ale ", "sesame", "c19b2d");
    // Esempio ufficiale della documentazione Subsonic.
    expect(c.token).toBe("26719a1196d2a940705a59634eb18eab");
    expect(c.user).toBe("ale");
    expect(c.url).toBe("https://server");
  });

  it("concorda con md5 di Node anche con caratteri non ASCII", () => {
    const c = makeCredentials("s", "u", "pàssword€", "ab12");
    expect(c.token).toBe(createHash("md5").update("pàssword€ab12", "utf8").digest("hex"));
  });

  it("genera un sale diverso ogni volta", () => {
    expect(makeCredentials("s", "u", "p").salt).not.toBe(makeCredentials("s", "u", "p").salt);
  });
});

describe("buildUrl", () => {
  it("include autenticazione, versione, client e parametri", () => {
    const c = { url: "https://x", user: "u", salt: "s1", token: "t1" };
    const url = new URL(buildUrl(c, "getAlbum", { id: "a b", skip: undefined }));
    expect(url.pathname).toBe("/rest/getAlbum");
    expect(Object.fromEntries(url.searchParams)).toEqual({ u: "u", t: "t1", s: "s1", v: "1.16.1", c: "baldmusic", f: "json", id: "a b" });
  });
});

describe("streamUrl", () => {
  it("chiede sempre il file originale, senza conversioni", () => {
    const url = new URL(new SubsonicClient(makeCredentials("https://pc.ts.net", "u", "p", "ab")).streamUrl("s1"));
    expect(url.searchParams.get("format")).toBe("raw");
    expect(url.searchParams.get("maxBitRate")).toBeNull();
    expect(url.searchParams.get("id")).toBe("s1");
  });
});

describe("parseResponse", () => {
  it("restituisce il corpo se lo stato è ok", () => {
    expect(parseResponse({ "subsonic-response": { status: "ok", album: { id: "1" } } }).album).toEqual({ id: "1" });
  });

  it("trasforma gli errori del server in SubsonicError", () => {
    const body = { "subsonic-response": { status: "failed", error: { code: 40, message: "Wrong username or password" } } };
    expect(() => parseResponse(body)).toThrow(SubsonicError);
    try {
      parseResponse(body);
    } catch (e) {
      expect((e as SubsonicError).code).toBe(40);
    }
  });

  it("rifiuta risposte che non vengono da Subsonic", () => {
    expect(() => parseResponse({ hello: 1 })).toThrow(/non sembra/);
  });
});

describe("blockedAsMixedContent", () => {
  it("da https blocca l'http in rete, non il PC stesso", () => {
    expect(blockedAsMixedContent("https:", "http://192.168.1.20:4533")).toBe(true);
    expect(blockedAsMixedContent("https:", "http://100.64.1.2:4533")).toBe(true);
    expect(blockedAsMixedContent("https:", "http://localhost:4533")).toBe(false);
    expect(blockedAsMixedContent("https:", "http://127.0.0.1:4533")).toBe(false);
    expect(blockedAsMixedContent("https:", "https://pc.tail1.ts.net")).toBe(false);
  });

  it("da una pagina http non blocca niente", () => {
    expect(blockedAsMixedContent("http:", "http://192.168.1.20:4533")).toBe(false);
  });
});
