package io.github.baldborne94.baldmusic.core.library

/**
 * Il tasto «precedente», uguale nell'app, in notifica e in Android Auto. Oltre [RESTART_AFTER_MS]
 * ricomincia il brano; prima torna al brano che hai sentito davvero prima di questo ([history],
 * il più recente in fondo), anche col casuale e anche se l'avevi saltato (e quindi era uscito
 * dalla coda: si rimette prima di quello in corso). Prima col casuale, o partendo da metà di una
 * raccolta, «precedente» ricominciava solo il brano: nell'ordine non c'era un brano prima.
 */
object GoBack {

    const val RESTART_AFTER_MS = 3_000L

    sealed interface Step {
        /** Si ricomincia il brano in corso. */
        data object Restart : Step
        /** Si va al brano [index] della coda. */
        data class Seek(val index: Int) : Step
        /** Il brano [mediaId] non è più in coda: si rimette prima di quello in corso e si suona. */
        data class Reinsert(val mediaId: String) : Step
    }

    /**
     * [queue]: gli ID della coda, [current] il brano in corso, [previousIndex] quello prima
     * nell'ordine del lettore (casuale compreso), se c'è.
     */
    fun step(positionMs: Long, queue: List<String>, current: Int, history: List<String>, previousIndex: Int?): Step {
        if (positionMs > RESTART_AFTER_MS) return Step.Restart
        val last = history.lastOrNull()
        if (last != null) {
            val at = queue.indices.firstOrNull { it != current && queue[it] == last }
            return if (at != null) Step.Seek(at) else Step.Reinsert(last)
        }
        return previousIndex?.let { Step.Seek(it) } ?: Step.Restart
    }
}
