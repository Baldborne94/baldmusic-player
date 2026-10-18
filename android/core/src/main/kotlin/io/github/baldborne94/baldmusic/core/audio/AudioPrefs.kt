package io.github.baldborne94.baldmusic.core.audio

import kotlinx.serialization.Serializable

/** Le preferenze audio: equalizzatore, effetti, velocità. Si salvano come JSON. */
@Serializable
data class AudioPrefs(
    val eqEnabled: Boolean = false,
    /** Preset dell'equalizzatore del telefono, -1 = bande regolate a mano. */
    val preset: Int = -1,
    /** Livello di ogni banda in millibel (0 = piatto). */
    val bands: List<Int> = emptyList(),
    /** Bass boost, 0..1000. */
    val bassBoost: Int = 0,
    /** Effetto spazialità (virtualizer), 0..1000. */
    val virtualizer: Int = 0,
    /** Guadagno della normalizzazione del volume in millibel, 0 = spenta. */
    val loudness: Int = 0,
    /** Velocità di riproduzione, 0.5..2.0. */
    val speed: Float = 1f,
    /** Salta i silenzi (inizio/fine brano, pause lunghe). */
    val skipSilence: Boolean = false,
) {
    fun withBand(index: Int, level: Int, count: Int): AudioPrefs {
        val levels = MutableList(count) { bands.getOrElse(it) { 0 } }
        if (index in levels.indices) levels[index] = level
        return copy(bands = levels, preset = -1)
    }

    fun withSpeed(value: Float) = copy(speed = ((value * 20).toInt() / 20f).coerceIn(0.5f, 2f))

    companion object {
        val SPEEDS = listOf(0.5f, 0.75f, 0.9f, 1f, 1.1f, 1.25f, 1.5f, 2f)
    }
}
