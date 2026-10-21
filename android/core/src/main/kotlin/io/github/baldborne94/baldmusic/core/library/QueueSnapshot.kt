package io.github.baldborne94.baldmusic.core.library

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * La coda com'era, per riprenderla uguale (in auto, dopo un riavvio, dalla notifica):
 * i brani, quello in corso, il punto, casuale e ripeti. Solo gli ID: i brani si
 * risolvono di nuovo nella loro sorgente, e chi non c'è più si salta.
 */
@Serializable
data class QueueSnapshot(
    val ids: List<String>,
    val index: Int,
    val positionMs: Long,
    val shuffle: Boolean = false,
    /** Come `Player.REPEAT_MODE_*`: 0 spento, 1 un brano, 2 tutta la coda. */
    val repeat: Int = 0,
) {
    val currentId: String? get() = ids.getOrNull(index)

    /**
     * Dove ripartire tra i brani ritrovati ([found]: la coda senza quelli spariti, nello
     * stesso ordine): il brano in corso, o quello che lo seguiva. Si scorrono insieme le
     * due code, così lo stesso brano messo in coda due volte riparte da quello giusto.
     */
    fun indexIn(found: List<String>): Int {
        var j = 0
        ids.forEachIndexed { i, id ->
            if (j < found.size && found[j] == id) {
                if (i >= index) return j
                j++
            }
        }
        // Le code non combaciano (o dopo il brano in corso non resta niente): si cerca per ID.
        val wanted = ids.drop(index).toSet()
        val at = found.indexOf(currentId)
        if (at >= 0) return at
        return found.indexOfFirst { it in wanted }.coerceAtLeast(0)
    }

    fun toJson(): String = json.encodeToString(serializer(), this)

    companion object {
        /** Oltre, la coda si accorcia: si tengono i brani vicini a quello in corso. */
        const val MAX = 500
        private const val BEFORE = 50

        private val json = Json { ignoreUnknownKeys = true }

        fun of(ids: List<String>, index: Int, positionMs: Long, shuffle: Boolean, repeat: Int): QueueSnapshot? {
            if (ids.isEmpty() || index !in ids.indices) return null
            // Si copiano solo i brani tenuti: [ids] può leggere la coda del player un brano alla volta.
            if (ids.size <= MAX) return QueueSnapshot(ids.toList(), index, positionMs, shuffle, repeat)
            // Verso la fine della coda si tengono più brani prima: sempre MAX in tutto.
            val from = (index - BEFORE).coerceIn(0, ids.size - MAX)
            val to = from + MAX
            return QueueSnapshot(ids.subList(from, to).toList(), index - from, positionMs, shuffle, repeat)
        }

        fun fromJson(text: String?): QueueSnapshot? =
            text?.let { runCatching { json.decodeFromString(serializer(), it) }.getOrNull() }?.takeIf { it.index in it.ids.indices }
    }
}
