package io.github.baldborne94.baldmusic.core.library

import io.github.baldborne94.baldmusic.core.podcast.PodcastSource

/**
 * I tasti accanto ai comandi, nella notifica e in Android Auto: per la musica cuore, casuale,
 * ripeti e timer; per una puntata di un podcast indietro di 15 s, avanti di 30 s e timer (cuore,
 * casuale e ripeti non servono). Le stesse regole per «In riproduzione», che manda gli stessi comandi.
 */
object SessionControls {

    enum class Button { HEART, SHUFFLE, REPEAT, SLEEP, BACK, FORWARD }

    /** Indietro e avanti dentro una puntata. */
    const val BACK_MS = 15_000L
    const val FORWARD_MS = 30_000L

    // Come Player.REPEAT_MODE_* di Media3 (qui senza Android).
    const val REPEAT_OFF = 0
    const val REPEAT_ONE = 1
    const val REPEAT_ALL = 2

    /** I tasti per il brano in corso ([currentId]; null = niente in coda: nessun tasto). */
    fun layout(currentId: String?): List<Button> = when {
        currentId == null -> emptyList()
        PodcastSource.episodeKey(currentId) != null -> listOf(Button.BACK, Button.FORWARD, Button.SLEEP)
        else -> listOf(Button.HEART, Button.SHUFFLE, Button.REPEAT, Button.SLEEP)
    }

    /**
     * Dove arrivare spostandosi di [deltaMs] da [positionMs]: mai prima dell'inizio né oltre la
     * fine (l'ultimo secondo resta, così la puntata non finisce con un tocco di troppo).
     */
    fun skipTarget(positionMs: Long, durationMs: Long?, deltaMs: Long): Long {
        val target = (positionMs + deltaMs).coerceAtLeast(0)
        val duration = durationMs?.takeIf { it > 0 } ?: return target
        return minOf(target, duration - 1_000).coerceAtLeast(0)
    }

    /** Il tasto ripeti: spento → tutta la coda → il brano → spento. */
    fun nextRepeat(mode: Int): Int = when (mode) {
        REPEAT_OFF -> REPEAT_ALL
        REPEAT_ALL -> REPEAT_ONE
        else -> REPEAT_OFF
    }

    fun likeLabel(liked: Boolean) = if (liked) "Togli da Mi piace" else "Mi piace"
    fun shuffleLabel(on: Boolean) = if (on) "Casuale attivo" else "Casuale"
    fun repeatLabel(mode: Int) = when (mode) {
        REPEAT_ONE -> "Ripeti il brano"
        REPEAT_ALL -> "Ripeti tutto"
        else -> "Ripeti"
    }
    const val BACK_LABEL = "Indietro di 15 secondi"
    const val FORWARD_LABEL = "Avanti di 30 secondi"
}
