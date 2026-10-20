package io.github.baldborne94.baldmusic.core.lyrics

import io.github.baldborne94.baldmusic.core.library.PlayCount
import io.github.baldborne94.baldmusic.core.library.TrackRef
import io.github.baldborne94.baldmusic.core.library.UserLibrary

/**
 * Quali testi scaricare prima, per averli quando non c'è rete (in macchina, in metro): si
 * scaricano in carica col Wi‑Fi e restano sul telefono. Prima quelli che ascolti di più,
 * poi i preferiti, poi raccolte e playlist a turno (un brano per ognuna, così tutte ne
 * hanno presto), infine il resto del telefono. Senza doppioni.
 */
object LyricsPrefetch {
    /** Quanti brani guarda un giro: i testi già salvati si saltano subito, quindi il giro dopo va avanti. */
    const val MAX_PER_RUN = 3_000

    fun order(lib: UserLibrary, phone: List<TrackRef>, limit: Int = MAX_PER_RUN): List<TrackRef> {
        val out = LinkedHashMap<String, TrackRef>()
        // Sempre l'ID senza il contenitore da cui era stato scelto: è quello con cui si ritrova il brano.
        fun add(t: TrackRef) {
            if (out.size >= limit) return
            val id = UserLibrary.baseId(t.id)
            if (id !in out) out[id] = if (id == t.id) t else t.copy(id = id)
        }
        lib.plays.values.sortedWith(compareByDescending<PlayCount> { it.count }.thenByDescending { it.lastPlayed })
            .forEach { add(it.track) }
        lib.liked.forEach(::add)
        // Prima le raccolte (le cartelle sono il modo in cui ascolti), poi le playlist.
        val lists = lib.playlists.sortedBy { if (it.isCollection) 0 else 1 }.map { it.tracks }
        val longest = lists.maxOfOrNull { it.size } ?: 0
        for (i in 0 until longest) lists.forEach { tracks -> tracks.getOrNull(i)?.let(::add) }
        phone.forEach(::add)
        return out.values.toList()
    }
}
