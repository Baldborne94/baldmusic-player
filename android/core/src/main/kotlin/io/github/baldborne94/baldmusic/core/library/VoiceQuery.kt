package io.github.baldborne94.baldmusic.core.library

import io.github.baldborne94.baldmusic.core.library.LibraryNode.Kind

/**
 * Cosa suonare per una richiesta vocale ("Ok Google, metti Movie Soundtracks su
 * BaldMusic"). Le parole che dicono il tipo ("la raccolta", "la playlist", "l'album")
 * si tolgono e fanno preferire quel tipo.
 */
object VoiceQuery {

    private val kinds = listOf(
        Kind.PLAYLIST to Regex("^(la |le )?(raccolta|raccolte|playlist)\\s+", RegexOption.IGNORE_CASE),
        Kind.ALBUM to Regex("^(l'|l’|il |lo )?(album|disco)\\s+", RegexOption.IGNORE_CASE),
        Kind.ARTIST to Regex("^(l'|l’)?(artista)\\s+", RegexOption.IGNORE_CASE),
    )

    /** La richiesta senza le parole sul tipo, e il tipo chiesto se c'era. */
    fun parse(query: String): Pair<String, Kind?> {
        val q = query.trim()
        kinds.forEach { (kind, regex) -> regex.find(q)?.let { return q.substring(it.range.last + 1).trim() to kind } }
        return q to null
    }

    private val podcast = Regex(
        "^(?:(?:il|un|i|un altro|l'ultimo|l’ultimo) )?podcast(?: di| del| della)?(?:\\s+|$)|" +
            "^(?:(?:la|una|l'ultima|l’ultima|la nuova|la prossima) )?puntata(?: del podcast| di| del| della)?(?:\\s+|$)",
        RegexOption.IGNORE_CASE,
    )

    /** Una richiesta di podcast: il nome (vuoto se non l'ha detto) e se ha chiesto proprio l'ultima uscita. */
    data class PodcastAsk(val name: String, val latest: Boolean)

    private val latest = Regex("^(?:l'ultim[oa]|l’ultim[oa]|la nuova) ", RegexOption.IGNORE_CASE)

    /**
     * Una richiesta che parla di podcast ("il podcast Non Aprite Quella Podcast", "l'ultima
     * puntata di NAQP", "un podcast"). Null: non parla di podcast (ma il nome di un podcast
     * detto da solo lo trova [pick]).
     */
    fun podcast(query: String): PodcastAsk? {
        val q = query.trim()
        val m = podcast.find(q) ?: return null
        return PodcastAsk(q.substring(m.range.last + 1).trim(), latest.containsMatchIn(q))
    }

    /**
     * Il contenitore da suonare per intero, se la richiesta ne nomina uno: lo stesso nome
     * esatto (prima raccolte e playlist, poi artisti, poi album) o, se è stato detto il tipo,
     * il primo di quel tipo che contiene le parole. Null: si suonano i brani trovati.
     */
    fun pick(query: String, results: List<LibraryNode>): LibraryNode? {
        val (what, kind) = parse(query)
        val key = ArtistNames.key(what)
        if (key.isEmpty()) return null
        val order = listOf(Kind.PLAYLIST, Kind.ARTIST, Kind.ALBUM)
        val containers = results.filter { it.kind in order && (kind == null || it.kind == kind) }
        containers.filter { ArtistNames.key(it.title) == key }.minByOrNull { order.indexOf(it.kind) }?.let { return it }
        if (kind != null) return containers.firstOrNull { ArtistNames.key(it.title).contains(key) }
        return null
    }
}
