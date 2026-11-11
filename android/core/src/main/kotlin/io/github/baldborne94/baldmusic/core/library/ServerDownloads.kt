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
