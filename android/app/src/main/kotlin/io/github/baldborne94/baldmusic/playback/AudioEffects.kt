package io.github.baldborne94.baldmusic.playback

import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.Virtualizer
import io.github.baldborne94.baldmusic.core.audio.AudioPrefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Gli effetti audio del telefono (equalizzatore, bass boost, spazialità,
 * normalizzazione) agganciati alla sessione audio del player.
 *
 * Ogni produttore li implementa a modo suo e alcuni non li hanno: ogni chiamata
 * è protetta e un effetto che manca semplicemente non compare nelle impostazioni.
 */
class AudioEffects {

    data class Info(
        /** Frequenza centrale di ogni banda, in Hz. */
        val bands: List<Int>,
        val minLevel: Int,
        val maxLevel: Int,
        val presets: List<String>,
        val bassBoost: Boolean,
        val virtualizer: Boolean,
        val loudness: Boolean,
    )

    private val _info = MutableStateFlow<Info?>(null)

    /** null finché il player non ha una sessione audio (o se il telefono non ha effetti). */
    val info: StateFlow<Info?> = _info.asStateFlow()

    private var eq: Equalizer? = null
    private var bass: BassBoost? = null
    private var virt: Virtualizer? = null
    private var loud: LoudnessEnhancer? = null

    @Synchronized
    fun attach(sessionId: Int, prefs: AudioPrefs) {
        release()
        if (sessionId <= 0) return
        eq = runCatching { Equalizer(0, sessionId) }.getOrNull()
        bass = runCatching { BassBoost(0, sessionId) }.getOrNull()?.takeIf { runCatching { it.strengthSupported }.getOrDefault(false) }
        virt = runCatching { Virtualizer(0, sessionId) }.getOrNull()?.takeIf { runCatching { it.strengthSupported }.getOrDefault(false) }
        loud = runCatching { LoudnessEnhancer(sessionId) }.getOrNull()
        val e = eq
        _info.value = Info(
            bands = e?.let { runCatching { (0 until it.numberOfBands).map { b -> it.getCenterFreq(b.toShort()) / 1000 } }.getOrNull() }.orEmpty(),
            minLevel = e?.let { runCatching { it.bandLevelRange[0].toInt() }.getOrNull() } ?: -1500,
            maxLevel = e?.let { runCatching { it.bandLevelRange[1].toInt() }.getOrNull() } ?: 1500,
            presets = e?.let { runCatching { (0 until it.numberOfPresets).map { p -> it.getPresetName(p.toShort()) } }.getOrNull() }.orEmpty(),
            bassBoost = bass != null,
            virtualizer = virt != null,
            loudness = loud != null,
        )
        apply(prefs)
    }

    @Synchronized
    fun apply(p: AudioPrefs) {
        eq?.let { e ->
            runCatching {
                e.enabled = p.eqEnabled
                if (p.eqEnabled) {
                    if (p.preset >= 0 && p.preset < e.numberOfPresets) e.usePreset(p.preset.toShort())
                    else p.bands.forEachIndexed { i, level -> if (i < e.numberOfBands) e.setBandLevel(i.toShort(), level.toShort()) }
                }
            }
        }
        bass?.let { b -> runCatching { b.enabled = p.eqEnabled && p.bassBoost > 0; if (p.bassBoost > 0) b.setStrength(p.bassBoost.toShort()) } }
        virt?.let { v -> runCatching { v.enabled = p.eqEnabled && p.virtualizer > 0; if (p.virtualizer > 0) v.setStrength(p.virtualizer.toShort()) } }
        loud?.let { l -> runCatching { l.setTargetGain(p.loudness); l.enabled = p.loudness > 0 } }
    }

    /** I livelli delle bande di un preset, per disegnare i cursori quando lo si sceglie. */
    @Synchronized
    fun presetLevels(preset: Int): List<Int>? = eq?.let { e ->
        runCatching {
            val before = (0 until e.numberOfBands).map { e.getBandLevel(it.toShort()).toInt() }
            e.usePreset(preset.toShort())
            val levels = (0 until e.numberOfBands).map { e.getBandLevel(it.toShort()).toInt() }
            if (!e.enabled) before.forEachIndexed { i, l -> e.setBandLevel(i.toShort(), l.toShort()) }
            levels
        }.getOrNull()
    }

    @Synchronized
    fun release() {
        listOf(eq, bass, virt, loud).forEach { runCatching { it?.release() } }
        eq = null
        bass = null
        virt = null
        loud = null
    }
}
