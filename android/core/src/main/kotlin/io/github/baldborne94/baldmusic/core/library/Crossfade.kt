package io.github.baldborne94.baldmusic.core.library

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Dissolvenza incrociata tra un brano e l'altro, come i DJ: negli ultimi secondi il
 * brano che finisce si abbassa mentre il successivo parte e sale. Solo volume: i file
 * restano originali. Stesse regole del web (crossfade.ts).
 */
object Crossfade {

    /** Durate tra cui scegliere, in secondi; 0 = spenta. */
    val CHOICES = listOf(0, 3, 6, 9, 12)
    const val DEFAULT_SECONDS = 6

    /**
     * Si sfuma? Mai tra due brani dello stesso album (dal vivo e concept restano
     * attaccati), mai su brani troppo corti per avere un inizio e una fine, e mai con una
     * puntata di un podcast ([episode]): la voce non si sfuma, e la puntata dopo riparte da
     * dove eri arrivato.
     */
    fun applies(seconds: Int, durationMs: Long, sameAlbum: Boolean, episode: Boolean = false): Boolean =
        seconds > 0 && !sameAlbum && !episode && durationMs >= (seconds * 2 + 5) * 1000L

    /**
     * Serve controllare, più volte al secondo, se è ora di sfumare? Solo mentre si suona (o si
     * sta per suonare) con la dissolvenza accesa: in pausa o spenta il controllo si ferma.
     */
    fun watching(seconds: Int, playWhenReady: Boolean): Boolean = seconds > 0 && playWhenReady

    /** Volumi a metà sfumatura ([t] da 0 a 1): uscente ed entrante, a potenza costante. */
    fun gains(t: Float): Pair<Float, Float> {
        val x = t.coerceIn(0f, 1f) * PI.toFloat() / 2
        return cos(x) to sin(x)
    }
}
