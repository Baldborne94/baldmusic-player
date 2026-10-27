# Collaudo a mano

I test automatici (`android/core`, `web/src/lib`, le prove nel browser in `web/e2e`) controllano le
regole: come si formano album, raccolte, code, backup, script. Quello che **non possono** provare sta
qui: il telefono vero, Android Auto, il tablet, Windows. Si fa:

- **sempre**, dopo ogni APK o versione del sito: il **giro veloce** (10 minuti);
- **per intero** quando cambia la parte toccata (le sezioni dicono quando), e prima di cose grosse
  (NAS, telefono nuovo).

Ogni caso: cosa fare → cosa deve succedere. Se qualcosa non torna, annota il numero del caso
(es. `T-12`) e cosa hai visto, con uno screenshot se puoi.

## Giro veloce (dopo ogni aggiornamento)

| # | Cosa fare | Cosa deve succedere |
|---|-----------|---------------------|
| G-1 | Aggiorna l'app dall'avviso in Home (o installa l'APK) | Si installa sopra senza disinstallare; preferiti, playlist e impostazioni ci sono ancora; compare una volta «Novità di BaldMusic» |
| G-2 | Apri una raccolta e tocca un brano | Parte quel brano, poi il resto della raccolta in ordine |
| G-3 | Metti un cuore, poi apri «Brani che ti piacciono» | Il brano c'è, in cima |
| G-4 | Chiudi l'app dalle app recenti, riaprila e premi play dalla notifica o dal mini-player | Riparte lo stesso brano, dallo stesso punto, con la stessa coda |
| G-5 | Ascolta la fine di un brano e l'inizio del successivo (di un album diverso) | Dissolvenza morbida, niente silenzio né salto di volume |
| G-6 | Collega l'auto (o il Desktop Head Unit) | BaldMusic c'è, «La tua musica» si apre, un brano parte |
| G-7 | Sul PC apri il sito e premi play su una raccolta | Suona; la barra in basso avanza; nessun messaggio di errore |

## Telefono

### Musica e libreria (dopo modifiche a catalogo, tag, raccolte)

| # | Cosa fare | Cosa deve succedere |
|---|-----------|---------------------|
| T-1 | Prima apertura dopo l'installazione | Chiede il permesso della musica; dopo «Consenti» compaiono raccolte e brani |
| T-2 | Libreria → Raccolte | Una raccolta per ogni cartella dentro `Music` (Disney Italia, Movie Soundtracks…), nell'ordine in cui sono arrivati i file |
| T-3 | Copia un file nuovo in una cartella-raccolta, riapri l'app | Il brano è in fondo a quella raccolta, da solo |
| T-4 | Togli un brano da una raccolta (⋮ → Togli), riapri l'app | Non torna |
| T-5 | Elimina una raccolta (non i file), riapri l'app | Non si ricrea; i file sono ancora sul telefono |
| T-6 | Crea una cartella nuova in `Music` con dei file | Diventa una raccolta nuova |
| T-6b | Rinomina una cartella-raccolta col gestore file (es. `Music/Disney` → `Music/Disney Italia`), riapri l'app | Nessuna raccolta vuota o doppia: la stessa raccolta ha il nome nuovo, lo stesso ordine e i cuori dei suoi brani; se l'avevi rinominata tu, tiene il tuo nome |
| T-6c | Sposta una cartella-raccolta dentro un'altra (es. `Music/Film/Disney`), o copiala dal PC e cancella la vecchia | Come T-6b; i brani piaciuti e i più ascoltati restano (anche se Android dà ai file ID nuovi) |
| T-7 | Libreria → Album | Solo dischi con almeno due brani; nessun «album» da un brano solo |
| T-8 | Apri un artista | Album per tipo (Album, EP, Dal vivo…) dal più recente; «Singoli e brani sparsi»; «Compare in» se è ospite altrove |
| T-9 | Un brano «A feat. B» | Sta sotto A; B lo ha in «Compare in» |
| T-10 | Elenco artisti | Lettere A–Z, «The Beatles» sotto la B, accenti con la loro lettera; in cima «I tuoi più ascoltati» se ne hai almeno 3 |
| T-11 | Un album su più dischi | Intestazioni «Disco 1», «Disco 2» |
| T-12 | Decenni | Nessun brano negli «Anni 70» solo perché il file dice 1970 |
| T-13 | Cerca un artista, un titolo, una raccolta | Risultati giusti, anche senza accenti |
| T-14 | ⋮ → Informazioni sul brano | Tag leggibili; per un brano tra gli sparsi si capisce il perché |
| T-15 | Un disco con la copertina rovinata nel file (righe di colori a caso, come «Classical Music»), dopo l'aggiornamento | Al suo posto la copertina di un altro brano dello stesso disco, o nessuna copertina; mai le righe colorate, neanche nei mosaici delle raccolte e in Android Auto |
| T-16 | Un album i cui file sono numerati solo nel nome («01 - …», «02 - …»), senza il tag della traccia | Brani nell'ordine dei file; ⋮ → Informazioni sul brano: «Traccia 2 (dal nome del file)»; un file «99 Luftballons» o «883 - …» non prende un numero |
| T-17 | Libreria → Artisti (o Album, o una pagina con più di 40 elementi), scorri | A destra compaiono le lettere; tocca o trascina: salta alla lettera, con la lettera grande accanto al dito; ferma la lista, dopo un attimo le lettere spariscono e i ⋮ delle righe si toccano di nuovo |
| T-18 | Un brano riconosciuto e applicato (AcoustID), aperto nel lettore con la rete lenta o assente | La copertina del file compare subito, mai il riquadro vuoto; con la rete, dalla volta dopo quella vera. Il ⋮ del lettore ha «Informazioni sul brano» (solo brani del telefono) |

### Raccolte e playlist (dopo modifiche a playlist, selezione, riordino)

| # | Cosa fare | Cosa deve succedere |
|---|-----------|---------------------|
| R-1 | Crea una playlist, aggiungi brani da album diversi | Ci sono tutti, nell'ordine aggiunto, senza doppioni |
| R-2 | Pressione lunga su un brano, poi selezionane altri | Barra con Aggiungi a / Togli / Elimina; il numero è giusto |
| R-3 | «Riordina brani», trascina la maniglia ≡ | L'ordine nuovo resta anche riaprendo l'app |
| R-4 | «Ordina per titolo», poi «per artista» | Ordine alfabetico corretto (articoli ignorati) |
| R-5 | Copertina di una raccolta con brani di 4+ dischi | Mosaico 2×2; anche in Android Auto |
| R-6 | Rinomina una raccolta | Il nome nuovo resta; la raccolta continua a ricevere i brani nuovi della cartella |
| R-7 | Home → I tuoi mix, dopo qualche settimana di ascolti | «Riscopri» (brani ascoltati spesso e non sentiti da due mesi, o piaciuti e mai sentiti di recente) e «Per la macchina» (i più ascoltati e i preferiti); nello stesso giorno lo stesso mix ha lo stesso ordine, il giorno dopo cambia |

### Ascolto (dopo modifiche a riproduzione, volume, dissolvenza)

| # | Cosa fare | Cosa deve succedere |
|---|-----------|---------------------|
| A-1 | Album dal vivo o concept, ascolta il passaggio tra due brani | Nessuna pausa e nessuna dissolvenza (stesso album) |
| A-2 | Impostazioni → Dissolvenza: prova Spenta, 3, 12 s | Spenta = passaggio netto; 12 s = sfumatura lunga; mai dentro lo stesso album |
| A-3 | Durante una dissolvenza premi pausa, poi «avanti», poi trascina la barra | La dissolvenza si interrompe subito, niente due brani insieme |
| A-4 | Volume uguale: Album / Brano / Spento con brani vecchi e recenti | Con Album o Brano i brani vecchi e nuovi hanno volume simile; mai distorsione |
| A-5 | Impostazioni → «Misura adesso», poi lascia il telefono in carica | Il numero di brani misurati cresce; dopo, i brani scaricati hanno volume simile |
| A-6 | Timer di spegnimento: 15 minuti e «fine brano», anche con «Ripeti il brano» e sull'ultimo brano della coda | Si abbassa e si ferma al momento giusto; finita la coda il timer si spegne e la volta dopo la musica non si ferma da sola |
| A-7 | Cuffie Bluetooth: tasti play/pausa/avanti | Funzionano anche con l'app chiusa |
| A-8 | Telefonata, sveglia o «Ok Google» durante l'ascolto, anche nel mezzo di una dissolvenza | La musica si ferma tutta (niente brano che sfuma sotto la chiamata) e riparte dopo |
| A-12 | Casuale su un album di 10 brani con «Ripeti» spento | Suonano tutti e 10, partendo da quello scelto |
| A-9 | Testo: «In riproduzione» → microfono; poi lo stesso senza rete e di nuovo con la rete | Testo sincronizzato; toccando una riga si salta lì; senza rete «non disponibile», tornata la rete il testo arriva |
| A-9b | «In riproduzione» con un brano cantato di cui c'è il testo | Sotto titolo e artista due righe: quella cantata adesso (bianca) e la prossima (sfumata), che cambiano con la canzone; toccandole si apre il testo intero. Senza testo le righe non ci sono |
| A-10 | Radio da un brano (⋮ → Radio) | Parte il brano e poi musica simile |
| A-11 | Coda: scorri un brano a destra, sposta su/giù, «Svuota» | La coda cambia come atteso e si salva |
| A-13 | Impostazioni → Testi da internet → «Scarica adesso», aspetta qualche minuto; poi modalità aereo col Wi‑Fi spento e apri il testo di un brano ascoltato spesso, di un preferito, di una raccolta | Il numero dei «testi salvati» cresce; senza rete i testi ci sono. Senza toccare niente lo stesso succede da solo di notte in carica col Wi‑Fi |
| A-14 | Metti un album, salta all'ultimo brano e lascialo finire (Continua ad ascoltare attivo, ripeti spento) | Quando parte l'ultimo brano la coda si allunga con brani simili (stesso artista, periodo o cartella); finito l'album la musica continua, con la dissolvenza |
| A-15 | Lo stesso con ripeti attivo, con il timer «a fine brano», con Continua ad ascoltare spento | Non si aggiunge niente: ripete / si ferma / si ferma a fine coda |
| A-16 | Casuale su una raccolta, arriva all'ultimo brano | I brani aggiunti suonano dopo, non tornano quelli già sentiti |

### Pulizia, cestino, salute (dopo modifiche a Pulizia, Cestino, Salute)

| # | Cosa fare | Cosa deve succedere |
|---|-----------|---------------------|
| P-1 | Pulizia: un doppione tra due cartelle → «Butta le copie doppie»; una seconda volta ruota il telefono mentre è aperta la finestra di Android | Conferma dell'app e poi di Android; il file va nel cestino; le raccolte che lo avevano hanno ancora il brano (la copia tenuta), anche dopo la rotazione |
| P-2 | Pulizia: anteprime di 30 s | Elencate solo brani da 25–35 s |
| P-3 | Brani nel cestino → ripristina un doppione | Il file torna nella sua cartella e nella sua raccolta, al posto della copia che lo aveva sostituito |
| P-4 | Elimina una raccolta «con i brani dal telefono» | Doppia conferma; i file nel cestino di Android (recuperabili 30 giorni) |
| P-5 | Salute della libreria | Le cartelle con album diversi per ogni brano in cima; numeri plausibili; tocco su un brano = i suoi tag; «Condividi l'elenco» manda il testo |
| P-6 | Sistema una cartella con Picard sul PC e ricopiala | Dopo la rilettura gli album si formano e la cartella sparisce (o scende) dalla Salute |

### Riconoscere i brani (dopo modifiche al riconoscimento)

| # | Cosa fare | Cosa deve succedere |
|---|-----------|---------------------|
| N-1 | Impostazioni → Riconosci i brani, senza chiave | Spiega come avere la chiave; «Crea la chiave» apre acoustid.org; «Riconosci adesso» non c'è finché manca |
| N-2 | Metti una chiave sbagliata e «Riconosci adesso» | Si ferma subito con «La chiave di AcoustID non vale» |
| N-3 | Chiave giusta, «Riconosci adesso», poi esci dalla schermata | Il numero «ascoltati» cresce anche fuori dalla schermata; in un paio di secondi a brano |
| N-4 | Una cartella-album scaricata coi tag sbagliati | Le proposte di quei brani hanno tutte lo stesso album, con numeri di traccia e anno giusti |
| N-5 | La cartella Disney Italia | Ogni brano col suo film (colonna sonora), non una raccolta qualunque; titoli in italiano |
| N-6 | «Applica quelli sicuri», poi Libreria → Album | Gli album riconosciuti compaiono tra gli album, coi brani in ordine; spariscono dai «brani sparsi»; i file sul telefono non sono cambiati |
| N-7 | Tocca un brano proposto e scegli un altro disco | Va su quel disco ed è applicato |
| N-8 | Applicati → «Annulla» su una cartella | Tornano i tag del file e le proposte tornano «da controllare» |
| N-9 | Passa una cartella da Picard e ricopiala sul telefono | Quei brani usano i tag di Picard (non più le proposte) |
| N-10 | Senza rete durante il riconoscimento | Si ferma con «Niente connessione», riprende da solo quando torna la rete in carica |
| N-11 | Salute della libreria dopo aver applicato | Molti meno brani da sistemare; il link «Riconosci dal suono» porta qui |
| N-12 | Dopo «Applica», guarda album e brani riconosciuti (anche in Android Auto) | La copertina vera del disco da Cover Art Archive; senza rete o per un disco senza copertina nell'archivio resta quella del file |

### Podcast (dopo modifiche ai podcast)

| # | Prova | Risultato atteso |
|---|---|---|
| P-1 | Impostazioni → Podcast: incolla `https://www.patreon.com/cw/NAQP` (la pagina, non il feed) | Messaggio che spiega dove prendere il link RSS privato, niente podcast aggiunto |
| P-2 | Su Patreon, «Non Aprite Quella Podcast» → «Ascolta in altre app» → «Copia link»; incollalo e «Aggiungi podcast» | «Fatto: «Non Aprite Quella Podcast», N puntate»; in Libreria il filtro «Podcast», in Home «I tuoi podcast», con la copertina |
| P-3 | Apri il podcast, tocca una puntata; dopo qualche minuto metti in pausa, chiudi l'app, riaprila e tocca di nuovo la puntata | Riparte qualche secondo prima di dove eri; nell'elenco «mancano N min»; finita la puntata non parte musica a caso |
| P-4 | Android Auto: scheda «Podcast» | Il podcast e le sue puntate; una puntata riparte da dove eri anche in macchina |
| P-5 | ⋮ → Segnala un problema dopo aver ascoltato una puntata | Nel resoconto nessun `auth=` né `token-hash=` |
| P-6 | Impostazioni → Podcast → ✕ accanto al podcast | Sparisce da Libreria, Home e Auto |
| P-7 | Apri «Non Aprite Quella Podcast» (in app e in Auto) | Un elenco unico dalla puntata più recente, con le intestazioni dei mesi («Ottobre 2026»…); esclusive e puntate delle stagioni mescolate nell'ordine in cui escono; nei titoli niente «(Patreon Exclusive)»/«(Ad-Free)», che stanno nella riga sotto |
| P-8 | Aggiungendo il podcast, consenti le notifiche; il lunedì dopo l'uscita di una puntata (o con «Esegui ora» dalle opzioni sviluppatore di WorkManager) | Notifica «Nuova puntata di Non Aprite Quella Podcast» col titolo; toccandola si apre l'app e la puntata è già nell'elenco e in Home in «Da ascoltare». Nessuna notifica per le puntate vecchie quando aggiungi il podcast |
| P-9 | Col Wi‑Fi, dopo aver aggiunto il podcast (Impostazioni → Podcast: «Le ultime 3», di serie) | In pochi minuti «3 puntate sul telefono» con lo spazio occupato; le ultime tre puntate dicono «scaricata»; in modalità aereo suonano lo stesso |
| P-10 | Con i dati mobili: ⋮ su una puntata vecchia → «Scarica sul telefono» | Si scarica subito anche senza Wi‑Fi; dopo nel ⋮ c'è «Togli dal telefono», che la toglie |
| P-11 | Ascolta fino in fondo una puntata scaricata (o ⋮ → «Segna come ascoltata») | Diventa «ascoltata», esce da «Da ascoltare» e al giro dopo dal telefono; col Wi‑Fi si scarica la successiva non ascoltata. «Segna come da ascoltare» la riporta come nuova |
| P-12 | Durante una puntata: «In riproduzione», notifica e Android Auto | Al posto di casuale e ripeti (e del cuore in notifica e in Auto) i tasti «indietro di 15 s» e «avanti di 30 s»; con un brano di musica tornano quelli di sempre |
| P-13 | Home con una puntata iniziata e una uscita da poco | In cima ai podcast «Da ascoltare»: prima quella da finire, poi la nuova; in Libreria → Podcast e in Auto la stessa voce «Da ascoltare» con «Da finire» e «Nuove» |
| P-14 | In macchina: «Ok Google, metti Non Aprite Quella Podcast su BaldMusic» (o «il podcast Non Aprite Quella Podcast»), con una puntata a metà | Riparte quella puntata da dove eri, una sola; senza puntate a metà parte la più recente non ascoltata, non tutto l'archivio |
| P-15 | «Ok Google, metti l'ultima puntata di Non Aprite Quella Podcast su BaldMusic» | La puntata uscita per ultima, anche se un'altra è a metà |
| P-16 | Nella pagina del podcast (app o Auto) tocca «Riproduci» | Una puntata sola, come in P-14 |
| P-17 | Su Patreon rigenera il link RSS (o prova con un link vecchio); riapri l'app dopo un paio d'ore | In Home «Il link di «Non Aprite Quella Podcast» non funziona più»; in Impostazioni → Podcast la riga lo dice in rosso. Senza rete l'avviso non compare |
| P-18 | Incolla il link nuovo in Impostazioni → Podcast | «Fatto: …»; il podcast non si raddoppia, l'avviso sparisce, le puntate a metà ripartono da dove eri e le scaricate restano |
| P-19 | ⋮ su una puntata (nell'elenco e nel lettore) → «Informazioni sulla puntata» | Titolo, podcast, data, durata e la descrizione del creatore senza tag HTML; i link si toccano e si aprono nel browser |
| P-20 | Ascolta metà di una puntata, segnane una come ascoltata, poi Impostazioni → Backup → «Salva un backup…» e apri il file | Nel file c'è «Non Aprite Quella Podcast» con le puntate, ma nessun `patreon.com`, `auth=` o `token-hash` |
| P-21 | Disinstalla e reinstalla l'app (o un altro telefono), ripristina il backup | Il riepilogo dice che tornano ascoltate e punti di ripresa di «Non Aprite Quella Podcast» e che il link va incollato di nuovo |
| P-22 | Incolla il link in Impostazioni → Podcast | La puntata a metà riparte da dove eri, quella segnata è «ascoltata», quelle scaricate a mano tornano sul telefono |
| P-23 | Con un podcast di centinaia di puntate: chiudi l'app, riaprila e apri subito Libreria → Podcast e «Informazioni sulla puntata» | Si apre senza scatti; la descrizione compare subito |
| P-24 | Ascolta una raccolta, poi rinomina la sua cartella sul telefono con l'app File (o eliminala); riapri BaldMusic | Nei riquadri in cima alla Home e in «Ascoltati di recente» (anche in Auto) c'è il nome nuovo; una raccolta eliminata non c'è più |
| P-25 | Raccolta in casuale: salta due brani dopo pochi secondi, poi spegni e riaccendi «Casuale», poi tocca un altro brano della raccolta | I due saltati spariscono dalla coda e non tornano più in questo ascolto; nemmeno quelli già sentiti; quello che tocchi suona anche se l'avevi saltato |
| P-26 | Stessa raccolta con «Ripeti tutto», arrivando in fondo | Al giro dopo i brani saltati non ci sono; un brano lasciato negli ultimi 30 s (o finito con la dissolvenza) conta come sentito, non saltato |
| P-27 | In una raccolta, senza casuale, tocca l'ultimo brano | Dopo di lui suonano tutti gli altri della raccolta dal primo; solo alla fine «Continua ad ascoltare» passa ad altro. Nella coda il brano toccato è in cima |
| P-28 | Stessa raccolta col casuale acceso, tocca l'ultimo brano | Suonano tutti i brani della raccolta (in ordine casuale) prima di passare ad altro |
| P-29 | Col casuale acceso: ⋮ su un brano → «Riproduci dopo»; poi su un altro «Aggiungi in coda» | Il primo parte subito dopo quello in corso; il secondo dopo tutti gli altri della coda |
| P-30 | In una raccolta salta un brano dopo pochi secondi e arriva in fondo con «Continua ad ascoltare» acceso | Tra i brani aggiunti non c'è quello saltato |
| P-31 | Col casuale, ascolta qualche brano, poi chiudi l'app dalle recenti (o riavvia il telefono) e riprendi dalla notifica o da Auto | Riparte lo stesso brano e dopo vengono quelli che mancavano, non di nuovo quelli già sentiti |
| P-32 | Salta un brano dopo 25 s, poi apri Statistiche o «I più ascoltati» | Il brano saltato non è stato contato; la raccolta però è negli ascoltati di recente |
| P-33 | Cerca un brano metal (es. Slipknot) e toccalo; lascia andare «Continua ad ascoltare» | Dopo vengono brani della sua raccolta e di raccolte con artisti simili (System Of A Down, Rammstein…), niente cartoni o brani tranquilli |
| P-34 | Libreria → Brani (A–Z): tocca un brano | La coda è la sua radio (vedi «Coda»), non i brani in ordine alfabetico |
| P-35 | «Informazioni sul brano» su un file col genere nei tag | Compare la riga «Genere» |
| P-36 | Ascolta (o segna come ascoltate) tutte le puntate nuove di NAQP, poi torna in Home | «Da ascoltare» ha ancora 5 puntate, «Dall'archivio», dalla più recente non ascoltata |
| P-37 | Ascolta fino in fondo una puntata vecchia dell'archivio | In Home la prima «Dall'archivio» diventa quella uscita subito dopo |

### Backup (dopo modifiche a backup o alla tua musica)

| # | Cosa fare | Cosa deve succedere |
|---|-----------|---------------------|
| B-1 | Metti un cuore, aspetta 1 minuto, Impostazioni → Backup | «Aggiornato il …» con l'ora di adesso; il file c'è in `Download/BaldMusic` (app File) |
| B-2 | «Salva un backup…» su Drive o in Download | Il file si crea; riaprendolo è un JSON non vuoto |
| B-3 | Togli un cuore, poi «Ripristina…» dal backup di B-2 | Riepilogo con numeri giusti e «ritrovo N brani su N»; dopo «Ripristina» il cuore è tornato |
| B-4 | Disinstalla, reinstalla, consenti la musica | In Home «Hai già usato BaldMusic?»; «Ripristina» → scegli il file in `Download/BaldMusic` → torna tutto |
| B-5 | Su un altro telefono o tablet con gli stessi file | Il ripristino ritrova i brani anche se il telefono è diverso; quelli che mancano sono elencati |
| B-6 | «Ripristina…» con un file qualsiasi (una foto, un testo) | «Questo file non è un backup di BaldMusic», nessun cambiamento |
| B-7 | Telefono nuovo configurato col trasferimento di Android (o dal backup di Google) | Le impostazioni ci sono; preferiti e playlist no (non puntano a brani sbagliati): in Home compare «Hai già usato BaldMusic?» per ripristinarli dal backup |
| B-9 | Imposta server, chiave di AcoustID, dissolvenza 9 s; salva un backup; disinstalla, reinstalla e ripristina | Tornano preferiti, playlist e le impostazioni: dissolvenza 9 s, chiave di AcoustID, testi, ReplayGain; in Impostazioni → Server indirizzo e utente sono già scritti, basta la password. Nel file di backup non c'è nessun token né password |
| B-10 | Ascolta 3–4 brani di fila guardando l'ora di `Download/BaldMusic/baldmusic-backup.json` (app File); poi metti un cuore | Mentre ascolti il file non si riscrive a ogni brano (gli ascolti entrano al massimo ogni mezz'ora); dopo il cuore si aggiorna entro un minuto circa |

### Aggiornamenti (dopo modifiche a aggiornamenti o alla CI)

| # | Cosa fare | Cosa deve succedere |
|---|-----------|---------------------|
| U-1 | Impostazioni → Aggiornamenti → «Controlla aggiornamenti» senza versioni nuove | «Hai già l'ultima versione ✓» |
| U-2 | Senza internet | «Non riesco a controllare: serve internet», nessun errore strano |
| U-3 | Con una versione nuova: «Aggiorna» la prima volta | Spiega «Consenti da questa fonte», apre le impostazioni; tornando, scarica con la percentuale e chiede conferma ad Android |
| U-4 | Annulla la conferma di Android; un'altra volta esci con «indietro» durante il download e riapri | Torna «Aggiorna», si può riprovare; il download va avanti o finisce, non resta fermo a metà |
| U-5 | «Più tardi» | L'avviso in Home sparisce fino alla versione dopo; in Impostazioni resta |
| U-6 | Dopo l'aggiornamento | «Novità di BaldMusic» una volta sola, con le novità di tutte le versioni saltate |
| U-7 | Un aggiornamento che non va (per esempio con «Blocco automatico» di Samsung acceso) | Il messaggio dice cosa fare e in fondo «(Android: …, codice N)»; «Pagina della release» apre il browser; «Segnala un problema» ha l'errore tra gli ultimi problemi |
| U-8 | Novità nell'avviso e in «Novità di BaldMusic» | I titoli delle PR (es. «Segnala un problema, Continua ad ascoltare…»), non «Correzioni e piccoli miglioramenti» ripetuto |

### Segnala un problema (dopo modifiche al resoconto)

| # | Cosa fare | Cosa deve succedere |
|---|-----------|---------------------|
| P-1 | Impostazioni → «Segnala un problema» | Si apre la scelta di Android (mail, WhatsApp, Telegram…) con un testo: data, versione dell'app, telefono, «Nessuna chiusura improvvisa salvata», il registro dell'app |
| P-2 | Un brano del server col PC spento, poi «Segnala un problema» | Nel resoconto, tra gli «Ultimi problemi annotati», l'errore di riproduzione col titolo del brano; negli indirizzi niente `t=`, `s=`, `u=` con valori (si vede `t=…`) |
| P-3 | Dopo una chiusura improvvisa dell'app, riaprila | In Home «BaldMusic si è chiusa da sola» con «Manda il resoconto» e «Non ora»; dopo uno dei due non ricompare fino alla prossima chiusura |

Il resoconto mandalo a chi sviluppa l'app (o incollalo nella conversazione con Claude): dice cosa correggere.

## Tablet

| # | Cosa fare | Cosa deve succedere |
|---|-----------|---------------------|
| K-1 | In verticale | Schede in una barra a sinistra; tutto leggibile |
| K-2 | In orizzontale, Libreria | Elenco a sinistra, quello che apri a destra; toccare un'altra voce cambia la destra senza accumulare «indietro» |
| K-3 | In orizzontale mentre suona | Pannello fisso a destra con «Coda» e «Testo»; il testo segue il brano |
| K-4 | Schermo diviso con un'altra app | L'app si adatta (barra in basso se stretta) senza tagli |
| K-5 | Ruota durante l'ascolto e durante un download di aggiornamento | La musica continua; l'aggiornamento non riparte da capo né si apre due volte |

## Android Auto (in macchina o col Desktop Head Unit)

### Android Auto sul PC (Desktop Head Unit)

Il **Desktop Head Unit** (DHU) è lo schermo dell'auto in una finestra del PC: si prova tutto senza
scendere in garage, con lo stesso Android Auto del telefono. Serve una volta sola:

1. **Strumenti sul PC.** Scarica da <https://developer.android.com/studio#command-tools> gli «strumenti
   a riga di comando» per Windows, estrai lo zip in `%LOCALAPPDATA%\Android\Sdk\cmdline-tools\latest`
   (dentro devono esserci `bin` e `lib`), poi in un prompt:
   `"%LOCALAPPDATA%\Android\Sdk\cmdline-tools\latest\bin\sdkmanager" "platform-tools" "extras;google;auto"`
   (accetta le licenze con `y`). Con Android Studio: SDK Manager → SDK Tools → «Android Auto Desktop Head Unit Emulator».
2. **Telefono.** Impostazioni → Informazioni sul telefono → tocca 7 volte «Numero build» → in Opzioni
   sviluppatore attiva **Debug USB**.
3. **Android Auto in modalità sviluppatore** (se non l'hai già fatto per installare BaldMusic, vedi
   README): impostazioni di Android Auto → tocca 10 volte **Versione** → menu ⋮ → **Impostazioni
   sviluppatore** → **Origini sconosciute** attivo. In «Auto connesse in precedenza» lascia attivo
   «Aggiungi nuove auto ad Android Auto».

Ogni volta:

1. Sul telefono: Android Auto → menu ⋮ → **Avvia server unità principale** (compare una notifica).
2. Collega il telefono col cavo, sbloccalo (la prima volta accetta «Consenti debug USB»).
3. Sul PC fai doppio clic su `android/tools/avvia-dhu.cmd` (fa `adb forward tcp:5277 tcp:5277` e
   apre il DHU). La prima volta accetta le condizioni sul telefono.

Nella finestra nera del DHU (la console) si scrive:

| Comando o tasto | A cosa serve |
|-----------------|--------------|
| `M` o `mic begin` | Il tasto della voce sul volante: parli nel microfono del PC («metti la raccolta Movie Soundtracks su BaldMusic») |
| `N`, `day`, `night` | Schermo di giorno / di notte |
| `screenshot nome.png` | Una foto dello schermo dell'auto, da allegare se qualcosa non torna |
| `android\tools\avvia-dhu.cmd -i rotary` (da un prompt) | Avvio con la manopola invece del tocco (molte auto ce l'hanno): frecce e Invio |
| `quit` | Chiude |

Se il DHU non si collega: `adb kill-server`, scollega e ricollega il cavo, riavvia il server
dell'unità principale sul telefono.

### Prove in Auto

| # | Cosa fare | Cosa deve succedere |
|---|-----------|---------------------|
| C-1 | Avvio a freddo (app chiusa da ore) e accensione dell'auto | Riprende la coda di prima dal punto giusto; con brani del server in coda e il PC spento riparte in pochi secondi con i soli brani del telefono |
| C-2 | Schede La tua musica / Telefono / Server | Si aprono; raccolte per prime; elenchi lunghi divisi per lettera |
| C-3 | Cuore, casuale, ripeti nella barra | Funzionano e si vedono nell'app |
| C-4 | «Ok Google, metti la raccolta Movie Soundtracks su BaldMusic» | Suona tutta la raccolta |
| C-5 | «…metti Lucio Dalla su BaldMusic», «…metti l'album X» | Suona l'artista / l'album giusto |
| C-6 | Ricerca 🔍 | Trova nelle due librerie |
| C-7 | Copertine | Si vedono, anche i mosaici delle raccolte |
| C-8 | Telefono senza rete | La musica del telefono funziona; quella del server dice che non c'è rete, senza bloccare |
| C-9 | Timer di spegnimento impostato dal telefono mentre sei in Auto | Si abbassa e si ferma |
| C-10 | La tua musica → I tuoi mix | Gli stessi mix della Home: Riscopri e Per la macchina (con abbastanza ascolti), Mix casuale, mix degli artisti più ascoltati, dei decenni; ognuno parte e ha la sua copertina |
| C-11 | Telefono con migliaia di brani: Telefono → Artisti, poi Tutti i brani, poi un artista; torna indietro e riapri | La prima volta in un paio di secondi al massimo, riaprendo subito; scorrendo veloce non si blocca |
| C-12 | Schermo di notte (`night`) | Testi e icone leggibili, copertine non bruciate |
| C-13 | Con la manopola (`-i rotary`): sfoglia, apri, torna indietro, metti in pausa | Si arriva a tutto senza toccare lo schermo |
| C-14 | Voce: «metti Brani che ti piacciono su BaldMusic», «metti Per la macchina su BaldMusic» | Suonano i preferiti / il mix |
| C-15 | Togli il cavo durante l'ascolto, ricollega | Si ferma; ricollegando riparte dallo stesso punto |
| C-16 | Tasto del timer nella barra (accanto a cuore, casuale, ripeti): toccalo più volte | Spento → «Si ferma tra 15 min» → 30 → 60 → «Si ferma a fine brano» → spento; il nome scende coi minuti; a tempo scaduto sfuma e si ferma; nell'app si vede lo stesso timer |
| C-17 | Finisce una raccolta mentre guidi | La musica continua con brani simili (Continua ad ascoltare) |

## PC (sito nel browser)

| # | Cosa fare | Cosa deve succedere |
|---|-----------|---------------------|
| W-1 | Accesso con `http://localhost:4533` sul PC di Navidrome | Entra; Home con raccolte |
| W-2 | Navidrome senza «Report Real Path» | Il sito spiega dove attivarlo; attivato e ricaricato, compaiono le raccolte |
| W-3 | Cartella nuova sul PC + Quick Scan, ricarica | Raccolta nuova; brani nuovi in fondo a quelle esistenti |
| W-4 | Raccolta con migliaia di brani | Si apre subito e scorre senza scatti mentre suona |
| W-5 | Trascina un brano (e poi 3 selezionati) su una playlist della barra laterale | «✓ N brani aggiunti»; i brani già presenti non si doppiano |
| W-6 | Seleziona con Maiuscolo + clic, riordina trascinando, ordina per titolo | Salvato su Navidrome (lo vedi anche dal telefono collegato al server) |
| W-7 | Pulizia → «Prepara la pulizia» → doppio clic sul `.cmd` scaricato | Si apre la finestra, i file vanno nel **Cestino di Windows**, «Premi Invio» alla fine; le raccolte tengono la copia rimasta |
| W-8 | Brani nel cestino → «Prepara il ripristino» → doppio clic sul `.cmd` | I file tornano dov'erano (un file che c'è già non si tocca); «Ho fatto: aggiorna» li rimette nella loro raccolta |
| W-9 | Un percorso con apostrofo o lettera accentata (`L'amore è.mp3`) in W-7 e W-8 | Funziona come gli altri |
| W-10 | Dissolvenza e Volume uguale nelle Impostazioni | Come sul telefono (A-2, A-4) |
| W-11 | Scorciatoie: Spazio, Maiuscolo+frecce, frecce, S, R, F, / | Fanno quello che dice Impostazioni → Scorciatoie |
| W-12 | Installa il sito come app (icona ⊕ in Chrome/Edge) | Si apre nella sua finestra e risponde ai tasti multimediali |
| W-13 | Da fuori casa con Tailscale (`https://…ts.net`) | Funziona; con `http://192.168…` il sito spiega perché non può |

## Server

| # | Cosa fare | Cosa deve succedere |
|---|-----------|---------------------|
| S-1 | PC riavviato | Navidrome riparte da solo; il sito e il telefono si ricollegano |
| S-2 | Telefono collegato al server (Impostazioni → Server Navidrome) | La musica del PC compare nella Home e in Auto; i brani suonano nella qualità originale |
