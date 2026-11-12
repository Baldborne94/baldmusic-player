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
