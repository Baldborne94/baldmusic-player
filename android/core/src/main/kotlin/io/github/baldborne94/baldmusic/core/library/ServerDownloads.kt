package io.github.baldborne94.baldmusic.core.library

import io.github.baldborne94.baldmusic.core.subsonic.Song
import kotlinx.serialization.Serializable

/**
 * Album e playlist del PC salvati sul telefono, per la macchina e i posti senza rete: il file
 * originale (come lo dà Navidrome con `format=raw`), mai convertito. Qui c'è l'elenco con i
 * dati dei brani, così si sfogliano e suonano anche senza server ([RemoteSource] →
 * «Scaricati sul telefono»); i file li tiene l'app.
 */
@Serializable
data class ServerDownloads(val sets: List<DownloadedSet> = emptyList()) {
    val songIds: Set<String> get() = sets.flatMapTo(LinkedHashSet()) { set -> set.songs.map { it.id } }

    /** Le copertine da tenere sul telefono (per quando non c'è rete). */
    val covers: Set<String> get() = sets.flatMapTo(LinkedHashSet()) { set -> listOfNotNull(set.coverArt) + set.songs.mapNotNull { it.coverArt } }

    fun has(container: String) = sets.any { it.container == container }

    fun set(key: String) = sets.firstOrNull { it.key == key }

    fun song(id: String): Song? = sets.firstNotNullOfOrNull { set -> set.songs.firstOrNull { it.id == id } }

    /** Chiesto di nuovo (playlist cambiata sul PC): prende il posto di quello di prima. */
    fun with(set: DownloadedSet) = copy(sets = sets.filterNot { it.container == set.container } + set)

    fun without(container: String) = copy(sets = sets.filterNot { it.container == container })

    /** Lo stato del tasto ⬇ di una raccolta, un album o una playlist del server (come su Spotify). */
    data class Status(val state: State, val done: Int = 0, val total: Int = 0) {
        enum class State { NONE, DOWNLOADING, DONE }

        /** Da 0 a 1, per il cerchio che si riempie mentre scarica. */
        val progress: Float get() = if (total == 0) 1f else done.toFloat() / total
    }

    /**
     * Il tasto ⬇ di [container]: da scaricare, mentre scarica (brani già sul telefono su quelli
     * chiesti, da [present]) o scaricato. Null se non si può scaricare (brani del telefono, artisti).
     */
    fun status(container: String, present: Set<String>): Status? {
        if (!downloadable(container)) return null
        val set = sets.firstOrNull { it.container == container } ?: return Status(Status.State.NONE)
        val done = set.songs.count { it.id in present }
        val total = set.songs.size
        return Status(if (done < total) Status.State.DOWNLOADING else Status.State.DONE, done, total)
    }

    /**
     * Cosa fare coi file sul telefono ([present], per ID del brano): scaricare quelli che mancano,
     * nell'ordine in cui li hai chiesti, e cancellare quelli che nessun album o playlist vuole più
     * (un brano in due playlist resta finché ce n'è una).
     */
    fun plan(present: Set<String>): Pair<List<String>, Set<String>> {
        val wanted = songIds
        return wanted.filter { it !in present } to present.filterTo(LinkedHashSet()) { it !in wanted }
    }

    /**
     * Cambio di server (dal PC al NAS): i brani scaricati prendono l'ID dello stesso brano sul
     * server nuovo ([ServerSync.Matcher.sameSong]); l'album diventa quello nuovo, la playlist
     * quella col nome uguale ([playlists], ID per nome). Restituisce anche gli ID dei file da
     * rinominare (vecchio → nuovo): sono lo stesso brano, non si riscarica niente. Quelli che il
     * server nuovo non ha restano come sono e si ascoltano dal file.
     */
    fun movedTo(match: ServerSync.Matcher, playlists: Map<String, String>): Pair<ServerDownloads, Map<String, String>> {
        val renames = LinkedHashMap<String, String>()
        val moved = sets.map { set ->
            val songs = set.songs.map { s ->
                if (match.has(s.id)) s
                else match.sameSong(s.artist, s.title, s.duration?.let { it * 1000L })?.also { renames[s.id] = it.id } ?: s
            }
            if (songs == set.songs) return@map set
            val id = MediaId.parse(set.container)
            val container = when (id?.kind) {
                RemoteSource.ALBUM -> songs.firstNotNullOfOrNull { s -> s.albumId?.takeIf { s.id in renames.values } }?.let { MediaId(RemoteSource.KEY, RemoteSource.ALBUM, it).toString() }
                RemoteSource.PLAYLIST -> playlists[ArtistNames.key(set.title)]?.let { MediaId(RemoteSource.KEY, RemoteSource.PLAYLIST, it).toString() }
                else -> null
            } ?: set.container
            set.copy(container = container, coverArt = songs.firstOrNull { it.id in renames.values }?.coverArt ?: set.coverArt, songs = songs)
        }
        return ServerDownloads(moved) to renames
    }

    companion object {
        /** I caratteri che Android (e le schede SD in FAT) non accettano nei nomi dei file. */
        private val forbidden = Regex("""[\\/:*?"<>|\p{Cntrl}]""")

        /** Un nome di file o cartella valido: niente caratteri vietati, niente punti o spazi in fondo, non troppo lungo. */
        fun safeName(raw: String, max: Int = 120): String {
            val clean = forbidden.replace(raw, "_").replace(Regex("\\s+"), " ").trim().trim('.', ' ')
            return clean.take(max).trim().trim('.', ' ').ifEmpty { "Senza titolo" }
        }

        /** La sottocartella di un album («Artista - Album») o di una playlist o raccolta (il suo nome). */
        fun folderName(set: DownloadedSet): String {
            val album = MediaId.parse(set.container)?.kind == RemoteSource.ALBUM
            return safeName(if (album && !set.subtitle.isNullOrBlank()) "${set.subtitle} - ${set.title}" else set.title)
        }

        /**
         * Il nome del file scaricato: in un album il numero di traccia («13 - Numb.mp3», «2-03 - …»
         * per il disco 2), in una playlist o raccolta l'artista («Linkin Park - Numb.mp3»).
         */
        fun fileName(song: Song, set: DownloadedSet): String {
            val album = MediaId.parse(set.container)?.kind == RemoteSource.ALBUM
            val base = when {
                album && song.track != null -> {
                    val disc = song.discNumber?.takeIf { it > 1 }?.let { "$it-" }.orEmpty()
                    "$disc${song.track.toString().padStart(2, '0')} - ${song.title}"
                }
                !album && !song.artist.isNullOrBlank() -> "${song.artist} - ${song.title}"
                else -> song.title
            }
            val ext = song.suffix?.trim()?.lowercase()?.takeIf { it.matches(Regex("[a-z0-9]{1,5}")) } ?: "mp3"
            return "${safeName(base)}.$ext"
        }

        /** Gli album e le playlist del server che si possono scaricare. */
        fun downloadable(container: String): Boolean {
            val id = MediaId.parse(container) ?: return false
            return id.source == RemoteSource.KEY && id.id != null && id.parent == null && (id.kind == RemoteSource.ALBUM || id.kind == RemoteSource.PLAYLIST)
        }
    }
}

/** Un album o una playlist del server salvati sul telefono, coi dati dei brani di quando li hai scaricati. */
@Serializable
data class DownloadedSet(
    /** L'album o la playlist sul server, es. "remote/album/al-1". */
    val container: String,
    val title: String,
    val subtitle: String? = null,
    val coverArt: String? = null,
    val songs: List<Song> = emptyList(),
) {
    /** La chiave nella sezione «Scaricati sul telefono»: "album~al-1". */
    val key: String get() = MediaId.parse(container)?.let { "${it.kind}~${it.id}" } ?: container
}
