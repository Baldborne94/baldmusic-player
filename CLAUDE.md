# BaldMusic — contesto del progetto

Player musicale open source personale. Tre pezzi, un'unica libreria:

- `server/` — documentazione + config per **Navidrome** sul PC di casa (API Subsonic), raggiunto da fuori con **Tailscale** (`tailscale serve` → `https://<pc>.<tailnet>.ts.net`). Non scriviamo un backend nostro: Navidrome fa scansione, tag, copertine, transcodifica.
- `web/` — SPA **Vite + React + TypeScript**, hash router, pubblicata su GitHub Pages. Parla Subsonic direttamente col server (Navidrome manda gli header CORS). Pagina https ⇒ il server dev'essere https (`…ts.net`), mai http.
- `android/` — app **nativa Kotlin** (Compose + **Media3**). Due sorgenti: MediaStore (file sul telefono) e Navidrome. **Android Auto** arriva da `MediaLibraryService`: le app WebView/Capacitor non sono visibili ad Auto, per questo l'app è nativa.

## Architettura Android
- `:core` (JVM puro, testabile senza SDK): `SubsonicClient`, `MediaId`, `LibraryTree`, `RemoteSource`.
- `:app`: `LocalSource` (MediaStore), `PlaybackService` (ExoPlayer + MediaLibrarySession), `ArtworkProvider`, UI Compose.
- **Un solo albero** (`LibraryTree`) serve sia l'app sia Auto: la UI sfoglia con un `MediaBrowser` come farebbe l'auto. Non aggiungere percorsi paralleli alla libreria solo per la UI.
- `MediaId` = `sorgente/tipo/id|contenitore`: un brano porta con sé il contenitore da cui è stato scelto, e `onSetMediaItems` ricostruisce la coda (album dal brano toccato in poi).
- I controller non passano l'URI dei brani (Media3 lo toglie): il servizio lo risolve sempre in `onSetMediaItems`/`onAddMediaItems`.
- Copertine sempre via `content://<pkg>.artwork/<sorgente>/<id>` (`ArtworkProvider`): Android Auto non carica http né legge MediaStore per noi.

## Build e verifica
- Web: `cd web && npm ci && npm run typecheck && npm test && npm run build`.
- Android: nessun SDK in locale nelle sessioni cloud (dl.google.com bloccato). `:core` si testa in locale con un progetto Gradle che include solo `core`; `:app` lo compila GitHub Actions (`.github/workflows/android.yml`) — leggi i log del job per gli errori.
- L'APK è firmato con la chiave debug ripristinata dal secret `ANDROID_DEBUG_KEYSTORE_B64`: non cambiare chiave, gli aggiornamenti richiederebbero di disinstallare.

## Regole
- Testi dell'interfaccia e commenti in italiano.
- Credenziali Subsonic: solo `salt` + `token = md5(password + salt)`, mai la password in chiaro salvata.
- Non mettere `toISOString()`/UTC dove serve l'ora locale; non introdurre un backend nostro se Navidrome lo fa già.
- Nessun push diretto su `main`: branch + PR.
