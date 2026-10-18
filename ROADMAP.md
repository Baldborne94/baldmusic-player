# Piano di lavoro

L'obiettivo: **un player fatto bene** per telefono (anche in **Android Auto**), tablet e PC, che
riconosca gli artisti, metta i brani nell'ordine giusto e renda piacevole ascoltare la propria musica.

Si procede a piccoli passi: **una fase alla volta, una PR per voce**, ognuna con i suoi test e
provata sul telefono prima di passare alla successiva. Le voci si spuntano qui quando la PR entra in `main`.

## Come si usa BaldMusic oggi

- **La musica sta su ogni dispositivo**, in locale, finché non arriva il NAS.
- **I file sono già passati da Picard**: i tag sono buoni e completi (artista dell'album, numeri di
  traccia e disco, anno originale, tipo di uscita, ID MusicBrainz, ReplayGain se calcolato).
  Il lavoro vero è **leggerli tutti** e usarli bene.
- **ReplayGain sì**: volume uniforme tra un brano e l'altro, letto dai tag. Non è un effetto: il suono
  resta quello del file, cambia solo il volume, di una quantità fissa per brano o per album.

Quindi, dispositivo per dispositivo:

| Dispositivo | App | Da dove legge la musica |
|-------------|-----|-------------------------|
| Telefono (e auto) | App Android | I file sul telefono (MediaStore + lettura dei tag) |
| Tablet Android | App Android | I file sul tablet, come il telefono |
| PC | App web | **Navidrome sullo stesso PC** (`http://localhost:4533`): legge i tag di Picard e fa già tutto, l'app web parla con lui |
| Col NAS | Tutte | Navidrome si sposta sul NAS: basta cambiare indirizzo, la libreria diventa una sola |

> L'app web non legge file dal disco da sola: per il PC la strada è Navidrome in locale, che è già
> supportato e domani si sposta sul NAS senza cambiare nulla nell'app. Un tablet che non è Android
> (iPad) usa l'app web e quindi ha bisogno di un Navidrome raggiungibile (PC acceso + Tailscale, o il NAS).

## Dove siamo

La prima stesura funziona già: app Android nativa con Android Auto, app web stile Spotify,
server Navidrome raggiungibile con Tailscale, testi sincronizzati, preferiti, playlist, radio,
statistiche, widget. I punti deboli emersi leggendo il codice:

| Area | Oggi | Problema |
|------|------|----------|
| **Tag sul telefono** | Si legge solo MediaStore | MediaStore non espone quasi niente di quello che Picard ha scritto: niente tipo di uscita, anno originale, nomi di ordinamento, ID MusicBrainz, ReplayGain |
| **Artisti (telefono)** | `LocalSource` raggruppa per `ARTIST_ID` di MediaStore | "Vasco Rossi feat. X" è un artista diverso da "Vasco Rossi"; l'*artista dell'album* non viene letto, quindi le compilation e gli album con ospiti si spezzano sotto tanti artisti |
| **Artisti (Navidrome)** | Si usa solo il campo `artist` | Navidrome manda già gli artisti multipli (`artists`, `albumArtists`, `displayArtist`), il tipo di uscita e il ReplayGain: li ignoriamo |
| **Album (telefono)** | Raggruppati per `ALBUM_ID` di MediaStore | Dipende da come Android indicizza: album omonimi di artisti diversi o lo stesso album in due cartelle possono fondersi o spezzarsi |
| **Ordine alfabetico** | `lowercase()` | Le lettere accentate finiscono in fondo, "The Beatles" sta sotto la T, "Brano 10" viene prima di "Brano 2" |
| **Discografia** | Album per anno, poi i singoli | Non distingue EP, raccolte, live e "compare in"; usa l'anno dell'edizione e non quello originale |
| **Test** | Le regole del telefono stanno in `:app` | Senza SDK in locale non si possono testare: proprio la parte più delicata (raggruppare e ordinare) non ha test |
| **Ripresa** | Si salva solo l'ultimo brano | Riaprendo l'app o riaccendendo l'auto la coda è persa |
| **Volume** | Ogni brano al suo volume | Niente ReplayGain: un disco degli anni 70 suona molto più piano di uno del 2010 |
| **Tablet Android** | Layout da telefono | Su schermo grande niente pannelli affiancati |
| **Documentazione** | `server/README.md` parla di "Qualità in streaming" | La funzione è stata tolta (si ascolta il file originale): la guida è vecchia, e in `RemoteSource` resta il parametro `maxBitRate` |

## Fase 0 — Fondamenta

Prima di toccare artisti e ordinamenti, la logica deve diventare testabile.

- [x] **Pulizia**: tolti `maxBitRate` e il paragrafo sulla conversione da `server/README.md`; lo streaming chiede a Navidrome `format=raw`, cioè sempre il file originale.
- [x] **Catalogo locale in `:core`**: raggruppamento e ordinamento della musica del telefono stanno in `LocalCatalog` (puro, testato); `LocalSource` legge solo MediaStore.
- [x] **Test delle regole attuali** (`LocalCatalogTest`): album e singoli, dischi e tracce, artisti, recenti, decenni, cartelle, ricerca, radio, mix. Hanno già trovato un difetto: un brano in una raccolta finisce tra i "singoli" del suo artista (lo sistema la fase 1).

## Fase 1 — Leggere i tag di Picard e riconoscere gli artisti

Il principio resta quello di `CLAUDE.md`: **la cura vera sono i tag**. Con i file già passati da Picard,
l'app deve solo leggerli tutti, non inventare euristiche.

- [ ] **Lettore di tag sul telefono**: alla prima scansione si leggono i tag completi di ogni file (MP3, FLAC, M4A, OGG/Opus) e si salvano in un indice; alle scansioni successive si rileggono solo i file nuovi o modificati. Campi: artista dell'album, artisti multipli, tipo di uscita, anno originale, nomi di ordinamento, ID MusicBrainz, titolo del disco, ReplayGain. Prima voce: scegliere il lettore (quello di Media3 già incluso, o una libreria dedicata) provandolo su file veri.
- [ ] **Artisti riconosciuti per ID**: se c'è l'ID MusicBrainz dell'artista, quello decide chi è chi (due artisti omonimi restano due, lo stesso artista scritto in modi diversi resta uno). Senza ID: nome normalizzato (maiuscole, spazi, accenti).
- [ ] **Artista dell'album**: un album appartiene al suo artista dell'album; i brani con ospiti restano dentro l'album e compaiono anche nella pagina dell'ospite, sotto "Compare in".
- [ ] **Artisti multipli**: dai tag multi-valore di Picard (`ARTISTS`); nei vecchi tag a stringa unica si separa solo su separatori non ambigui (`;`, `/`, ` feat. `, ` ft. `). "Simon & Garfunkel" resta intero.
- [ ] **Album = ID MusicBrainz della pubblicazione**, oppure artista dell'album + titolo: lo stesso album in due cartelle è una scheda, "Greatest Hits" di due artisti sono due schede.
- [ ] **Tipo di uscita**: album, EP, singolo, raccolta, live presi dal tag. La regola "un brano solo = singolo" resta solo per i file senza quel tag.
- [ ] **Raccolte**: album con il flag compilation o artista dell'album "Various Artists" in una sezione "Raccolte".
- [ ] **Navidrome (PC)**: leggere i campi OpenSubsonic (`artists`, `albumArtists`, `displayArtist`, `sortName`, `releaseTypes`, `isCompilation`, `originalReleaseDate`, `replayGain`) nell'app web e nel telefono.

## Fase 2 — L'ordine giusto

- [ ] **Brani di un album**: disco → traccia (dai tag; il numero nel nome del file solo come ripiego).
- [ ] **Album su più dischi**: intestazione "Disco 1", "Disco 2" col titolo del disco se c'è, sul telefono, nel web e in Auto.
- [ ] **Ordine alfabetico come una persona**: prima i nomi di ordinamento di Picard ("Beatles, The"); senza, `Collator` italiano (gli accenti stanno con la loro lettera), articoli ignorati ("The", "Il", "La", "Lo", "L'", "I", "Gli", "Le"), numeri in ordine naturale. Lettere-indice laterali nell'elenco artisti.
- [ ] **Discografia**: Album, EP, Singoli, Raccolte, Live, Compare in; ognuna dal più recente, con l'**anno originale** (una ristampa 2011 di un disco del 1977 sta nel 1977, anche nei decenni).
- [ ] **Web allineato**: stesse regole nell'app web dove Navidrome non ordina già lui, verificate con la stessa libreria di prova in JSON (casi difficili: feat., raccolte, accenti, articoli, dischi multipli, album omonimi) usata dai test Kotlin.

## Fase 3 — Ascoltare bene

- [ ] **ReplayGain**: impostazione *Album* (predefinita: dentro un disco le differenze volute restano), *Brano* (per mix e casuale) o *Spento*. Sul telefono dai tag letti in fase 1, nel web dal campo `replayGain` di Navidrome. Senza tag il brano suona com'è. Si abbassa e basta, mai oltre il picco: niente distorsione.
- [ ] **Coda salvata per intero**: riaprendo l'app o riaccendendo l'auto riparte la stessa coda, dallo stesso punto.
- [ ] **Senza pause tra i brani** (gapless) negli album dal vivo e nei concept: verificare con MP3, AAC e FLAC.

## Fase 4 — Android Auto fatto bene

- [ ] Prova completa con il **Desktop Head Unit** (l'emulatore di Android Auto) e in macchina, con una checklist: avvio a freddo, ripresa, ricerca vocale ("metti *artista*", "metti *album*", "metti *playlist*"), cuore, timer, telefono senza rete.
- [ ] Elenchi lunghi: Auto mostra pochi elementi per volta; artisti e album raggruppati per lettera, "Più ascoltati" e "Ascoltati di recente" in cima.
- [ ] Tasti **casuale** e **ripeti** accanto al cuore.
- [ ] Discografia divisa per tipo (fase 2) anche in Auto.

## Fase 5 — Tablet e PC

- [ ] **Tablet Android**: barra di navigazione laterale, libreria e dettaglio affiancati, pannello "In riproduzione" a destra con testo e coda, come l'app web. Funziona anche in orizzontale e a schermo diviso.
- [ ] **App web sul PC**: elenchi lunghi veloci anche con decine di migliaia di brani, trascina-e-rilascia nelle playlist.
- [ ] **App web sul tablet** (se non Android): tocchi comodi, gesti, "In riproduzione" a schermo intero.

## Fase 6 — Quando arriva il NAS

Con una libreria sola ha senso unire quello che oggi è separato per dispositivo.

- [ ] **Guida per il NAS** in `server/README.md`: Navidrome sul NAS (Docker), Tailscale, spostare la musica.
- [ ] **Preferiti e playlist uguali ovunque**: quelli del telefono finiscono anche su Navidrome; i brani presenti sia sul telefono sia sul server sono riconosciuti come uno (ID MusicBrainz del brano, o artista + titolo + durata).
- [ ] **Scaricare per l'offline**: album e playlist del NAS salvati sul telefono, per la macchina e i posti senza rete. Si scarica il file originale.
- [ ] **Statistiche unite** con gli ascolti dal web (scrobble di Navidrome).

## Fase 7 — Rifinitura

- [ ] **Salute della libreria**: una pagina che segnala i file aggiunti dopo Picard con tag incompleti (senza artista dell'album, numeri di traccia mancanti o doppi, senza copertina).
- [ ] Prestazioni con librerie grandi (10.000+ brani) su telefono e in Auto.
- [ ] Note di versione nella release `apk-latest`: cosa è cambiato da un APK all'altro.
- [ ] Accessibilità: TalkBack, contrasto, dimensione del testo.

## Cosa non faremo

Scelte già prese (vedi `CLAUDE.md`): niente equalizzatore, effetti, velocità o bitrate ridotto
(il ReplayGain è ammesso: solo volume, dai tag); niente backend nostro dove Navidrome basta;
niente app WebView per Android (Auto non le vede).
