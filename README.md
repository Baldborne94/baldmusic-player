# BaldMusic

Il mio player musicale open source. Ascolto la mia musica dal **telefono**, dal **tablet**, dal **PC** e in **macchina con Android Auto**.

| | Dove | Cosa fa |
|-|------|---------|
| 📱 **App Android** | [`android/`](android) | Suona la musica salvata **sul telefono** e quella del **server di casa**. Funziona con **Android Auto**, con la notifica, la schermata di blocco e i tasti delle cuffie. |
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
2. **App Android:** dal telefono apri la [release `apk-latest`](https://github.com/Baldborne94/baldmusic-player/releases/tag/apk-latest), scarica `baldmusic.apk` e installalo. Al primo avvio concedi l'accesso alla musica. Per la musica del PC vai in *Impostazioni → Collega server*.
3. **Android Auto:** l'app non arriva dal Play Store, quindi Android Auto la mostra solo dopo aver attivato le *origini sconosciute*:
   1. apri le impostazioni di Android Auto;
   2. tocca 10 volte **Versione** per sbloccare le impostazioni sviluppatore;
   3. dal menu ⋮ apri **Impostazioni sviluppatore** e attiva **Origini sconosciute**;
   4. ricollega il telefono all'auto. BaldMusic compare tra le app musicali.
4. **App web (PC e tablet):** apri <https://baldborne94.github.io/baldmusic-player/>.
   - **Sul PC dove gira Navidrome** accedi con `http://localhost:4533` e le credenziali di Navidrome.
   - **Su altri dispositivi** (tablet, un altro PC) usa l'indirizzo `https://…ts.net` di Tailscale.
   - **Per averla come programma**: in Chrome o Edge clicca l'icona *Installa* (⊕) nella barra degli indirizzi. BaldMusic si apre nella sua finestra, ha l'icona sulla barra delle applicazioni e risponde ai tasti multimediali della tastiera.

## Cosa fa l'app web (PC e tablet)

- Interfaccia come Spotify sul PC: barra laterale con la tua libreria, pagine colorate dalla copertina, pannello "In riproduzione" con testo e coda.
- **Home** con accessi rapidi, i tuoi mix (casuale, preferiti, per genere, per decennio), ascoltati di recente, più ascoltati, i tuoi artisti.
- **Brani che ti piacciono** e **playlist** salvati su Navidrome: gli stessi su ogni dispositivo collegato al server.
- **Testi sincronizzati**, **radio** da un brano, **timer di spegnimento**, coda riordinabile trascinando.
- Tasto destro su un brano per il menu; scorciatoie: <kbd>Spazio</kbd> play/pausa, <kbd>Shift</kbd>+<kbd>←/→</kbd> brano, <kbd>F</kbd> schermo intero, <kbd>/</kbd> cerca.

## Cosa fa l'app Android

- **Home** con accessi rapidi, ascoltati di recente, i tuoi mix (casuale, preferiti, per decennio, per artista), aggiunti di recente, più ascoltati.
- **Brani che ti piacciono** e **playlist tue**, che valgono per la musica del telefono e del server.
- **Testi sincronizzati** stile karaoke (da [LRCLIB](https://lrclib.net)), tocca una riga per saltarci.
- **Radio** da qualsiasi brano: lui e poi musica simile.
- **Timer di spegnimento** (con dissolvenza o a fine brano), **coda** riordinabile, scorri un brano a destra per metterlo in coda.
- **Le tue statistiche**: minuti ascoltati, artisti, brani e album preferiti.
- **Widget** per la schermata home, cartelle, decenni, condivisione.

## In macchina

- Le schede **La tua musica**, **Telefono** e **Server** raccolgono preferiti, playlist, album, artisti e mix.
- Il **cuore** accanto ai comandi aggiunge il brano a quelli che ti piacciono.
- Toccando un brano parte il suo album (o la sua playlist) da quel punto.
- La 🔍 cerca in tutte e due le librerie. Funzionano anche i comandi vocali: *"Ok Google, metti Lucio Dalla su BaldMusic"*.
- Alla riaccensione dell'auto si può riprendere dall'ultimo brano ascoltato.

## Sviluppo

Il piano di lavoro, fase per fase, è in [`ROADMAP.md`](ROADMAP.md).

| | Requisiti | Comandi |
|-|-----------|---------|
| Web | Node 22 | `cd web && npm ci && npm run dev` · `npm test` · `npm run build` |
| Android | JDK 21, SDK Android (o solo GitHub Actions) | `cd android && ./gradlew :core:test :app:assembleRelease` |

Non serve installare Android Studio: ogni push compila l'APK su GitHub Actions ([`android.yml`](.github/workflows/android.yml)). Su `main` l'APK viene pubblicato nella release `apk-latest` e l'app web su GitHub Pages ([`web.yml`](.github/workflows/web.yml)).

Configurazione una tantum del repository:

- **Settings → Pages → Source: GitHub Actions**, per pubblicare l'app web.
- **Settings → Secrets → Actions → `ANDROID_DEBUG_KEYSTORE_B64`**: una chiave di firma fissa in base64. Senza, ogni APK ha una firma diversa e per aggiornare bisogna disinstallare.

## Licenza

[MIT](LICENSE)
