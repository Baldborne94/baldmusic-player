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
