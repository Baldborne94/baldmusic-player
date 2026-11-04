package io.github.baldborne94.baldmusic.core.library

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.tan

/**
 * Quanto è forte un brano: loudness integrata (LUFS) e picco dei campioni (0…1); e dove comincia
 * e finisce il suono ([startMs], [endMs]: il primo e l'ultimo pezzo da 100 ms sopra [SILENCE]).
 */
data class Measure(val lufs: Float, val peak: Float, val startMs: Long = 0, val endMs: Long? = null) {
    val trim get() = endMs?.let { Trim(startMs, it) }
}

/** Dove comincia e finisce davvero il suono di un file, in millisecondi dall'inizio. */
@kotlinx.serialization.Serializable
data class Trim(val startMs: Long, val endMs: Long)

/**
 * Misura la loudness di un brano come ITU-R BS.1770 / EBU R128, lo stesso metodo che
 * usano ReplayGain 2.0, Spotify e le radio: filtro "K" che pesa le frequenze come
 * l'orecchio, blocchi da 400 ms che si sovrappongono, e due soglie che tolgono i
 * silenzi e i pezzi molto più piani del resto. Riceve il PCM decodificato a pezzi,
 * così non serve tenere il brano in memoria.
 */
class LoudnessMeter(val sampleRate: Int, val channels: Int) {

    private val pre: DoubleArray
    private val rlb: DoubleArray

    init {
        require(sampleRate > 0 && channels > 0)
        // Coefficienti per qualsiasi frequenza di campionamento (come libebur128).
        var f0 = 1681.974450955533
        val gain = 3.999843853973347
        var q = 0.7071752369554196
        var k = tan(PI * f0 / sampleRate)
        val vh = 10.0.pow(gain / 20.0)
        val vb = vh.pow(0.4996667741545416)
        var a0 = 1.0 + k / q + k * k
        pre = doubleArrayOf(
            (vh + vb * k / q + k * k) / a0, 2.0 * (k * k - vh) / a0, (vh - vb * k / q + k * k) / a0,
            2.0 * (k * k - 1.0) / a0, (1.0 - k / q + k * k) / a0,
        )
        f0 = 38.13547087602444
        q = 0.5003270373238773
        k = tan(PI * f0 / sampleRate)
        a0 = 1.0 + k / q + k * k
        rlb = doubleArrayOf(1.0, -2.0, 1.0, 2.0 * (k * k - 1.0) / a0, (1.0 - k / q + k * k) / a0)
    }

    // Stato dei due filtri per canale (forma diretta II trasposta).
    private val preZ1 = DoubleArray(channels)
    private val preZ2 = DoubleArray(channels)
    private val rlbZ1 = DoubleArray(channels)
    private val rlbZ2 = DoubleArray(channels)

    /** Pezzi da 100 ms: quattro di fila fanno un blocco da 400 ms (sovrapposto del 75%). */
    private val step = max(1, sampleRate / 10)
    private val sums = DoubleArray(channels)
    private var inStep = 0
    private val subBlocks = ArrayList<Double>()
    private var peak = 0.0
    // I pezzi da 100 ms con del suono: il primo e l'ultimo dicono dove sono i silenzi.
    private var stepPeak = 0.0
    private var firstLoud = -1
    private var lastLoud = -1

    /** PCM a 16 bit, canali alternati. */
    fun addShorts(samples: ShortArray, frames: Int) {
        for (i in 0 until frames) {
            for (c in 0 until channels) sample(c, samples[i * channels + c] / 32768.0)
            endFrame()
        }
    }

    /** PCM in virgola mobile (−1…1), canali alternati. */
    fun addFloats(samples: FloatArray, frames: Int) {
        for (i in 0 until frames) {
            for (c in 0 until channels) sample(c, samples[i * channels + c].toDouble())
            endFrame()
        }
    }

    private fun sample(c: Int, x: Double) {
        peak = max(peak, abs(x))
        stepPeak = max(stepPeak, abs(x))
        val y = pre[0] * x + preZ1[c]
        preZ1[c] = pre[1] * x - pre[3] * y + preZ2[c]
        preZ2[c] = pre[2] * x - pre[4] * y
        val z = rlb[0] * y + rlbZ1[c]
        rlbZ1[c] = rlb[1] * y - rlb[3] * z + rlbZ2[c]
        rlbZ2[c] = rlb[2] * y - rlb[4] * z
        sums[c] += z * z
    }

    private fun endFrame() {
        if (++inStep < step) return
        // Ogni canale pesa uguale; i surround sono rari nella musica. Un mono si ascolta sui due
        // altoparlanti: si misura come "doppio mono" (ReplayGain 2.0, EBU), cioè come lo stesso
        // suono in stereo, altrimenti risulterebbe 3 dB più piano e suonerebbe più forte degli altri.
        subBlocks += sums.sum() * (if (channels == 1) 2 else 1) / step
        if (stepPeak > SILENCE) {
            if (firstLoud < 0) firstLoud = subBlocks.size - 1
            lastLoud = subBlocks.size - 1
        }
        stepPeak = 0.0
        sums.fill(0.0)
        inStep = 0
    }

    /** La misura finale; null se il brano è più corto di un blocco o è tutto silenzio. */
    fun result(): Measure? {
        if (subBlocks.size < 4) return null
        val blocks = DoubleArray(subBlocks.size - 3) { i -> (subBlocks[i] + subBlocks[i + 1] + subBlocks[i + 2] + subBlocks[i + 3]) / 4 }
        val loud = { z: Double -> -0.691 + 10 * log10(z) }
        val absolute = blocks.filter { it > 0 && loud(it) > -70.0 }
        if (absolute.isEmpty()) return null
        val relative = loud(absolute.average()) - 10.0
        val gated = absolute.filter { loud(it) > relative }
        val msPerStep = step * 1000L / sampleRate
        return Measure(
            loud(gated.average()).toFloat(), peak.toFloat(),
            startMs = firstLoud.coerceAtLeast(0) * msPerStep,
            endMs = (lastLoud + 1).takeIf { lastLoud >= 0 }?.let { it * msPerStep },
        )
    }

    companion object {
        /** Il livello a cui ReplayGain 2.0 porta ogni brano. */
        const val REFERENCE_LUFS = -18f

        /** Sotto −50 dBFS è silenzio (o il fruscio di fondo di un file scaricato). */
        const val SILENCE = 0.0032

        /** Dalla misura al ReplayGain del brano, come lo scriverebbero rsgain o Picard. */
        fun replayGain(m: Measure) = ReplayGain(trackGain = REFERENCE_LUFS - m.lufs, trackPeak = m.peak, measured = true)
    }
}
