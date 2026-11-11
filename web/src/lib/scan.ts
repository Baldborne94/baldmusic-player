import { SubsonicError, type ScanStatus } from "./subsonic";

// «Aggiorna libreria» dall'app web: Navidrome rilegge la cartella della musica (come «Quick
// Scan» o «Full Scan» nella sua pagina), l'app aspetta che finisca e poi riallinea le raccolte.

/** Ogni quanto si chiede a Navidrome a che punto è. */
export const SCAN_POLL_MS = 1500;
/** Oltre così si smette di aspettare (la scansione continua da sola su Navidrome). */
export const SCAN_MAX_MS = 30 * 60 * 1000;

/** Il codice di Subsonic per «non hai il permesso»: la scansione la fa partire solo un amministratore. */
const NOT_AUTHORIZED = 50;

export class ScanError extends Error {}

interface Scanner {
  startScan(full: boolean): Promise<ScanStatus>;
  scanStatus(): Promise<ScanStatus>;
}

/**
 * Fa partire la scansione e aspetta che finisca; [onProgress] riceve i brani letti finora.
 * Restituisce quanti brani ha letto. [sleep] e [now] si cambiano nei test.
 */
export async function scanAndWait(
  c: Scanner,
  full: boolean,
  onProgress: (count: number) => void = () => {},
  sleep: (ms: number) => Promise<void> = (ms) => new Promise((r) => setTimeout(r, ms)),
  now: () => number = Date.now,
): Promise<number> {
  let status: ScanStatus;
  try {
    status = await c.startScan(full);
  } catch (e) {
    throw new ScanError(scanErrorMessage(e));
  }
  const started = now();
  onProgress(status.count);
  // Navidrome può rispondere «non sta scansionando» prima di aver cominciato: si guarda almeno una volta.
  let first = true;
  while (status.scanning || first) {
    first = false;
    if (now() - started > SCAN_MAX_MS) throw new ScanError("La scansione ci mette tanto: continua su Navidrome, riprova più tardi.");
    await sleep(SCAN_POLL_MS);
    try {
      status = await c.scanStatus();
    } catch (e) {
      throw new ScanError(scanErrorMessage(e));
    }
    onProgress(status.count);
  }
  return status.count;
}

/** Il messaggio da mostrare quando la scansione non parte. */
export function scanErrorMessage(e: unknown): string {
  if (e instanceof SubsonicError && e.code === NOT_AUTHORIZED) {
    return "Solo un amministratore di Navidrome può far partire la scansione: entra con quell'utente, o usa Activity → Quick Scan nella pagina di Navidrome.";
  }
  return e instanceof Error ? e.message : String(e);
}
