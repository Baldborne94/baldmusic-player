package io.github.baldborne94.baldmusic.core.library

/**
 * Il tasto del timer di spegnimento in Android Auto: un tocco per passo, come casuale e ripeti.
 * Spento → 15 → 30 → 60 minuti → a fine brano → spento. Un timer messo dal telefono per un altro
 * tempo va al passo dopo quello che resta. Il valore è l'ora di fine (millisecondi), o
 * [END_OF_TRACK], o null (spento): lo stesso di `BaldMusicApp.sleepTimer`.
 */
object SleepTimer {
    const val END_OF_TRACK = -1L
    private val STEPS_MIN = listOf(15, 30, 60)

    fun next(end: Long?, now: Long): Long? {
        if (end == null) return now + STEPS_MIN.first() * MINUTE
        if (end == END_OF_TRACK) return null
        // Il passo in cui si è (il più piccolo che contiene il tempo che resta), poi il successivo.
        val left = end - now
        val current = STEPS_MIN.indexOfFirst { it * MINUTE >= left - SLACK }
        val step = if (current < 0) null else STEPS_MIN.getOrNull(current + 1)
        return step?.let { now + it * MINUTE } ?: END_OF_TRACK
    }

    /** Il nome del tasto: cosa fa adesso il timer. */
    fun label(end: Long?, now: Long): String = when {
        end == null -> "Timer di spegnimento"
        end == END_OF_TRACK -> "Si ferma a fine brano"
        else -> "Si ferma tra ${minutesLeft(end, now)} min"
    }

    /** I minuti che restano, arrotondati in su (fino all'ultimo dice "1 min"). */
    fun minutesLeft(end: Long, now: Long): Long = ((end - now).coerceAtLeast(0) + MINUTE - 1) / MINUTE

    private const val MINUTE = 60_000L
    /** Un timer di 15 minuti messo dal telefono un attimo prima conta ancora come 15 minuti. */
    private const val SLACK = 30_000L
}
