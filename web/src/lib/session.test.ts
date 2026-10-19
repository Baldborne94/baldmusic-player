import { afterEach, describe, expect, it, vi } from "vitest";
import { CREDS, memoryStorage } from "../test/env";
import { makeCredentials } from "./subsonic";

// useClient è un hook: qui si guarda solo cosa leggerebbe React.
const react = vi.hoisted(() => ({ subscribe: null as null | ((l: () => void) => () => void), get: null as null | (() => unknown) }));
vi.mock("react", async (importOriginal) => ({
  ...(await importOriginal<typeof import("react")>()),
  useSyncExternalStore: (subscribe: (l: () => void) => () => void, get: () => unknown) => {
    react.subscribe = subscribe;
    react.get = get;
    return get();
  },
}));

const KEY = "baldmusic.credentials";

async function open(storage: Storage) {
  vi.resetModules();
  vi.stubGlobal("localStorage", storage);
  return import("./session");
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("sessione", () => {
  it("all'apertura ritrova le credenziali salvate", async () => {
    const s = await open(memoryStorage({ [KEY]: JSON.stringify(CREDS) }));
    expect(s.getClient()?.creds).toEqual(CREDS);
  });

  it("credenziali incomplete o rovinate: si torna all'accesso", async () => {
    for (const raw of [JSON.stringify({ ...CREDS, token: "" }), JSON.stringify({ url: "x", user: "u", password: "p" }), "{rotto", "null"]) {
      const s = await open(memoryStorage({ [KEY]: raw }));
      expect(s.getClient()).toBeNull();
    }
    expect((await open(memoryStorage({}, true))).getClient()).toBeNull();
  });

  it("salva solo indirizzo, utente, sale e token: mai la password", async () => {
    const storage = memoryStorage();
    const s = await open(storage);
    s.saveCredentials(makeCredentials("pc.tail1.ts.net", "ale", "la-mia-password", "abc"));
    const raw = storage.getItem(KEY)!;
    expect(Object.keys(JSON.parse(raw)).sort()).toEqual(["salt", "token", "url", "user"]);
    expect(raw).not.toContain("la-mia-password");
    expect(JSON.parse(raw)).toMatchObject({ url: "https://pc.tail1.ts.net", user: "ale", salt: "abc" });
    // Le richieste portano sale e token, non la password.
    const url = new URL(s.getClient()!.streamUrl("x"));
    expect(url.searchParams.get("t")).toBe(JSON.parse(raw).token);
    expect(url.searchParams.has("p")).toBe(false);
  });

  it("uscendo le credenziali si cancellano e chi usa la sessione lo sa", async () => {
    const storage = memoryStorage({ [KEY]: JSON.stringify(CREDS) });
    const s = await open(storage);
    expect(s.useClient()?.creds.user).toBe("b");
    let heard = 0;
    react.subscribe!(() => heard++);
    s.saveCredentials(null);
    expect(heard).toBe(1);
    expect(react.get!()).toBeNull();
    expect(storage.getItem(KEY)).toBeNull();
  });

  it("archiviazione bloccata: si entra lo stesso, finché la scheda resta aperta", async () => {
    const s = await open(memoryStorage({}, true));
    expect(() => s.saveCredentials(CREDS)).not.toThrow();
    expect(s.getClient()?.creds).toEqual(CREDS);
  });
});
