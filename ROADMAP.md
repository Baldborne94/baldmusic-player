# Piano di lavoro

L'obiettivo: **un player fatto bene** per telefono (anche in **Android Auto**), tablet e PC, che
riconosca gli artisti, metta i brani nell'ordine giusto e renda piacevole ascoltare la propria musica.

Si procede a piccoli passi: **una fase alla volta, una PR per voce**, ognuna con i suoi test e
provata sul telefono prima di passare alla successiva. Le voci si spuntano qui quando la PR entra in `main`.

## Come si usa BaldMusic oggi

- **La musica sta su ogni dispositivo**, in locale, finché non arriva il NAS.
- **Sul PC i file sono passati da Picard**: i tag sono buoni e completi (artista dell'album, numeri di
  traccia e disco, anno originale, tipo di uscita, ID MusicBrainz, ReplayGain se calcolato).
  Il lavoro vero è **leggerli tutti** e usarli bene.
- **Sul telefono no**: i file sono scaricati lì, divisi in cartelle per playlist, con tag poveri (album
  diverso da brano a brano, anno 1970, niente ReplayGain). Lì contano le cartelle, la pulizia e il volume
  misurato dall'app.
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

> **Principio**: sul telefono la musica si organizza in **cartelle** (Music/Disney Italia, Music/Movie
> Soundtracks…) e l'app segue quelle. Le cartelle diventano **raccolte** modificabili e vengono per
> prime ovunque (Home, Libreria, Cerca, Android Auto). Album, artisti e decenni dai tag restano come
> secondo modo di sfogliare, e migliorano quanto più i tag sono buoni.
>
> Ogni volta che carichi musica nuova si guarda insieme come è disposta nelle cartelle (uno screenshot
> della cartella Music basta) e si sistema l'app perché la mostri nel modo migliore.

## Dove siamo

Fatto e in uso: app Android nativa con Android Auto, app web stile Spotify, server Navidrome
raggiungibile con Tailscale; raccolte dalle cartelle su telefono e PC; tag di Picard letti per intero
(artisti, album, dischi, tipo di uscita, anno originale); volume uguale (ReplayGain o misurato);
dissolvenza tra i brani; pulizia e cestino; coda che riparte da dov'era; tablet con libreria affiancata;
aggiornamenti dall'app con le novità. I punti ancora aperti:

| Area | Oggi | Cosa manca |
|------|------|------------|
| **Tag sul telefono** | L'app li legge tutti, ma quelli dei file scaricati sono poveri | Per sistemarli serve passare da Picard sul PC (guida in `server/README.md`); una pagina "Salute della libreria" che dica quali file sistemare (fase 7) |
| **Android Auto** | Raccolte, lettere, voce, cuore/casuale/ripeti | Prova completa in macchina e col Desktop Head Unit (fase 4) |
| **Senza pause tra i brani** | ExoPlayer è gapless; sul PC il brano dopo si carica in anticipo | Da sentire a orecchio sul telefono con album dal vivo (fase 3) |
| **Telefono e PC** | Preferiti e playlist separati: sul telefono in un file dell'app, sul PC su Navidrome | Unirli quando il telefono raggiunge il server (fase 6) |
| **Backup** | La tua musica del telefono (preferiti, playlist, raccolte, ascolti) sta solo nell'app | Esporta/importa, e un backup che sopravvive alla disinstallazione |

## Fase 0 — Fondamenta

Prima di toccare artisti e ordinamenti, la logica deve diventare testabile.

- [x] **Pulizia**: tolti `maxBitRate` e il paragrafo sulla conversione da `server/README.md`; lo streaming chiede a Navidrome `format=raw`, cioè sempre il file originale.
- [x] **Catalogo locale in `:core`**: raggruppamento e ordinamento della musica del telefono stanno in `LocalCatalog` (puro, testato); `LocalSource` legge solo MediaStore.
- [x] **Test delle regole attuali** (`LocalCatalogTest`): album e singoli, dischi e tracce, artisti, recenti, decenni, cartelle, ricerca, radio, mix. Hanno già trovato un difetto: un brano in una raccolta finisce tra i "singoli" del suo artista (lo sistema la fase 1).

## Fase 1 — Leggere i tag di Picard e riconoscere gli artisti

Il principio resta quello di `CLAUDE.md`: **la cura vera sono i tag**. Con i file già passati da Picard,
l'app deve solo leggerli tutti, non inventare euristiche.

- [x] **Lettore di tag sul telefono** (`TagScanner`, con gli estrattori di Media3 già inclusi): alla prima apertura si leggono i tag completi di ogni file (MP3, FLAC, M4A, Ogg/Opus) e si salvano in un indice; poi si rileggono solo i file nuovi o modificati. Campi: artista dell'album, artisti uno per uno, tipo di uscita, anno originale, ID MusicBrainz, titolo del disco, ReplayGain (salvato, si userà in fase 3).
- [x] **Artisti riconosciuti per ID**: se c'è l'ID MusicBrainz dell'artista, quello decide chi è chi (due artisti omonimi restano due, lo stesso artista scritto in modi diversi resta uno). Senza ID: nome normalizzato (maiuscole, spazi, accenti).
- [x] **Artista dell'album**: un album appartiene al suo artista dell'album; i brani con ospiti restano dentro l'album e compaiono anche nella pagina dell'ospite, sotto "Compare in".
- [x] **Artisti multipli**: dai tag multi-valore di Picard (`ARTISTS`, anche in ID3 v2.3 dove sono uniti da "/": si separano solo se tornano col numero di ID); nei tag senza Picard si separa solo su separatori non ambigui (`;`, `/`, ` feat. `, ` ft. `) e sugli ospiti nel titolo ("(feat. X)"). "Simon & Garfunkel" e "AC/DC" restano interi.
- [x] **Album = gruppo di pubblicazione MusicBrainz** (le edizioni diverse dello stesso disco si fondono), oppure artista dell'album + titolo: lo stesso album in due cartelle è una scheda, "Greatest Hits" di due artisti sono due schede.
- [x] **Tipo di uscita**: un singolo resta tra i singoli anche con il lato B, sul telefono e da Navidrome. EP, live e raccolte dello stesso artista divisi in discografia: fase 2.
- [x] **Dischi di artisti vari**: album con il flag compilation, artista dell'album "Various Artists" o (senza tag) più artisti nello stesso disco stanno tra gli album con "Artisti vari"; i loro artisti li ritrovano in "Compare in".
- [x] **Navidrome (PC)**: `releaseTypes` e `originalReleaseDate` di OpenSubsonic nell'app web e nel telefono (singoli fuori dagli album, anno originale in discografia). Gli artisti multipli li gestisce già Navidrome. Restano per le fasi successive `sortName` (fase 2) e `replayGain` (fase 3).

- [x] **Raccolte dalle cartelle**: ogni cartella in cui hai organizzato la musica sul telefono è una raccolta modificabile (aggiungi, togli, rinomina, elimina), che si aggiorna da sola quando arrivano file nuovi.

- [x] **Eliminare dal telefono**: un brano o una raccolta intera finiscono nel cestino di Android (recuperabili per 30 giorni), con doppia conferma; spariscono da raccolte, preferiti e playlist.

- [x] **Pulizia della libreria**: doppioni tra le cartelle (si tiene una copia, le raccolte non perdono il brano) e anteprime di 30 secondi, da buttare nel cestino.

- [x] **Raccolte più comode**: riordinare trascinando, ordinare per titolo o artista, selezione multipla (aggiungi, togli, elimina), copertina a mosaico.

- [x] **Brani nel cestino**: ripescare i brani buttati (entro 30 giorni) e rimetterli al loro posto nelle raccolte.

## Fase 2 — L'ordine giusto

- [x] **Brani di un album**: disco → traccia dai tag. Resta da fare: il numero nel nome del file come ripiego per i file senza numero di traccia.
- [x] **Album su più dischi**: intestazione "Disco 1", "Disco 2" col titolo del disco se c'è, sul telefono, nel web e in Auto.
- [x] **Ordine alfabetico come una persona** (`ArtistNames.sortKey`): nomi di ordinamento di Picard, accenti con la loro lettera, articoli ignorati ("The", "Il", "La", "Lo", "L'", "I", "Gli", "Le"); intestazioni A–Z negli elenchi, anche in Auto. Resta da fare: lettere-indice laterali per saltare a una lettera.
- [x] **Discografia**: Album, EP, Singoli, Raccolte, Live, Compare in; ognuna dal più recente. L'**anno originale** è già usato (fase 1) per album, decenni e ordine.
- [ ] **Web allineato**: stesse regole nell'app web dove Navidrome non ordina già lui, verificate con la stessa libreria di prova in JSON (casi difficili: feat., raccolte, accenti, articoli, dischi multipli, album omonimi) usata dai test Kotlin.

## Fase 3 — Ascoltare bene

- [x] **ReplayGain**: impostazione *Album* (predefinita: dentro un disco le differenze volute restano), *Brano* (per mix e casuale) o *Spento*. Sul telefono dai tag letti in fase 1, nel web dal campo `replayGain` di Navidrome. Senza tag il brano suona com'è. Si abbassa e basta, mai oltre il picco: niente distorsione.
- [x] **Volume misurato sul telefono**: per i file senza ReplayGain l'app misura il volume da sola (EBU R128), una volta per file, in sottofondo mentre il telefono è in carica.
- [x] **Coda salvata per intero**: riaccendendo l'auto (o dalla notifica dopo un riavvio) riparte la stessa coda, dallo stesso punto, con casuale e ripeti.
- [x] **Dissolvenza tra i brani** (chiesta dall'utente al posto delle pause): Spenta/3/6/9/12 s, mai dentro lo stesso album, sul telefono, in Auto e sul PC.
- [ ] **Senza pause tra i brani** (gapless) negli album dal vivo e nei concept: verificare con MP3, AAC e FLAC. Sul telefono ExoPlayer lo fa già; sul PC il brano dopo si carica in anticipo (pausa da ~25 a ~5 ms in prova, molto di più in rete). Resta da sentire a orecchio sul telefono.

## Fase 4 — Android Auto fatto bene

- [x] **Comandi vocali per le raccolte**: "Ok Google, metti (la raccolta) Movie Soundtracks su BaldMusic".
- [ ] Prova completa con il **Desktop Head Unit** (l'emulatore di Android Auto) e in macchina, con una checklist: avvio a freddo, ripresa, ricerca vocale ("metti *artista*", "metti *album*", "metti *playlist*"), cuore, timer, telefono senza rete.
- [x] Elenchi lunghi: Auto mostra pochi elementi per volta; artisti e album raggruppati per lettera, "Più ascoltati" e "Ascoltati di recente" in cima.
- [x] Tasti **casuale** e **ripeti** accanto al cuore.
- [x] Discografia divisa per tipo (fase 2) anche in Auto.

## Fase 5 — Tablet e PC

- [x] **Tablet Android**: da 600dp di larghezza le schede stanno in una barra a sinistra; da 900dp (tablet in orizzontale) la coda resta fissa a destra mentre suona qualcosa. "In riproduzione" ha i comandi in una colonna centrale. Si adatta da solo anche a schermo diviso.
- [x] **Tablet in orizzontale**: nella Libreria l'elenco resta a sinistra e quello che apri compare accanto; il pannello di destra mostra la coda o il testo del brano.
- [x] **Raccolte anche sul PC**: le cartelle della musica di Navidrome diventano raccolte (playlist di Navidrome) come sul telefono, prima di tutto in Home e in Libreria. Serve «Mostra percorso reale» nel lettore *baldmusic* di Navidrome.
- [x] **Pulizia anche sul PC**: anteprime e doppioni come sul telefono; il browser non tocca i file, quindi l'app prepara uno script che li sposta nel Cestino di Windows, e le raccolte passano prima alla copia tenuta.
- [x] **Web come il telefono**, dopo le raccolte: riordinare e ordinare le playlist, selezione di più brani, informazioni sul brano.
- [x] **App web sul PC**: elenchi lunghi veloci anche con migliaia di brani; trascinare uno o più brani su una raccolta, una playlist o "Brani che ti piacciono" nella barra laterale.
- [x] **Brani nel cestino anche sul PC**: l'app ricorda i file che ha mandato nel Cestino di Windows e prepara uno script che li rimette al loro posto; tornano nella loro raccolta al posto della copia tenuta.
- [ ] **App web sul tablet** (se non Android): tocchi comodi, gesti, "In riproduzione" a schermo intero.

## Fase 6 — Quando arriva il NAS

Con una libreria sola ha senso unire quello che oggi è separato per dispositivo.

- [ ] **Guida per il NAS** in `server/README.md`: Navidrome sul NAS (Docker), Tailscale, spostare la musica.
- [ ] **Preferiti e playlist uguali ovunque**: quelli del telefono finiscono anche su Navidrome; i brani presenti sia sul telefono sia sul server sono riconosciuti come uno (ID MusicBrainz del brano, o artista + titolo + durata).
- [ ] **Scaricare per l'offline**: album e playlist del NAS salvati sul telefono, per la macchina e i posti senza rete. Si scarica il file originale.
- [ ] **Statistiche unite** con gli ascolti dal web (scrobble di Navidrome).

## Fase 7 — Rifinitura

- [ ] **Salute della libreria**: una pagina che segnala i file con tag incompleti (senza album o artista dell'album, anno 1970, numeri di traccia mancanti o doppi, senza copertina), cartella per cartella. I **brani di 30 secondi** li trova già la Pulizia.
- [ ] Prestazioni con librerie grandi (10.000+ brani) su telefono e in Auto.
- [x] **Note di versione e aggiornamenti dall'app**: ogni APK pubblica in `apk-latest` anche `version.json` con le novità (i titoli delle PR unite); l'app avvisa in Home, scarica e installa sopra con la conferma di Android, e dopo l'aggiornamento mostra cosa c'è di nuovo.
- [ ] Accessibilità: TalkBack, contrasto, dimensione del testo.

## Cosa non faremo

Scelte già prese (vedi `CLAUDE.md`): niente equalizzatore, effetti, velocità o bitrate ridotto
(il ReplayGain è ammesso: solo volume, dai tag); niente backend nostro dove Navidrome basta;
niente app WebView per Android (Auto non le vede).
