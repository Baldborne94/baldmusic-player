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

1. **Server (facoltativo ma consigliato):** segui [`server/README.md`](server/README.md). Si installa Navidrome sul PC e Tailscale su PC e telefono, in circa 15 minuti.
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

## In macchina

- Le schede **Telefono** e **Server** raccolgono album, artisti, playlist, preferiti e mix casuale.
- Toccando un brano parte il suo album (o la sua playlist) da quel punto.
- La 🔍 cerca in tutte e due le librerie. Funzionano anche i comandi vocali: *"Ok Google, metti Lucio Dalla su BaldMusic"*.
- Alla riaccensione dell'auto si può riprendere dall'ultimo brano ascoltato.

## Sviluppo

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
