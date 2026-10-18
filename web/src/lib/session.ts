import { useSyncExternalStore } from "react";
import { SubsonicClient, type Credentials } from "./subsonic";

// Le credenziali (url, utente, sale, token) vivono in localStorage: il token
// è md5(password + sale), quindi la password in chiaro non resta sul dispositivo.

const KEY = "baldmusic.credentials";
const listeners = new Set<() => void>();

function read(): SubsonicClient | null {
  try {
    const raw = localStorage.getItem(KEY);
    if (!raw) return null;
    const c = JSON.parse(raw) as Credentials;
    return c.url && c.user && c.token && c.salt ? new SubsonicClient(c) : null;
  } catch {
    return null;
  }
}

let client = read();

export function saveCredentials(creds: Credentials | null): void {
  try {
    if (creds) localStorage.setItem(KEY, JSON.stringify(creds));
    else localStorage.removeItem(KEY);
  } catch {
    // Archiviazione bloccata (navigazione privata): la sessione dura finché resta aperta la scheda.
  }
  client = creds ? new SubsonicClient(creds) : null;
  listeners.forEach((l) => l());
}

export function getClient(): SubsonicClient | null {
  return client;
}

export function useClient(): SubsonicClient | null {
  return useSyncExternalStore(
    (l) => {
      listeners.add(l);
      return () => listeners.delete(l);
    },
    () => client,
  );
}
