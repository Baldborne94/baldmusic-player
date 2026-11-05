package io.github.baldborne94.baldmusic.core.library

/**
 * «Schermo copertina»: il telefono appoggiato mostra la copertina di quello che suona, come il
 * salvaschermo di un Kindle. Uno schermo spento non può mostrare niente (il Kindle ci riesce
 * perché l'inchiostro elettronico non consuma): qui lo schermo resta acceso, poco luminoso, solo
 * finché suona. Regole pure: quando tenerlo acceso e come spostare la copertina perché uno
 * schermo OLED non resti segnato.
 */
object CoverScreen {
    /** Fermo da più di così (pausa, fine della coda): lo schermo si lascia spegnere. */
    const val GRACE_MS = 15_000L

    /** Ogni quanto la copertina si sposta un poco. */
    const val SHIFT_EVERY_MS = 60_000L

    /** Di quanto al massimo si sposta, in dp, da ogni lato. */
    const val SHIFT_DP = 10

    /** Luminosità dello schermo (da 0 a 1): la copertina si vede al buio e consuma poco. */
    const val BRIGHTNESS = 0.2f

    /** Di notte (dalle [NIGHT_FROM] alle [NIGHT_UNTIL]) ancora meno: sul comodino al buio basta. */
    const val NIGHT_BRIGHTNESS = 0.08f
    const val NIGHT_FROM = 22
    const val NIGHT_UNTIL = 7

    /** Sotto questa percentuale, senza caricatore, lo schermo non resta acceso. */
    const val LOW_BATTERY = 15

    /** Dopo un tocco i comandi (e la luminosità normale) restano per così. */
    const val CONTROLS_MS = 5_000L

    /**
     * Tenere acceso lo schermo: mentre suona e per [GRACE_MS] da quando si è fermato
     * ([stoppedAtMs]), così il passaggio tra due brani non lo spegne. Fermato dal timer di
     * spegnimento ([byTimer]) si spegne subito, insieme alla musica. Con poca batteria e senza
     * caricatore ([batteryPercent] sotto [LOW_BATTERY], [charging] falso) mai.
     */
    fun keepAwake(
        playing: Boolean,
        stoppedAtMs: Long?,
        nowMs: Long,
        byTimer: Boolean = false,
        batteryPercent: Int? = null,
        charging: Boolean = false,
    ): Boolean {
        if (!charging && batteryPercent != null && batteryPercent < LOW_BATTERY) return false
        if (playing) return true
        return !byTimer && stoppedAtMs != null && nowMs - stoppedAtMs < GRACE_MS
    }

    /** La luminosità all'ora [hour] (0–23, ora del telefono). */
    fun brightness(hour: Int): Float = if (hour >= NIGHT_FROM || hour < NIGHT_UNTIL) NIGHT_BRIGHTNESS else BRIGHTNESS

    /** Cosa fa toccare lo schermo copertina. */
    enum class Touch { PLAY_PAUSE, LIKE }

    /**
     * [count] tocchi di fila: uno ferma la musica o la fa ripartire (e mostra i comandi), due
     * mettono o tolgono «mi piace» senza toccare la musica.
     */
    fun tap(count: Int): Touch? = when (count) {
        1 -> Touch.PLAY_PAUSE
        2 -> Touch.LIKE
        else -> null
    }

    /**
     * Uno scorrimento di lato di [dragPx] punti (negativo = verso sinistra), su uno schermo largo
     * [widthPx]: +1 il brano dopo (verso sinistra, come sfogliare), −1 quello prima, 0 niente
     * (meno di un quinto dello schermo: era un tocco un po' mosso).
     */
    fun swipe(dragPx: Float, widthPx: Float): Int = when {
        widthPx <= 0f || kotlin.math.abs(dragPx) < widthPx / 5f -> 0
        dragPx < 0f -> 1
        else -> -1
    }

    /** Da quando si è fermato: resta lo stesso finché non riparte. */
    fun stoppedAt(playing: Boolean, before: Long?, nowMs: Long): Long? = if (playing) null else before ?: nowMs

    /** Senza toccare il telefono per così, con la musica appena partita, lo schermo copertina si apre da solo. */
    const val IDLE_MS = 10_000L

    /**
     * Si arma quando parte la musica ([playing] dopo [wasPlaying] fermo) o quando parte una coda
     * nuova ([newQueue]: hai scelto altro da ascoltare); resta armato finché non si apre.
     */
    fun arms(wasPlaying: Boolean, playing: Boolean, newQueue: Boolean): Boolean = playing && (!wasPlaying || newQueue)

    /**
     * Aprirlo da solo adesso: acceso nelle impostazioni, armato, la musica suona e non tocchi il
     * telefono da [IDLE_MS]. Mai col testo aperto ([reading]): lo stai leggendo. Una volta sola
     * per ogni partenza: chiuso a mano non torna finché la musica non riparte.
     */
    fun autoOpen(enabled: Boolean, armed: Boolean, playing: Boolean, reading: Boolean, idleMs: Long): Boolean =
        enabled && armed && playing && !reading && idleMs >= IDLE_MS

    /** Lo spostamento (dx, dy in dp) al passo [step]: un giro attorno al centro, mai fermo nello stesso punto. */
    fun shift(step: Long): Pair<Int, Int> {
        val (x, y) = PATH[Math.floorMod(step, PATH.size.toLong()).toInt()]
        return x * SHIFT_DP to y * SHIFT_DP
    }

    private val PATH = listOf(0 to 0, 1 to 0, 1 to 1, 0 to 1, -1 to 1, -1 to 0, -1 to -1, 0 to -1, 1 to -1)
}
