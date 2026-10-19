package io.github.baldborne94.baldmusic.core.library

import kotlin.math.min
import kotlin.math.pow

/**
 * ReplayGain: *Album* (predefinito: dentro un disco le differenze volute restano),
 * *Brano* (ogni brano allo stesso livello, per mix e casuale) o *Spento*.
 */
enum class ReplayGainMode { ALBUM, TRACK, OFF }

/**
 * Il volume uguale tra i brani, dai valori di ReplayGain nei tag. Si abbassa e basta:
 * un brano forte scende verso il livello di riferimento, uno piano resta com'è, e mai
 * oltre il picco (niente distorsione). Senza tag il brano suona com'è. Niente effetti,
 * niente equalizzatore: cambia solo il volume.
 */
object Loudness {

    /** Volume da 0 a 1 per [rg] con la modalità scelta. */
    fun volume(rg: ReplayGain?, mode: ReplayGainMode): Float {
        if (rg == null) return 1f
        val (gain, peak) = when (mode) {
            ReplayGainMode.OFF -> return 1f
            ReplayGainMode.ALBUM -> if (rg.albumGain != null) rg.albumGain to rg.albumPeak else rg.trackGain to rg.trackPeak
            ReplayGainMode.TRACK -> if (rg.trackGain != null) rg.trackGain to rg.trackPeak else rg.albumGain to rg.albumPeak
        }
        // Un guadagno che non è un numero (tag rovinato) vale come mancante: NaN darebbe un volume
        // NaN al player, e -Infinity il silenzio.
        if (gain == null || !gain.isFinite()) return 1f
        var v = 10f.pow(gain / 20f)
        if (peak != null && peak.isFinite() && peak > 0f) v = min(v, 1f / peak)
        return v.coerceIn(0f, 1f)
    }
}
