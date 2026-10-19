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
| T-7 | Libreria → Album | Solo dischi con almeno due brani; nessun «album» da un brano solo |
| T-8 | Apri un artista | Album per tipo (Album, EP, Dal vivo…) dal più recente; «Singoli e brani sparsi»; «Compare in» se è ospite altrove |
| T-9 | Un brano «A feat. B» | Sta sotto A; B lo ha in «Compare in» |
| T-10 | Elenco artisti | Lettere A–Z, «The Beatles» sotto la B, accenti con la loro lettera; in cima «I tuoi più ascoltati» se ne hai almeno 3 |
| T-11 | Un album su più dischi | Intestazioni «Disco 1», «Disco 2» |
| T-12 | Decenni | Nessun brano negli «Anni 70» solo perché il file dice 1970 |
| T-13 | Cerca un artista, un titolo, una raccolta | Risultati giusti, anche senza accenti |
| T-14 | ⋮ → Informazioni sul brano | Tag leggibili; per un brano tra gli sparsi si capisce il perché |

### Raccolte e playlist (dopo modifiche a playlist, selezione, riordino)

| # | Cosa fare | Cosa deve succedere |
|---|-----------|---------------------|
| R-1 | Crea una playlist, aggiungi brani da album diversi | Ci sono tutti, nell'ordine aggiunto, senza doppioni |
| R-2 | Pressione lunga su un brano, poi selezionane altri | Barra con Aggiungi a / Togli / Elimina; il numero è giusto |
| R-3 | «Riordina brani», trascina la maniglia ≡ | L'ordine nuovo resta anche riaprendo l'app |
| R-4 | «Ordina per titolo», poi «per artista» | Ordine alfabetico corretto (articoli ignorati) |
| R-5 | Copertina di una raccolta con brani di 4+ dischi | Mosaico 2×2; anche in Android Auto |
| R-6 | Rinomina una raccolta | Il nome nuovo resta; la raccolta continua a ricevere i brani nuovi della cartella |

### Ascolto (dopo modifiche a riproduzione, volume, dissolvenza)

| # | Cosa fare | Cosa deve succedere |
|---|-----------|---------------------|
| A-1 | Album dal vivo o concept, ascolta il passaggio tra due brani | Nessuna pausa e nessuna dissolvenza (stesso album) |
| A-2 | Impostazioni → Dissolvenza: prova Spenta, 3, 12 s | Spenta = passaggio netto; 12 s = sfumatura lunga; mai dentro lo stesso album |
| A-3 | Durante una dissolvenza premi pausa, poi «avanti», poi trascina la barra | La dissolvenza si interrompe subito, niente due brani insieme |
| A-4 | Volume uguale: Album / Brano / Spento con brani vecchi e recenti | Con Album o Brano i brani vecchi e nuovi hanno volume simile; mai distorsione |
| A-5 | Impostazioni → «Misura adesso», poi lascia il telefono in carica | Il numero di brani misurati cresce; dopo, i brani scaricati hanno volume simile |
| A-6 | Timer di spegnimento: 15 minuti e «fine brano» | Si abbassa e si ferma al momento giusto |
| A-7 | Cuffie Bluetooth: tasti play/pausa/avanti | Funzionano anche con l'app chiusa |
| A-8 | Telefonata o sveglia durante l'ascolto | La musica si ferma e riparte dopo |
| A-9 | Testo: «In riproduzione» → microfono | Testo sincronizzato; toccando una riga si salta lì |
| A-10 | Radio da un brano (⋮ → Radio) | Parte il brano e poi musica simile |
| A-11 | Coda: scorri un brano a destra, sposta su/giù, «Svuota» | La coda cambia come atteso e si salva |

### Pulizia, cestino, salute (dopo modifiche a Pulizia, Cestino, Salute)

| # | Cosa fare | Cosa deve succedere |
|---|-----------|---------------------|
| P-1 | Pulizia: un doppione tra due cartelle → «Butta le copie doppie» | Conferma dell'app e poi di Android; il file va nel cestino; le raccolte che lo avevano hanno ancora il brano (la copia tenuta) |
| P-2 | Pulizia: anteprime di 30 s | Elencate solo brani da 25–35 s |
| P-3 | Brani nel cestino → ripristina un doppione | Il file torna nella sua cartella e nella sua raccolta, al posto della copia che lo aveva sostituito |
| P-4 | Elimina una raccolta «con i brani dal telefono» | Doppia conferma; i file nel cestino di Android (recuperabili 30 giorni) |
| P-5 | Salute della libreria | Le cartelle con album diversi per ogni brano in cima; numeri plausibili; tocco su un brano = i suoi tag; «Condividi l'elenco» manda il testo |
| P-6 | Sistema una cartella con Picard sul PC e ricopiala | Dopo la rilettura gli album si formano e la cartella sparisce (o scende) dalla Salute |

### Backup (dopo modifiche a backup o alla tua musica)

| # | Cosa fare | Cosa deve succedere |
|---|-----------|---------------------|
| B-1 | Metti un cuore, aspetta 1 minuto, Impostazioni → Backup | «Aggiornato il …» con l'ora di adesso; il file c'è in `Download/BaldMusic` (app File) |
| B-2 | «Salva un backup…» su Drive o in Download | Il file si crea; riaprendolo è un JSON non vuoto |
| B-3 | Togli un cuore, poi «Ripristina…» dal backup di B-2 | Riepilogo con numeri giusti e «ritrovo N brani su N»; dopo «Ripristina» il cuore è tornato |
| B-4 | Disinstalla, reinstalla, consenti la musica | In Home «Hai già usato BaldMusic?»; «Ripristina» → scegli il file in `Download/BaldMusic` → torna tutto |
| B-5 | Su un altro telefono o tablet con gli stessi file | Il ripristino ritrova i brani anche se il telefono è diverso; quelli che mancano sono elencati |
| B-6 | «Ripristina…» con un file qualsiasi (una foto, un testo) | «Questo file non è un backup di BaldMusic», nessun cambiamento |

### Aggiornamenti (dopo modifiche a aggiornamenti o alla CI)

| # | Cosa fare | Cosa deve succedere |
|---|-----------|---------------------|
| U-1 | Impostazioni → Aggiornamenti → «Controlla aggiornamenti» senza versioni nuove | «Hai già l'ultima versione ✓» |
| U-2 | Senza internet | «Non riesco a controllare: serve internet», nessun errore strano |
| U-3 | Con una versione nuova: «Aggiorna» la prima volta | Spiega «Consenti da questa fonte», apre le impostazioni; tornando, scarica con la percentuale e chiede conferma ad Android |
| U-4 | Annulla la conferma di Android | Torna «Aggiorna», si può riprovare |
| U-5 | «Più tardi» | L'avviso in Home sparisce fino alla versione dopo; in Impostazioni resta |
| U-6 | Dopo l'aggiornamento | «Novità di BaldMusic» una volta sola, con le novità di tutte le versioni saltate |

## Tablet

| # | Cosa fare | Cosa deve succedere |
|---|-----------|---------------------|
| K-1 | In verticale | Schede in una barra a sinistra; tutto leggibile |
| K-2 | In orizzontale, Libreria | Elenco a sinistra, quello che apri a destra; toccare un'altra voce cambia la destra senza accumulare «indietro» |
| K-3 | In orizzontale mentre suona | Pannello fisso a destra con «Coda» e «Testo»; il testo segue il brano |
| K-4 | Schermo diviso con un'altra app | L'app si adatta (barra in basso se stretta) senza tagli |
| K-5 | Ruota durante l'ascolto e durante un download di aggiornamento | La musica continua; l'aggiornamento non riparte da capo né si apre due volte |

## Android Auto (in macchina o col Desktop Head Unit)

| # | Cosa fare | Cosa deve succedere |
|---|-----------|---------------------|
| C-1 | Avvio a freddo (app chiusa da ore) e accensione dell'auto | Riprende la coda di prima dal punto giusto |
| C-2 | Schede La tua musica / Telefono / Server | Si aprono; raccolte per prime; elenchi lunghi divisi per lettera |
| C-3 | Cuore, casuale, ripeti nella barra | Funzionano e si vedono nell'app |
| C-4 | «Ok Google, metti la raccolta Movie Soundtracks su BaldMusic» | Suona tutta la raccolta |
| C-5 | «…metti Lucio Dalla su BaldMusic», «…metti l'album X» | Suona l'artista / l'album giusto |
| C-6 | Ricerca 🔍 | Trova nelle due librerie |
| C-7 | Copertine | Si vedono, anche i mosaici delle raccolte |
| C-8 | Telefono senza rete | La musica del telefono funziona; quella del server dice che non c'è rete, senza bloccare |
| C-9 | Timer di spegnimento impostato dal telefono mentre sei in Auto | Si abbassa e si ferma |

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
