# BaldMusic — contesto del progetto

Player musicale open source personale. Tre pezzi, un'unica libreria:

- `server/` — documentazione + config per **Navidrome** sul PC di casa (API Subsonic), raggiunto da fuori con **Tailscale** (`tailscale serve` → `https://<pc>.<tailnet>.ts.net`). Non scriviamo un backend nostro: Navidrome fa scansione, tag, copertine, transcodifica.
- `web/` — SPA **Vite + React + TypeScript** in stile Spotify desktop (barra laterale con libreria, pannello destro con testo/coda, barra in basso), hash router, pubblicata su GitHub Pages. Preferiti e playlist sono quelli di **Navidrome** (`lib/library.ts`), testi da LRCLIB (`lib/lyrics.ts`), colori dalle copertine via canvas (`lib/colors.ts`). Parla Subsonic direttamente col server (Navidrome manda gli header CORS). Pagina https ⇒ il server dev'essere https (`…ts.net`); unica eccezione `http://localhost`/`127.x`, che i browser non trattano come contenuto misto (`blockedAsMixedContent`).
- `android/` — app **nativa Kotlin** (Compose + **Media3**). Due sorgenti: MediaStore (file sul telefono) e Navidrome. **Android Auto** arriva da `MediaLibraryService`: le app WebView/Capacitor non sono visibili ad Auto, per questo l'app è nativa.

**Come la usa l'utente**: musica in locale su ogni dispositivo (telefono/tablet Android → MediaStore; PC → Navidrome su `localhost`) finché non arriva un NAS. I file sono già passati da Picard: i tag sono affidabili, il lavoro è leggerli tutti. Il piano è in `ROADMAP.md`.

## Architettura Android
- `:core` (JVM puro, testabile senza SDK): `SubsonicClient`, `MediaId`, `LibraryTree`, `RemoteSource`, `LocalCatalog` (le regole della musica sul telefono: album, singoli, artisti, decenni, cartelle, radio), `UserLibrary` + `PersonalSource` (mi piace, playlist, cronologia), `ListeningStats`, testi (`parseLrc`, `LrcLibClient`).
- `:app`: `LocalSource` (legge MediaStore e passa i brani a `LocalCatalog`), `PlaybackService` (ExoPlayer + MediaLibrarySession, cuore in Auto, timer, conteggio ascolti, widget), `UserData` (JSON della tua musica), `LyricsRepository`, `ArtworkProvider`, `PlayerWidget`, UI Compose stile Spotify (`ui/`).
- **Album veri vs singoli**: un "album" con un solo brano è quasi sempre un tag sbagliato (album = titolo). In entrambe le sorgenti e nell'app web non va tra gli album: finisce in "Singoli e brani sparsi" (sezione propria e sotto l'artista). La cura vera sono i tag (guida Picard in `server/README.md`), non euristiche più furbe.
- **Niente equalizzatore, effetti, velocità o bitrate ridotto**: scelta dell'utente, si ascolta il file originale e basta. Non reintrodurli. Il **ReplayGain** invece sì (scelta dell'utente): solo volume, letto dai tag, mai oltre il picco.
- **Root della UI in una `Surface`** (MainActivity): senza, `LocalContentColor` resta nero e ogni `Text` senza colore sparisce sullo sfondo scuro.
- **La tua musica** è una sorgente (`me/...`) come le altre: niente cache nell'albero (`cacheable = false`), i brani restano della loro sorgente e si risolvono lì. Le regole stanno in `UserLibrary` (pure, testate): l'app salva solo il JSON.
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
- Nei test Kotlin niente lettere accentate nei nomi con i backtick: il compilatore locale fallisce sul nome del file .class.
- Credenziali Subsonic: solo `salt` + `token = md5(password + salt)`, mai la password in chiaro salvata.
- Non mettere `toISOString()`/UTC dove serve l'ora locale; non introdurre un backend nostro se Navidrome lo fa già.
- Nessun push diretto su `main`: branch + PR.
