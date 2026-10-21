package io.github.baldborne94.baldmusic.core.library

/**
 * Le ultime ricerche fatte dall'app o da Android Auto. Ogni ricerca chiede prima di cercare
 * e poi i risultati: con questo la ricerca si fa una volta sola. Se ne tengono al massimo
 * [max] (ogni lettera scritta è una ricerca nuova) e si dimenticano quando la libreria cambia.
 */
class RecentSearches<V : Any>(private val max: Int = 8) {
    private val entries = object : LinkedHashMap<String, V>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, V>?) = size > max
    }

    /** Il risultato di [query]: quello già avviato, o [start] la prima volta. */
    @Synchronized
    fun getOrPut(query: String, start: () -> V): V = entries.getOrPut(query, start)

    @Synchronized
    fun clear() = entries.clear()

    @get:Synchronized
    val size get() = entries.size
}
