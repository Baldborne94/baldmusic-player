import { useState } from "react";
import { makeCredentials, SubsonicClient, SubsonicError } from "../lib/subsonic";
import { saveCredentials } from "../lib/session";

export function Login() {
  const [url, setUrl] = useState("");
  const [user, setUser] = useState("");
  const [password, setPassword] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    const creds = makeCredentials(url, user, password);
    // Pagina in https + server in http: il browser blocca la richiesta senza dire perché.
    if (location.protocol === "https:" && creds.url.startsWith("http:")) {
      setError("Questa pagina è in HTTPS, quindi anche il server deve esserlo. Usa l'indirizzo https://….ts.net di Tailscale (vedi la guida del server).");
      setBusy(false);
      return;
    }
    try {
      await new SubsonicClient(creds).ping();
      saveCredentials(creds);
    } catch (err) {
      setError(err instanceof SubsonicError ? err.message : "Accesso non riuscito");
    } finally {
      setBusy(false);
    }
  };

  return (
    <main className="login">
      <form className="login__card" onSubmit={submit}>
        <img src="./icon.svg" alt="" width={72} height={72} />
        <h1>BaldMusic</h1>
        <p className="muted">Collegati al tuo server Navidrome.</p>
        <label>
          Indirizzo del server
          <input value={url} onChange={(e) => setUrl(e.target.value)} placeholder="https://pc-di-casa.tail1234.ts.net" inputMode="url" autoComplete="url" required />
        </label>
        <label>
          Utente
          <input value={user} onChange={(e) => setUser(e.target.value)} autoComplete="username" autoCapitalize="none" required />
        </label>
        <label>
          Password
          <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="current-password" required />
        </label>
        {error && <p className="error">{error}</p>}
        <button className="btn btn--primary" disabled={busy}>
          {busy ? "Connessione…" : "Entra"}
        </button>
      </form>
    </main>
  );
}
