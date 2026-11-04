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
     * Si sfuma? Mai tra due brani attaccati nello stesso disco ([sameAlbum], vedi [inSequence]:
     * dal vivo e concept restano attaccati), mai su brani troppo corti per avere un inizio e una fine, e mai con una
     * puntata di un podcast ([episode]): la voce non si sfuma, e la puntata dopo riparte da
     * dove eri arrivato.
     */
    fun applies(seconds: Int, durationMs: Long, sameAlbum: Boolean, episode: Boolean = false): Boolean =
        seconds > 0 && !sameAlbum && !episode && durationMs >= (seconds * 2 + 5) * 1000L

    /**
     * Due brani attaccati nel disco: stesso album ([sameAlbum]) e il secondo è la traccia dopo (o
     * la prima del disco dopo). Solo questi restano senza dissolvenza: un disco dal vivo o un
     * concept ascoltato in ordine. Lo stesso album in un altro ordine (casuale, raccolte) si sfuma,
     * e senza numeri di traccia anche. Prima bastava lo stesso album: i brani del telefono senza
     * il tag dell'album (Android usa il nome della cartella) non sfumavano mai dentro una raccolta.
     */
    fun inSequence(sameAlbum: Boolean, disc: Int?, track: Int?, nextDisc: Int?, nextTrack: Int?): Boolean {
        if (!sameAlbum || track == null || nextTrack == null || track <= 0 || nextTrack <= 0) return false
        val d = disc?.takeIf { it > 0 } ?: 1
        val nd = nextDisc?.takeIf { it > 0 } ?: 1
        return (nd == d && nextTrack == track + 1) || (nd == d + 1 && nextTrack == 1)
    }

    /**
     * Dove finisce davvero il suono di un brano lungo [durationMs] ([trim] misurato dall'app): lì
     * comincia la dissolvenza, non nei secondi di silenzio che molti file scaricati hanno in fondo
     * (prima tra due canzoni si sentiva un vuoto). Solo se il silenzio dura almeno [MIN_SILENCE_MS].
     */
    fun audibleEnd(durationMs: Long, trim: Trim?): Long {
        val end = trim?.endMs ?: return durationMs
        return if (end in 1..(durationMs - MIN_SILENCE_MS)) end else durationMs
    }

    /** Da dove far partire il brano che entra: dopo il silenzio iniziale, se è lungo almeno [MIN_SILENCE_MS] e non più di [MAX_LEAD_SILENCE_MS]. */
    fun audibleStart(trim: Trim?): Long {
        val start = trim?.startMs ?: return 0
        return if (start in MIN_SILENCE_MS..MAX_LEAD_SILENCE_MS) start else 0
    }

    /** Un silenzio più corto non si salta: è il respiro normale tra due brani. */
    const val MIN_SILENCE_MS = 1_000L
    /** Più lungo non è un silenzio da saltare: forse un'introduzione molto piano. */
    const val MAX_LEAD_SILENCE_MS = 15_000L

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
