# BaldMusic

Il mio player musicale open source. Ascolto la mia musica dal **telefono**, dal **tablet**, dal **PC** e in **macchina con Android Auto**.

| | Dove | Cosa fa |
|-|------|---------|
| 📱 **App Android** | [`android/`](android) | Suona la musica salvata **sul telefono** (e sul tablet) e quella del **server di casa**. Funziona con **Android Auto**, con la notifica, la schermata di blocco e i tasti delle cuffie. Si aggiorna da sola. |
| 💻 **App web** | [`web/`](web) | Si apre nel browser su PC e tablet e si può installare come app. Legge la libreria del server di casa. |
| 🖥️ **Server** | [`server/`](server) | [Navidrome](https://www.navidrome.org) sul PC di casa: indicizza la musica e la trasmette ovunque tramite [Tailscale](https://tailscale.com). |

```
                     ┌───────────────────────────── Android Auto
   musica sul        │
   telefono ───▶  App Android ◀──┐
                                 │  API Subsonic
   PC di casa:                   │  (via Tailscale)
   Musica ─▶ Navidrome ──────────┼──▶ App web (PC, tablet)
```

## Per iniziare

1. **Server (facoltativo ma consigliato):** segui [`server/README.md`](server/README.md). Si installa Navidrome sul PC e Tailscale su PC e telefono, in circa 15 minuti. Se gli album escono "spezzati" in tanti brani singoli, la causa sono i tag dei file: la guida spiega come sistemarli una volta per tutte con Picard.
2. **App Android:** dal telefono apri la [release `apk-latest`](https://github.com/Baldborne94/baldmusic-player/releases/tag/apk-latest), scarica `baldmusic.apk` e installalo. Al primo avvio concedi l'accesso alla musica. Per la musica del PC vai in *Impostazioni → Server Navidrome*. Da lì in poi l'app si aggiorna da sola: quando esce una versione nuova compare un avviso in Home (o *Impostazioni → Aggiornamenti*) con le novità e il tasto **Aggiorna**; la prima volta Android chiede di consentire l'installazione da BaldMusic.
3. **Android Auto:** l'app non arriva dal Play Store, quindi Android Auto la mostra solo dopo aver attivato le *origini sconosciute*:
   1. apri le impostazioni di Android Auto;
   2. tocca 10 volte **Versione** per sbloccare le impostazioni sviluppatore;
   3. dal menu ⋮ apri **Impostazioni sviluppatore** e attiva **Origini sconosciute**;
   4. ricollega il telefono all'auto. BaldMusic compare tra le app musicali.
4. **App web (PC e tablet):** apri <https://baldborne94.github.io/baldmusic-player/>.
   - **Sul PC dove gira Navidrome** accedi con `http://localhost:4533` e le credenziali di Navidrome. Per avere le **raccolte dalle cartelle** come sul telefono, in Navidrome attiva una volta *Report Real Path* per il lettore *baldmusic* (vedi [`server/README.md`](server/README.md#raccolte-dalle-cartelle-anche-sul-pc)).
   - **Su altri dispositivi** (tablet, un altro PC) usa l'indirizzo `https://…ts.net` di Tailscale.
   - **Per averla come programma**: in Chrome o Edge clicca l'icona *Installa* (⊕) nella barra degli indirizzi. BaldMusic si apre nella sua finestra, ha l'icona sulla barra delle applicazioni e risponde ai tasti multimediali della tastiera.

## Le cartelle sono le raccolte

La musica è ordinata in cartelle (`Music/Disney Italia`, `Music/Movie Soundtracks`…): ogni cartella diventa una **raccolta**, prima di tutto il resto in Home, in Libreria e in Android Auto. I brani nuovi della cartella entrano da soli; per il resto è una playlist (rinomina, aggiungi, togli, riordina trascinando, ordina per titolo o artista). Album, artisti e decenni dai tag restano come secondo modo di sfogliare.

## Cosa fa l'app Android (telefono e tablet)

- **Home** con accessi rapidi, raccolte, ascoltati di recente, i tuoi mix (Riscopri, Per la macchina, mix degli artisti e dei decenni: gli stessi in Android Auto), aggiunti di recente, più ascoltati.
- **Brani che ti piacciono**, **playlist** e **raccolte**, per la musica del telefono e del server. Pressione lunga su un brano per selezionarne più di uno.
- **Artisti riconosciuti** dai tag (anche "A feat. B"), album veri separati da singoli e brani sparsi, discografia per tipo (Album, EP, Dal vivo, Colonne sonore, Compilation), album su più dischi, elenchi con le lettere A–Z.
- **Volume uguale tra i brani**: ReplayGain dai tag e, per i file che non l'hanno, volume misurato dall'app mentre il telefono è in carica.
- **Dissolvenza tra i brani** come i DJ (3–12 secondi, mai dentro lo stesso album).
- **Testi sincronizzati** stile karaoke (da [LRCLIB](https://lrclib.net)), tocca una riga per saltarci; quelli dei brani che ascolti di più, dei preferiti e delle raccolte si scaricano prima, in carica col Wi‑Fi, e ci sono anche senza rete.
- **Radio** da qualsiasi brano, **timer di spegnimento**, **coda** riordinabile che si salva per intero e riparte da dov'era.
- **Pulizia della libreria**: doppioni tra le cartelle e anteprime di 30 secondi nel cestino di Android (recuperabili per 30 giorni da *Brani nel cestino*), senza perdere il brano nelle raccolte.
- **Informazioni sul brano** (menu ⋮) e **Salute della libreria** (Impostazioni): i tag come li legge l'app, e cartella per cartella quali file hanno i tag da sistemare e perché un brano è finito tra gli sparsi.
- **Riconosci i brani dal suono** (Impostazioni): per i file scaricati coi tag sbagliati l'app ascolta i primi due minuti e trova con [AcoustID](https://acoustid.org) e MusicBrainz titolo, artista, album, anno e numero di traccia veri. Le proposte si controllano cartella per cartella e si applicano (o si annullano) senza toccare i file. Serve una chiave gratuita di AcoustID, una volta.
- **Backup della tua musica**: preferiti, playlist, raccolte e ascolti si salvano da soli in `Download/BaldMusic` (restano anche se disinstalli l'app) o dove vuoi tu; su un telefono nuovo *Impostazioni → Ripristina…* li rimette, ritrovando i brani per cartella e titolo.
- **Tablet**: schede a sinistra; in orizzontale libreria e dettaglio affiancati, coda o testo fissi a destra.
- **Le tue statistiche**, **widget**, condivisione, **aggiornamenti dall'app** con le novità.

## Cosa fa l'app web (PC e tablet)

- Interfaccia come Spotify sul PC: barra laterale con la tua libreria, pagine colorate dalla copertina, pannello "In riproduzione" con testo e coda.
- **Raccolte dalle cartelle** come sul telefono, **Brani che ti piacciono** e **playlist** salvati su Navidrome: gli stessi su ogni dispositivo collegato al server.
- **Trascina** uno o più brani su una raccolta, una playlist o "Brani che ti piacciono" nella barra laterale; nelle pagine di raccolte e playlist selezione multipla (Maiuscolo + clic), riordino trascinando, ordine per titolo o artista.
- **Pulizia** (doppioni e anteprime) e **Brani nel cestino**: il browser non tocca i file, quindi l'app scarica uno script che si apre col doppio clic e sposta i file nel **Cestino di Windows** (o li rimette al loro posto).
- **Volume uguale** (ReplayGain), **dissolvenza tra i brani**, **testi sincronizzati**, **radio**, **timer di spegnimento**.
- Tasto destro su un brano per il menu; scorciatoie: <kbd>Spazio</kbd> play/pausa, <kbd>Shift</kbd>+<kbd>←/→</kbd> brano, <kbd>←/→</kbd> 10 secondi, <kbd>S</kbd> casuale, <kbd>R</kbd> ripeti, <kbd>F</kbd> schermo intero, <kbd>/</kbd> cerca.

## In macchina

- Le schede **La tua musica**, **Telefono** e **Server** raccolgono raccolte, preferiti, playlist, album, artisti e mix; gli elenchi lunghi sono divisi per lettera.
- Accanto ai comandi: **cuore**, **casuale** e **ripeti**.
- Toccando un brano parte il suo album (o la sua raccolta) da quel punto.
- La 🔍 cerca in tutte e due le librerie. Funzionano anche i comandi vocali: *"Ok Google, metti Lucio Dalla su BaldMusic"*, *"metti la raccolta Movie Soundtracks su BaldMusic"*.
- Alla riaccensione dell'auto riparte la stessa coda, dallo stesso punto.

## Sviluppo

Il piano di lavoro, fase per fase, è in [`ROADMAP.md`](ROADMAP.md); le prove da fare a mano su telefono, tablet, auto e PC sono in [`COLLAUDO.md`](COLLAUDO.md).

| | Requisiti | Comandi |
|-|-----------|---------|
| Web | Node 22 | `cd web && npm ci && npm run dev` · `npm test` · `npm run build` |
| Android | JDK 21, SDK Android (o solo GitHub Actions) | `cd android && ./gradlew :core:test :app:assembleRelease` |

Non serve installare Android Studio: ogni push compila l'APK su GitHub Actions ([`android.yml`](.github/workflows/android.yml)). Su `main` l'APK viene pubblicato nella release `apk-latest` insieme a `version.json` (numero di versione e novità, cioè i titoli delle PR unite: [`apk_version.py`](.github/scripts/apk_version.py)), che l'app legge per aggiornarsi; l'app web va su GitHub Pages ([`web.yml`](.github/workflows/web.yml)).

Configurazione una tantum del repository:

- **Settings → Pages → Source: GitHub Actions**, per pubblicare l'app web.
- **Settings → Secrets → Actions → `ANDROID_DEBUG_KEYSTORE_B64`**: una chiave di firma fissa in base64. Senza, ogni APK ha una firma diversa e per aggiornare bisogna disinstallare.

## Licenza

[MIT](LICENSE), tranne [`Chromaprint.kt`](android/core/src/main/kotlin/io/github/baldborne94/baldmusic/core/acoustid/Chromaprint.kt): è il porting in Kotlin di [Chromaprint](https://github.com/acoustid/chromaprint) (MIT, Lukas Lalinsky) con la FFT di KISS FFT (BSD-3-Clause) e il ricampionatore di FFmpeg (LGPL 2.1), quindi quel file è sotto **LGPL 2.1**. I dettagli sono in testa al file.
