package io.github.baldborne94.baldmusic.core.library

import kotlin.math.min
import kotlin.math.pow

/**
 * ReplayGain: *Album* (predefinito: ascoltando un disco le differenze volute restano; in
 * raccolte, playlist, mix e casuale ogni brano al suo livello), *Brano* (sempre ogni brano allo
 * stesso livello) o *Spento*.
 */
enum class ReplayGainMode { ALBUM, TRACK, OFF }

/**
 * Il volume uguale tra i brani, dai valori di ReplayGain nei tag. Si abbassa e basta:
 * un brano forte scende verso il livello di riferimento, uno piano resta com'è, e mai
 * oltre il picco (niente distorsione). Senza tag il brano suona com'è. Niente effetti,
 * niente equalizzatore: cambia solo il volume.
 */
object Loudness {

    /**
     * Volume da 0 a 1 per [rg] con la modalità scelta. Il guadagno dell'album vale solo ascoltando
     * un disco ([fromAlbum]: il brano è stato scelto dal suo album). Prima valeva sempre, e per i
     * file del telefono senza tag dell'album (Android li raggruppa per cartella) una raccolta
     * intera faceva da «album»: le canzoni restavano più alte o più basse l'una dell'altra.
     */
    fun volume(rg: ReplayGain?, mode: ReplayGainMode, fromAlbum: Boolean = true): Float {
        if (rg == null) return 1f
        val (gain, peak) = when (mode) {
            ReplayGainMode.OFF -> return 1f
            ReplayGainMode.ALBUM -> if (rg.albumGain != null && (fromAlbum || rg.trackGain == null)) rg.albumGain to rg.albumPeak else rg.trackGain to rg.trackPeak
            ReplayGainMode.TRACK -> if (rg.trackGain != null) rg.trackGain to rg.trackPeak else rg.albumGain to rg.albumPeak
        }
        // Un guadagno che non è un numero (tag rovinato) vale come mancante: NaN darebbe un volume
        // NaN al player, e -Infinity il silenzio.
        if (gain == null || !gain.isFinite()) return 1f
        var v = 10f.pow(gain / 20f)
        if (peak != null && peak.isFinite() && peak > 0f) v = min(v, 1f / peak)
        return v.coerceIn(0f, 1f)
    }

    /** Il brano [mediaId] è stato scelto dal suo album (il contenitore dopo «|» è un album). */
    fun fromAlbum(mediaId: String?): Boolean = mediaId != null && MediaId.parse(mediaId)?.parent?.kind == LocalCatalog.ALBUM
}
