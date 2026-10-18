# Piano di lavoro

L'obiettivo: **un player fatto bene** per telefono (anche in **Android Auto**), tablet e PC, che
riconosca gli artisti, metta i brani nell'ordine giusto e renda piacevole ascoltare la propria musica.

Si procede a piccoli passi: **una fase alla volta, una PR per voce**, ognuna con i suoi test e
provata sul telefono prima di passare alla successiva. Le voci si spuntano qui quando la PR entra in `main`.

## Dove siamo

La prima stesura funziona già: app Android nativa con Android Auto, app web stile Spotify,
server Navidrome raggiungibile con Tailscale, testi sincronizzati, preferiti, playlist, radio,
statistiche, widget. I punti deboli emersi leggendo il codice:

| Area | Oggi | Problema |
|------|------|----------|
| **Artisti (telefono)** | `LocalSource` raggruppa per `ARTIST_ID` di MediaStore | "Vasco Rossi feat. X" è un artista diverso da "Vasco Rossi"; l'*artista dell'album* non viene letto, quindi le compilation e gli album con ospiti si spezzano sotto tanti artisti |
| **Artisti (server)** | Si usa solo il campo `artist` | Navidrome manda già gli artisti multipli (`artists`, `albumArtists`, `displayArtist`) e il tipo di uscita (album, EP, singolo, raccolta): li ignoriamo |
| **Album (telefono)** | Raggruppati per `ALBUM_ID` di MediaStore | Dipende da come Android indicizza: album omonimi di artisti diversi o lo stesso album in due cartelle possono fondersi o spezzarsi |
| **Ordine dei brani** | Disco → traccia → titolo | Senza numero di traccia nel tag l'album va in ordine alfabetico, anche quando il nome del file ("03 - …") dice l'ordine giusto |
| **Ordine alfabetico** | `lowercase()` | Le lettere accentate finiscono in fondo, "The Beatles" sta sotto la T, "Brano 10" viene prima di "Brano 2" |
| **Discografia** | Album per anno, poi i singoli | Non distingue EP, raccolte, live e "compare in"; usa l'anno dell'edizione e non quello originale |
| **Test** | Le regole del telefono stanno in `:app` | Senza SDK in locale non si possono testare: proprio la parte più delicata (raggruppare e ordinare) non ha test |
| **I tuoi preferiti** | Telefono: JSON locale. Web: preferiti di Navidrome | Un "mi piace" sul telefono non si vede sul PC e viceversa |
| **Ripresa** | Si salva solo l'ultimo brano | Riaprendo l'app o riaccendendo l'auto la coda è persa |
| **Tablet Android** | Layout da telefono | Su schermo grande niente pannelli affiancati |
| **Documentazione** | `server/README.md` parla di "Qualità in streaming" | La funzione è stata tolta (si ascolta il file originale): la guida è vecchia, e in `RemoteSource` resta il parametro `maxBitRate` |

## Fase 0 — Fondamenta

Prima di toccare artisti e ordinamenti, la logica deve diventare testabile.

- [ ] **Pulizia**: togliere `maxBitRate` da `RemoteSource` e il paragrafo sulla conversione da `server/README.md`.
- [ ] **Catalogo locale in `:core`**: spostare raggruppamento e ordinamento della musica del telefono da `LocalSource` a una classe pura (`LocalCatalog`) che riceve i brani letti da MediaStore e restituisce album, artisti, singoli. `LocalSource` resta solo la lettura di MediaStore.
- [ ] **Test con una libreria "difficile"**: un insieme di brani finti che riproduce i casi reali (feat., compilation, album senza numeri di traccia, accenti, articoli, dischi multipli, album omonimi). Lo stesso insieme, in JSON, lo usano anche i test dell'app web: le due app devono dare lo stesso risultato.

## Fase 1 — Riconoscere gli artisti e gli album

Il principio resta quello di `CLAUDE.md`: **la cura vera sono i tag**. L'app legge meglio i tag che ci
sono, non inventa euristiche fragili.

- [ ] **Artista dell'album**: leggere `ALBUM_ARTIST` da MediaStore. Un album appartiene al suo artista dell'album; i brani con ospiti restano dentro l'album.
- [ ] **Album = artista dell'album + titolo**, non `ALBUM_ID`: stesso album in due cartelle → una scheda; "Greatest Hits" di due artisti → due schede.
- [ ] **Artisti multipli**: separare solo sui separatori non ambigui (`;`, `/`, ` feat. `, ` ft. `, ` featuring `). "Simon & Garfunkel" o "Earth, Wind & Fire" restano interi. Un brano con ospite compare anche nella pagina dell'ospite, sotto "Compare in".
- [ ] **Stesso artista scritto in modi diversi** (maiuscole, spazi, accenti composti in modo diverso): un solo artista, col nome scritto nel modo più frequente.
- [ ] **Raccolte**: album con il flag compilation o con artista dell'album "Various Artists"/"Artisti vari" in una sezione "Raccolte", non sparsi sotto venti artisti.
- [ ] **Server: campi OpenSubsonic** di Navidrome (`artists`, `albumArtists`, `displayArtist`, `sortName`, `releaseTypes`, `isCompilation`, `originalReleaseDate`). Se il tipo di uscita c'è (Picard lo scrive), decide lui se è album, EP o singolo; la regola "un brano solo = singolo" resta solo come ripiego.

## Fase 2 — L'ordine giusto

- [ ] **Brani di un album**: disco → traccia → numero all'inizio del nome del file ("03 - Titolo.mp3", "1-03 Titolo.flac") → titolo. Su Android 11+ leggere anche `DISC_NUMBER` e `CD_TRACK_NUMBER`.
- [ ] **Album su più dischi**: intestazione "Disco 1", "Disco 2" (col titolo del disco se il tag lo ha), sul telefono, nel web e in Auto.
- [ ] **Ordine alfabetico come una persona**: `Collator` italiano (gli accenti stanno con la loro lettera), articoli ignorati ("The", "Il", "La", "Lo", "L'", "I", "Gli", "Le"), numeri in ordine naturale, tag di ordinamento (`sortName`) quando ci sono. Lettere-indice laterali nell'elenco artisti.
- [ ] **Discografia**: Album, EP, Singoli, Raccolte, Live, Compare in; ognuna dal più recente, con l'**anno originale** se presente (una ristampa 2011 di un disco del 1977 sta nel 1977, anche nei decenni).
- [ ] **Web allineato**: stesse regole nell'app web dove il server non ordina già lui.

## Fase 3 — La salute della libreria

Aiuta a sistemare i tag invece di nascondere i problemi.

- [ ] Una pagina **"Salute della libreria"** (telefono e web) che elenca: brani senza artista o album, album con numeri di traccia mancanti o doppi, artisti scritti in modi diversi, album sdoppiati, copertine mancanti, anni sospetti. Ogni voce dice cosa fare (di solito: passarla in Picard, guida in `server/README.md`).
- [ ] Dopo una scansione nuova, un avviso discreto se i problemi sono aumentati.

## Fase 4 — Ascoltare bene

- [ ] **Coda salvata per intero**: riaprendo l'app o riaccendendo l'auto riparte la stessa coda, dallo stesso punto.
- [ ] **Senza pause tra i brani** (gapless) negli album dal vivo e nei concept: verificare con file MP3, AAC e FLAC sia dal telefono sia dal server.
- [ ] **Preferiti e playlist uguali ovunque**: per i brani del server il "mi piace" e le playlist del telefono finiscono anche su Navidrome (e viceversa). I brani solo sul telefono restano locali; se lo stesso brano c'è anche sul server (stesso artista, titolo e durata) i due vengono riconosciuti come uno.
- [ ] **Scaricare per l'offline**: album e playlist del server salvati sul telefono, per la macchina e i posti senza rete. Si scarica il file originale.
- [ ] **Statistiche** che contano anche gli ascolti dal web (dagli scrobble di Navidrome).

## Fase 5 — Android Auto fatto bene

- [ ] Prova completa con il **Desktop Head Unit** (l'emulatore di Android Auto) e in macchina, con una checklist: avvio a freddo, ripresa, ricerca vocale ("metti *artista*", "metti *album*", "metti *playlist*"), cuore, timer, telefono senza rete.
- [ ] Elenchi lunghi: Auto ne mostra pochi elementi per volta; artisti e album raggruppati per lettera, o una sezione "Più ascoltati" in cima.
- [ ] Tasti **casuale** e **ripeti** accanto al cuore.
- [ ] Brani scaricati (fase 4) segnalati e suonabili anche senza rete.

## Fase 6 — Tablet e PC

- [ ] **Tablet Android**: barra di navigazione laterale, libreria e dettaglio affiancati, pannello "In riproduzione" a destra con testo e coda, come l'app web. Funziona anche in orizzontale e a schermo diviso.
- [ ] **App web sul tablet**: tocchi comodi, gesti (scorri per mettere in coda), pannello "In riproduzione" a schermo intero.
- [ ] **App web sul PC**: elenchi lunghi veloci anche con decine di migliaia di brani, trascina-e-rilascia nelle playlist.

## Fase 7 — Rifinitura

- [ ] Prestazioni con librerie grandi (10.000+ brani) su telefono e in Auto.
- [ ] Note di versione nella release `apk-latest`: cosa è cambiato da un APK all'altro.
- [ ] Accessibilità: TalkBack, contrasto, dimensione del testo.

## Cosa non faremo

Scelte già prese (vedi `CLAUDE.md`): niente equalizzatore, effetti, velocità o bitrate ridotto; niente
backend nostro dove Navidrome basta; niente app WebView per Android (Auto non le vede).
