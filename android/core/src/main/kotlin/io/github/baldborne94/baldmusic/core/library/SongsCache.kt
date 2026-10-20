package io.github.baldborne94.baldmusic.core.library

/**
 * L'elenco dei brani del telefono letto da MediaStore, e quando rileggerlo: solo quando
 * Android dice che i file sono cambiati ([changed]), o dopo [maxAgeMs] per sicurezza.
 * Se la rilettura dà gli stessi brani si tiene l'elenco di prima, la stessa istanza: così
 * l'indice di album e artisti e gli elenchi già pronti ([LocalCatalog]) restano validi.
 */
class SongsCache<T>(private val maxAgeMs: Long, private val clock: () -> Long = System::currentTimeMillis) {
    @Volatile private var list: List<T>? = null
    @Volatile private var at = 0L
    @Volatile private var stale = true

    /** L'elenco da usare senza rileggere, o null se va riletto. */
    fun fresh(): List<T>? = list?.takeIf { !stale && clock() - at < maxAgeMs }

    /** I file sono cambiati (o i tag letti, o le impostazioni): la prossima volta si rilegge. */
    fun changed() {
        stale = true
    }

    /**
     * Si sta per rileggere: un cambiamento che arriva durante la lettura la fa ripetere la
     * volta dopo, invece di perdersi.
     */
    fun reading() {
        stale = false
    }

    /** L'elenco appena letto; se è uguale a quello di prima, quello di prima. */
    fun store(read: List<T>): List<T> {
        val kept = list?.takeIf { it == read } ?: read
        list = kept
        at = clock()
        return kept
    }
}
