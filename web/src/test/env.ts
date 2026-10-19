import type { FakeNavidrome } from "./fakeNavidrome";

// Quello che il browser darebbe e Node no: localStorage e un fetch che arriva al
// Navidrome finto. I test lo mettono con vi.stubGlobal.

/** Un localStorage in memoria; [broken] = archiviazione bloccata, come in navigazione privata. */
export function memoryStorage(initial: Record<string, string> = {}, broken = false): Storage {
  const data = new Map(Object.entries(initial));
  const guard = () => {
    if (broken) throw new DOMException("Archiviazione bloccata", "SecurityError");
  };
  return {
    get length() {
      return data.size;
    },
    clear: () => data.clear(),
    getItem: (k) => (guard(), data.has(k) ? data.get(k)! : null),
    key: (i) => [...data.keys()][i] ?? null,
    removeItem: (k) => (guard(), void data.delete(k)),
    setItem: (k, v) => (guard(), void data.set(k, String(v))),
  };
}

/** fetch che risponde col Navidrome finto. */
export function fakeFetch(server: FakeNavidrome): typeof fetch {
  return (async (input: RequestInfo | URL) => {
    const url = typeof input === "string" ? input : input instanceof URL ? input.href : input.url;
    return new Response(JSON.stringify(server.handle(url)), { headers: { "content-type": "application/json" } });
  }) as typeof fetch;
}

export const CREDS = { url: "http://nd.test", user: "b", salt: "s", token: "t" };
