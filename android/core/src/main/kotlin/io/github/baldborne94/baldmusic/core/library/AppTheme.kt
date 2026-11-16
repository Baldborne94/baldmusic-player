package io.github.baldborne94.baldmusic.core.library

/**
 * Il tema dell'app (scelta dell'utente: «pop di giorno, scuro di sera»). Pop = giallo, contorni
 * neri e rosso, come il logo; scuro = quello di sempre. In automatico segue il tema del telefono:
 * chiaro di giorno, scuro la sera (su Samsung «Modalità scura» → «Dal tramonto all'alba»).
 */
object AppTheme {
    enum class Mode(val label: String) { AUTO("Automatico"), POP("Pop"), DARK("Scuro") }

    val DEFAULT = Mode.AUTO

    /** Pop o scuro, adesso: in automatico pop quando il telefono è chiaro. */
    fun isPop(mode: Mode, systemDark: Boolean): Boolean = when (mode) {
        Mode.AUTO -> !systemDark
        Mode.POP -> true
        Mode.DARK -> false
    }

    /** Il valore salvato nelle impostazioni; uno sconosciuto (o niente) è [DEFAULT]. */
    fun parse(saved: String?): Mode = Mode.entries.firstOrNull { it.name == saved } ?: DEFAULT

    /**
     * Il testo del brano in chiaro (bianco) o in scuro (nero) sul suo sfondo. Nel pop sta su un
     * riquadro bianco, quindi nero; nello scuro lo sfondo è il colore della copertina scurito: con una
     * copertina scura il testo è bianco. Prima le righe che dovevano ancora venire erano sempre nere,
     * e con una copertina nera (Nickelback, «All the Right Reasons») non si leggevano.
     */
    fun lightLyrics(pop: Boolean, backgroundLuminance: Float): Boolean = !pop && backgroundLuminance < LYRICS_LIGHT_BELOW

    /** Sotto questa luminanza (0 nero, 1 bianco) lo sfondo è scuro. */
    const val LYRICS_LIGHT_BELOW = 0.4f
}
