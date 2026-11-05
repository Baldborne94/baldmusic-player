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

    /** Dopo un tocco i comandi (e la luminosità normale) restano per così. */
    const val CONTROLS_MS = 5_000L

    /**
     * Tenere acceso lo schermo: mentre suona e per [GRACE_MS] da quando si è fermato
     * ([stoppedAtMs]), così il passaggio tra due brani non lo spegne.
     */
    fun keepAwake(playing: Boolean, stoppedAtMs: Long?, nowMs: Long): Boolean =
        playing || (stoppedAtMs != null && nowMs - stoppedAtMs < GRACE_MS)

    /** Da quando si è fermato: resta lo stesso finché non riparte. */
    fun stoppedAt(playing: Boolean, before: Long?, nowMs: Long): Long? = if (playing) null else before ?: nowMs

    /** Lo spostamento (dx, dy in dp) al passo [step]: un giro attorno al centro, mai fermo nello stesso punto. */
    fun shift(step: Long): Pair<Int, Int> {
        val (x, y) = PATH[Math.floorMod(step, PATH.size.toLong()).toInt()]
        return x * SHIFT_DP to y * SHIFT_DP
    }

    private val PATH = listOf(0 to 0, 1 to 0, 1 to 1, 0 to 1, -1 to 1, -1 to 0, -1 to -1, 0 to -1, 1 to -1)
}
