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

I tag (titolo, artista, album, anno) e le copertine vengono letti **dai file**: l'app mostra quello che c'è scritto dentro. Se i brani di un album compaiono come tanti album separati, o un brano del 2005 finisce negli "Anni 70", il problema è nei tag, non nell'app. Vedi [Sistemare i tag una volta per tutte](#sistemare-i-tag-una-volta-per-tutte): bastano dieci minuti e vale per telefono, PC e auto.

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

## Sistemare i tag una volta per tutte

Molti file scaricati da internet hanno il campo **album** uguale al titolo della canzone e un **anno** a caso. Il risultato è una libreria piena di "album" da un brano solo, con la copertina giusta ma il nome sbagliato, e decenni che non tornano. BaldMusic raccoglie questi brani sotto l'artista in *Singoli e brani sparsi*, ma il modo giusto per avere album completi, in ordine e con l'anno corretto è correggere i file.

Lo fa in automatico **[MusicBrainz Picard](https://picard.musicbrainz.org/)**, gratuito e open source: riconosce ogni brano **dall'audio** (impronta acustica), non dal nome del file, e scrive nei tag artista, album, numero di traccia, anno e copertina presi dal database MusicBrainz.

1. Installa Picard e aprilo. In *Opzioni → Opzioni → Copertine* spunta **Incorpora le copertine nei tag**.
2. Trascina nella finestra la cartella della musica (la stessa di Navidrome).
3. In alto premi **Raggruppa** (Cluster), poi **Scansiona** (Scan): Picard ascolta i file e li abbina agli album giusti. Con molte canzoni ci vuole qualche minuto.
4. Sulla destra compaiono gli album ricostruiti. Quelli **dorati** sono completi; apri a campione un paio di quelli con il punto rosso o arancione per controllare che l'abbinamento sia giusto (succede con i remix o le versioni live).
5. Seleziona tutto e premi **Salva**. Picard riscrive i tag dentro ai file: la musica non si sposta e non si perde nulla.
6. In Navidrome: in alto a destra, l'icona *Attività* (la linea a zig-zag) → **Scansione completa** (oppure aspetta la scansione automatica). Gli album si ricompongono da soli, su tutti i dispositivi.

Due accorgimenti che evitano i casi più comuni:

- **Niente raccolte né singoli.** In *Opzioni → Metadati → Pubblicazioni preferite* lascia solo *Album* tra i preferiti e metti *Raccolta*, *Singolo*, *Dal vivo* e *Remix* tra gli scoraggiati. Senza, un brano scaricato da solo finisce nel primo "Greatest Hits" che lo contiene.
- **Mai *Cerca* su tutto.** *Cerca* abbina per titolo, non per audio, e con titoli generici sbaglia artista. Usalo solo dopo *Analizza*, sui file rimasti, con *Opzioni → Avanzate → Corrispondenze → Similarità minima per la ricerca dei file* almeno al 90%.
- **Lo stesso album due volte.** Se in Navidrome un disco compare sdoppiato (due *Mezmerize* con la stessa copertina), Picard ha abbinato brani diversi a edizioni diverse. Aggiungi `GroupAlbumReleases = true` nella sezione `[Scanner]` di `navidrome.toml` (vedi l'esempio) e riavvia il servizio: le edizioni si fondono in una scheda sola.

Se un brano resta non riconosciuto (cover amatoriali, registrazioni proprie), selezionalo e digita tu album e anno nel pannello in basso: anche quello si salva nel file.

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
| Tanti album da un brano solo, decenni sbagliati | Tag dei file: segui [Sistemare i tag una volta per tutte](#sistemare-i-tag-una-volta-per-tutte) |
| L'app web non si collega ma quella Android sì | L'app web vuole l'indirizzo `https://…ts.net` (o `http://localhost:4533` sul PC stesso), non `http://192.168…` |
| Chrome chiede di "accedere ad altre app e servizi su questo dispositivo" | È la richiesta per raggiungere Navidrome su `localhost`: rispondi *Consenti* |
