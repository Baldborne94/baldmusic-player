# Il server sul PC di casa

BaldMusic legge la musica da **[Navidrome](https://www.navidrome.org)**, un server musicale open source. Gira sul PC di casa e "trasmette" i brani a telefono, tablet, browser e auto. La musica resta sul PC: niente cloud, niente abbonamenti.

```
 PC di casa                                   ovunque
┌──────────────────────┐                ┌──────────────────────────┐
│ cartella Musica      │                │ app Android ── Android Auto
│        │             │   Tailscale    │ app web (PC, tablet)      │
│   Navidrome :4533 ───┼── rete privata ┼─▶                         │
└──────────────────────┘                └──────────────────────────┘
```

Servono tre passi, da fare una volta sola.

## 1. Copia la musica sul PC

Se oggi la musica è solo sul telefono, collega il telefono al PC col cavo USB e copia la cartella `Music` (o dove tieni i file) in una cartella del PC, ad esempio `C:\Users\<tuo-utente>\Music`.

I tag (titolo, artista, album) e le copertine vengono letti dai file. Se un album appare con il nome sbagliato, il problema è nei tag del file. [MusicBrainz Picard](https://picard.musicbrainz.org/) li sistema in automatico.

## 2. Installa Navidrome

### Windows (consigliato)

1. Scarica l'installer `.msi` per Windows dall'ultima [release di Navidrome](https://github.com/navidrome/navidrome/releases/latest) (`navidrome_..._windows_amd64_installer.msi`).
2. Avvialo. Quando lo chiede, indica la cartella della musica e lascia la porta `4533`. Navidrome viene installato come servizio, quindi parte da solo all'accensione del PC.
3. Apri <http://localhost:4533> e crea l'utente amministratore. **Sono le credenziali che userai in BaldMusic.**
4. Attendi la prima scansione: la libreria si riempie da sola.

Per cambiare la cartella in seguito, modifica `MusicFolder` nel file `navidrome.toml` (c'è un esempio in [`navidrome.toml`](navidrome.toml)), poi riavvia il servizio "Navidrome" da *Servizi* di Windows.

> **Il PC deve restare acceso** (o almeno non andare in sospensione) quando vuoi ascoltare da fuori. In *Impostazioni → Sistema → Alimentazione* imposta la sospensione su "Mai" quando è collegato alla corrente.

### Linux, NAS o Docker

Usa [`docker-compose.yml`](docker-compose.yml): cambia il percorso della musica e lancia `docker compose up -d`.

### Conversione per la rete mobile (facoltativa)

Se installi [ffmpeg](https://www.gyan.dev/ffmpeg/builds/) sul PC, Navidrome può convertire i brani al volo a un bitrate più basso. Nell'app Android lo scegli in *Impostazioni → Qualità in streaming* e consumi meno dati in macchina.

## 3. Raggiungilo da fuori casa con Tailscale

A casa, sulla stessa Wi-Fi, il server risponde su `http://<IP-del-PC>:4533` (es. `http://192.168.1.20:4533`). Fuori casa usiamo **[Tailscale](https://tailscale.com)**: una rete privata gratuita tra i tuoi dispositivi. Non si aprono porte sul router e nessun altro può vedere il server.

1. Crea un account su <https://tailscale.com> (gratis per uso personale).
2. Installa Tailscale **sul PC** e **sul telefono** (Play Store), e sul tablet se lo usi. Accedi con lo stesso account.
3. Nella [console di Tailscale](https://login.tailscale.com/admin/dns) attiva **MagicDNS** e **HTTPS Certificates**.
4. Sul PC apri il *Prompt dei comandi* ed esegui:

   ```
   tailscale serve --bg 4533
   ```

   Il comando stampa un indirizzo del tipo **`https://nome-pc.tail1234.ts.net`**: è l'indirizzo del tuo server, valido ovunque.

Quale indirizzo usare:

| Dove | App Android | App web (browser) |
|------|-------------|-------------------|
| Sul PC dove gira Navidrome | — | `http://localhost:4533` |
| Ovunque, con Tailscale attivo | `https://nome-pc.tail1234.ts.net` | `https://nome-pc.tail1234.ts.net` |
| Solo a casa, senza Tailscale | `http://192.168.x.x:4533` | — (il browser blocca l'http da una pagina https, tranne `localhost`) |

In auto il telefono usa i dati mobili: lascia Tailscale attivo in background (*Impostazioni → VPN → sempre attiva*) e la musica arriva anche lì.

### In alternativa: Tailscale Funnel

`tailscale funnel --bg 4533` rende il server raggiungibile da **internet**, senza Tailscale sui dispositivi. È comodo, ma il server diventa pubblico, protetto solo dalla password di Navidrome. Usa una password robusta. Se non ti serve, preferisci `serve`.

## Problemi comuni

| Sintomo | Causa probabile |
|---------|-----------------|
| "Server non raggiungibile" | PC spento o in sospensione, oppure Tailscale spento sul telefono |
| "Wrong username or password" | Credenziali diverse da quelle create su <http://localhost:4533> |
| La libreria è vuota | Cartella sbagliata in `MusicFolder`, oppure la scansione non è ancora finita |
| Album spezzati o senza copertina | Tag dei file incompleti: sistemali con MusicBrainz Picard |
| L'app web non si collega ma quella Android sì | L'app web vuole l'indirizzo `https://…ts.net` (o `http://localhost:4533` sul PC stesso), non `http://192.168…` |
| Chrome chiede di "accedere ad altre app e servizi su questo dispositivo" | È la richiesta per raggiungere Navidrome su `localhost`: rispondi *Consenti* |
