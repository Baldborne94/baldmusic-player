package io.github.baldborne94.baldmusic.core.library

/**
 * "Continua ad ascoltare": finita la coda (raccolta, album, playlist), invece di fermarsi si
 * va avanti con musica simile, come in macchina si vorrebbe. Dal brano del telefono la sua
 * radio (stesso artista, stesso periodo, stessa cartella); da un brano del server brani a caso
 * di Navidrome. Si aggiunge quando parte l'ultimo brano, così la dissolvenza ha il brano dopo.
 */
object Autoplay {
    /** Quanti brani si aggiungono per volta. */
    const val BATCH = 25

    /**
     * Se aggiungere adesso: attivo nelle Impostazioni, nessun brano dopo quello in corso, senza
     * "ripeti" (che ricomincia da sé) e senza il timer "a fine brano" (che sta per fermare tutto).
     */
    fun needed(enabled: Boolean, hasNext: Boolean, repeatOff: Boolean, endOfTrackTimer: Boolean): Boolean =
        enabled && !hasNext && repeatOff && !endOfTrackTimer

    /** Da dove prendere i brani dopo [lastTrackId]; null se non si sa (non è un brano). */
    fun source(lastTrackId: String): MediaId? {
        val id = MediaId.parse(lastTrackId)?.takeIf { it.isTrack && it.id != null } ?: return null
        return when (id.source) {
            LocalCatalog.KEY -> MediaId(LocalCatalog.KEY, LocalCatalog.RADIO, id.id)
            RemoteSource.KEY -> MediaId(RemoteSource.KEY, RemoteSource.RANDOM)
            else -> null
        }
    }

    /**
     * I brani da aggiungere tra quelli proposti ([candidates], ID completi): niente che sia già
     * nella coda o ripetuto, al massimo [max]. Restituisce gli indici dei candidati scelti.
     */
    fun pick(candidates: List<String>, queue: Collection<String>, max: Int = BATCH): List<Int> {
        val seen = queue.mapTo(HashSet(), UserLibrary::baseId)
        return candidates.indices.filter { i -> seen.add(UserLibrary.baseId(candidates[i])) }.take(max)
    }
}
