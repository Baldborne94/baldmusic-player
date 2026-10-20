/*
 * Porting in Kotlin di Chromaprint 1.6.2 (https://github.com/acoustid/chromaprint,
 * commit 3b38c100022bdfb49be7797c4828a476f1621d55), algoritmo predefinito (TEST2),
 * costruito con la FFT interna KissFFT e il ricampionatore interno copiato da FFmpeg.
 *
 * Chromaprint: Copyright (C) 2010-2016 Lukas Lalinsky, licenza MIT:
 *
 *   Permission is hereby granted, free of charge, to any person obtaining a copy
 *   of this software and associated documentation files (the "Software"), to deal
 *   in the Software without restriction, including without limitation the rights
 *   to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *   copies of the Software, and to permit persons to whom the Software is
 *   furnished to do so, subject to the following conditions:
 *
 *   The above copyright notice and this permission notice shall be included in
 *   all copies or substantial portions of the Software.
 *
 *   THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *   IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *   FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *   AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *   LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *   OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 *   THE SOFTWARE.
 *
 * Il ricampionatore (classe Resampler) deriva da libavcodec/resample2.c di FFmpeg,
 * Copyright (c) 2004 Michael Niedermayer, licenza GNU LGPL 2.1 o successiva
 * (https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html): per questo, come dice
 * il LICENSE.md di Chromaprint, l'insieme va considerato sotto LGPL 2.1.
 *
 * La FFT (classe KissFftr) deriva da KISS FFT, Copyright (c) 2003-2010 Mark Borgerding,
 * licenza BSD-3-Clause (https://github.com/mborgerding/kissfft).
 *
 * Esattezza: ogni passo rifà le stesse operazioni nello stesso ordine e con gli stessi
 * tipi (float dove la libreria C usa float, double dove usa double, interi a 16/32 bit
 * nel ricampionatore), così l'impronta è identica bit per bit a quella della libreria C.
 * Seno, coseno e logaritmo vengono da StrictMath (fdlibm), uguali su JVM e Android.
 */
package io.github.baldborne94.baldmusic.core.acoustid

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Impronta acustica come Chromaprint (algoritmo predefinito, quello di fpcalc). */
class Chromaprint(sampleRate: Int, channels: Int) {

    private val channels: Int

    // AudioProcessor: mono a 16 bit, ricampionato a 11025 Hz a blocchi di 32768 campioni.
    private val buffer = ShortArray(BUFFER_SIZE)
    private var bufferOffset = 0
    private val resampleBuffer = ShortArray(BUFFER_SIZE)
    private val resampler: Resampler?
    private val leftover: ShortArray
    private var leftoverSize = 0

    // AudioSlicer + FFT: finestre di 4096 campioni ogni 1365.
    private val frame = ShortArray(FRAME_SIZE)
    private var frameFill = 0
    private val fft = KissFftr()
    private val fftInput = FloatArray(FRAME_SIZE)
    private val spectrum = FloatArray(FRAME_SIZE + 2)

    // Chroma, ChromaFilter, ChromaNormalizer.
    private val chroma = DoubleArray(NUM_BANDS)
    private val filterRing = Array(8) { DoubleArray(NUM_BANDS) }
    private var filterOffset = 0
    private var filterSize = 1
    private val filtered = DoubleArray(NUM_BANDS)

    // FingerprintCalculator con la sua immagine integrale "a rotolo".
    private val image = DoubleArray(IMAGE_ROWS * NUM_BANDS)
    private var imageRows = 0
    private var fingerprint = IntArray(256)
    private var fingerprintSize = 0

    private var result: IntArray? = null

    init {
        // Come AudioProcessor::Reset, dove chromaprint_start fallisce.
        require(channels > 0) { "Servono canali audio" }
        require(sampleRate > MIN_SAMPLE_RATE) { "Frequenza di campionamento troppo bassa: $sampleRate" }
        this.channels = channels
        leftover = ShortArray(channels)
        resampler = if (sampleRate != SAMPLE_RATE) Resampler(SAMPLE_RATE, sampleRate) else null
    }

    /** Campioni PCM 16 bit interleaved (come li dà il decoder), anche a pezzi. */
    fun consume(samples: ShortArray, offset: Int = 0, length: Int = samples.size - offset) {
        require(offset >= 0 && length >= 0 && offset + length <= samples.size) { "Intervallo fuori dai campioni" }
        check(result == null) { "Impronta già chiusa con finish()" }
        var pos = offset
        var len = length
        // Un fotogramma (un campione per canale) rimasto a metà dal pezzo precedente.
        if (leftoverSize > 0) {
            while (len > 0 && leftoverSize < channels) {
                leftover[leftoverSize++] = samples[pos++]
                len--
            }
            if (leftoverSize < channels) return
            consumeAligned(leftover, 0, channels)
            leftoverSize = 0
        }
        val remainder = len % channels
        consumeAligned(samples, pos, len - remainder)
        for (i in 0 until remainder) leftover[i] = samples[pos + len - remainder + i]
        leftoverSize = remainder
    }

    /**
     * Fine dell'audio: l'impronta "grezza" (gli interi a 32 bit di chromaprint_get_raw_fingerprint).
     * Non si ferma da sola dopo [MAX_SECONDS]: decide il chiamante quanto audio dare.
     * Chiamarla di nuovo restituisce la stessa impronta; dopo non si può più dare audio.
     */
    fun finish(): IntArray {
        result?.let { return it.copyOf() }
        if (bufferOffset > 0) resample()
        val done = fingerprint.copyOf(fingerprintSize)
        result = done
        return done.copyOf()
    }

    // --- AudioProcessor ---

    private fun consumeAligned(input: ShortArray, start: Int, length: Int) {
        var pos = start
        var frames = length / channels
        while (frames > 0) {
            val n = min(frames, BUFFER_SIZE - bufferOffset)
            load(input, pos, n)
            pos += n * channels
            frames -= n
            if (bufferOffset == BUFFER_SIZE) {
                resample()
                if (bufferOffset == BUFFER_SIZE) return // il ricampionatore non avanza: come in C
            }
        }
    }

    /** Scende a mono come la libreria C: media intera troncata verso lo zero. */
    private fun load(input: ShortArray, start: Int, frames: Int) {
        var o = bufferOffset
        when (channels) {
            1 -> System.arraycopy(input, start, buffer, o, frames).also { o += frames }
            2 -> {
                var p = start
                repeat(frames) {
                    buffer[o++] = ((input[p] + input[p + 1]) / 2).toShort()
                    p += 2
                }
            }
            else -> {
                var p = start
                repeat(frames) {
                    var sum = 0
                    repeat(channels) { sum += input[p++] }
                    buffer[o++] = (sum / channels).toShort()
                }
            }
        }
        bufferOffset += frames
    }

    private fun resample() {
        val r = resampler
        if (r == null) {
            slice(buffer, bufferOffset)
            bufferOffset = 0
            return
        }
        val length = r.resample(resampleBuffer, buffer, bufferOffset, BUFFER_SIZE)
        slice(resampleBuffer, length)
        var remaining = bufferOffset - r.consumed
        if (remaining > 0) System.arraycopy(buffer, r.consumed, buffer, 0, remaining)
        else if (remaining < 0) remaining = 0
        bufferOffset = remaining
    }

    // --- AudioSlicer + FFT ---

    private fun slice(input: ShortArray, length: Int) {
        var pos = 0
        while (pos < length) {
            val n = min(length - pos, FRAME_SIZE - frameFill)
            System.arraycopy(input, pos, frame, frameFill, n)
            frameFill += n
            pos += n
            if (frameFill == FRAME_SIZE) {
                processFrame()
                System.arraycopy(frame, FRAME_INCREMENT, frame, 0, FRAME_SIZE - FRAME_INCREMENT)
                frameFill = FRAME_SIZE - FRAME_INCREMENT
            }
        }
    }

    private fun processFrame() {
        val window = HAMMING
        for (i in 0 until FRAME_SIZE) fftInput[i] = frame[i].toFloat() * window[i]
        fft.transform(fftInput, spectrum)
        // Chroma: energia (float, come in FFTLib::Compute) sommata in double per nota.
        chroma.fill(0.0)
        for (i in MIN_INDEX until MAX_INDEX) {
            val re = spectrum[2 * i]
            val im = spectrum[2 * i + 1]
            val energy = (re * re + im * im).toDouble()
            chroma[NOTES[i]] += energy
        }
        filterChroma()
    }

    // --- ChromaFilter + ChromaNormalizer ---

    private fun filterChroma() {
        chroma.copyInto(filterRing[filterOffset])
        filterOffset = (filterOffset + 1) % 8
        if (filterSize >= CHROMA_FILTER.size) {
            val offset = (filterOffset + 8 - CHROMA_FILTER.size) % 8
            filtered.fill(0.0)
            for (i in 0 until NUM_BANDS) {
                for (j in CHROMA_FILTER.indices) {
                    filtered[i] += filterRing[(offset + j) % 8][i] * CHROMA_FILTER[j]
                }
            }
            normalize(filtered)
            addRow(filtered)
        } else {
            filterSize++
        }
    }

    private fun normalize(v: DoubleArray) {
        var squares = 0.0
        for (x in v) squares += x * x
        val norm = if (squares > 0) sqrt(squares) else 0.0
        if (norm < 0.01) v.fill(0.0) else for (i in v.indices) v[i] /= norm
    }

    // --- FingerprintCalculator ---

    private fun addRow(features: DoubleArray) {
        val cur = (imageRows % IMAGE_ROWS) * NUM_BANDS
        var sum = 0.0
        for (j in 0 until NUM_BANDS) {
            sum += features[j]
            image[cur + j] = sum
        }
        if (imageRows > 0) {
            val last = ((imageRows - 1) % IMAGE_ROWS) * NUM_BANDS
            for (j in 0 until NUM_BANDS) image[cur + j] = image[last + j] + image[cur + j]
        }
        imageRows++
        if (imageRows >= MAX_FILTER_WIDTH) {
            val offset = imageRows - MAX_FILTER_WIDTH
            var bits = 0
            for (c in CLASSIFIERS) bits = (bits shl 2) or GRAY_CODE[c.classify(offset)]
            if (fingerprintSize == fingerprint.size) fingerprint = fingerprint.copyOf(fingerprintSize * 2)
            fingerprint[fingerprintSize++] = bits
        }
    }

    /** RollingIntegralImage::Area: righe = tempo, colonne = note. */
    private fun area(r1: Int, c1: Int, r2: Int, c2: Int): Double {
        if (r1 == r2 || c1 == c2) return 0.0
        val row2 = ((r2 - 1) % IMAGE_ROWS) * NUM_BANDS
        return if (r1 == 0) {
            if (c1 == 0) image[row2 + c2 - 1] else image[row2 + c2 - 1] - image[row2 + c1 - 1]
        } else {
            val row1 = ((r1 - 1) % IMAGE_ROWS) * NUM_BANDS
            if (c1 == 0) image[row2 + c2 - 1] - image[row1 + c2 - 1]
            else image[row2 + c2 - 1] - image[row1 + c2 - 1] - image[row2 + c1 - 1] + image[row1 + c1 - 1]
        }
    }

    private fun Classifier.classify(x: Int): Int {
        val y = y
        val w = width
        val h = height
        val a: Double
        val b: Double
        when (type) {
            0 -> { a = area(x, y, x + w, y + h); b = 0.0 }
            1 -> {
                val h2 = h / 2
                a = area(x, y + h2, x + w, y + h)
                b = area(x, y, x + w, y + h2)
            }
            2 -> {
                val w2 = w / 2
                a = area(x + w2, y, x + w, y + h)
                b = area(x, y, x + w2, y + h)
            }
            3 -> {
                val w2 = w / 2
                val h2 = h / 2
                a = area(x, y + h2, x + w2, y + h) + area(x + w2, y, x + w, y + h2)
                b = area(x, y, x + w2, y + h2) + area(x + w2, y + h2, x + w, y + h)
            }
            4 -> {
                val h3 = h / 3
                a = area(x, y + h3, x + w, y + 2 * h3)
                b = area(x, y, x + w, y + h3) + area(x, y + 2 * h3, x + w, y + h)
            }
            else -> {
                val w3 = w / 3
                a = area(x + w3, y, x + 2 * w3, y + h)
                b = area(x, y, x + w3, y + h) + area(x + 2 * w3, y, x + w, y + h)
            }
        }
        val value = StrictMath.log((1.0 + a) / (1.0 + b))
        return if (value < t1) (if (value < t0) 0 else 1) else (if (value < t2) 2 else 3)
    }

    private class Classifier(
        val type: Int, val y: Int, val height: Int, val width: Int,
        val t0: Double, val t1: Double, val t2: Double,
    )

    companion object {
        /** Secondi di audio che servono (fpcalc usa i primi 120). */
        const val MAX_SECONDS = 120

        /** Frequenza a cui lavora l'algoritmo. */
        const val SAMPLE_RATE = 11025

        /** Algoritmo predefinito (CHROMAPRINT_ALGORITHM_TEST2), quello che si scrive in testa all'impronta. */
        const val ALGORITHM = 1

        private const val MIN_SAMPLE_RATE = 1000
        private const val BUFFER_SIZE = 1024 * 32
        private const val FRAME_SIZE = 4096
        private const val FRAME_INCREMENT = FRAME_SIZE - (FRAME_SIZE - FRAME_SIZE / 3)
        private const val NUM_BANDS = 12
        private const val IMAGE_ROWS = 256 + 1
        private const val MAX_FILTER_WIDTH = 16
        private val CHROMA_FILTER = doubleArrayOf(0.25, 0.75, 1.0, 0.75, 0.25)
        private val GRAY_CODE = intArrayOf(0, 1, 3, 2)

        // FingerprinterConfigurationTest2: Filter(tipo, y, altezza, larghezza) + Quantizer(t0, t1, t2).
        private val CLASSIFIERS = arrayOf(
            Classifier(0, 4, 3, 15, 1.98215, 2.35817, 2.63523),
            Classifier(4, 4, 6, 15, -1.03809, -0.651211, -0.282167),
            Classifier(1, 0, 4, 16, -0.298702, 0.119262, 0.558497),
            Classifier(3, 8, 2, 12, -0.105439, 0.0153946, 0.135898),
            Classifier(3, 4, 4, 8, -0.142891, 0.0258736, 0.200632),
            Classifier(4, 0, 3, 5, -0.826319, -0.590612, -0.368214),
            Classifier(1, 2, 2, 9, -0.557409, -0.233035, 0.0534525),
            Classifier(2, 7, 3, 4, -0.0646826, 0.00620476, 0.0784847),
            Classifier(2, 6, 2, 16, -0.192387, -0.029699, 0.215855),
            Classifier(2, 1, 3, 2, -0.0397818, -0.00568076, 0.0292026),
            Classifier(5, 10, 1, 15, -0.53823, -0.369934, -0.190235),
            Classifier(3, 6, 2, 10, -0.124877, 0.0296483, 0.139239),
            Classifier(2, 1, 1, 14, -0.101475, 0.0225617, 0.231971),
            Classifier(3, 5, 6, 4, -0.0799915, -0.00729616, 0.063262),
            Classifier(1, 9, 2, 12, -0.272556, 0.019424, 0.302559),
            Classifier(3, 4, 2, 14, -0.164292, -0.0321188, 0.0846339),
        )

        /** Finestra di Hamming già divisa per INT16_MAX (PrepareHammingWindow), in float. */
        private val HAMMING = FloatArray(FRAME_SIZE) { i ->
            (1.0 / Short.MAX_VALUE * (0.54 - 0.46 * StrictMath.cos(i * 2.0 * Math.PI / (FRAME_SIZE - 1)))).toFloat()
        }

        private fun freqToIndex(freq: Double): Int = cRound(FRAME_SIZE * freq / SAMPLE_RATE).toInt()

        /** round() di C: metà lontano dallo zero. */
        private fun cRound(x: Double): Double = if (x >= 0.0) floor(x + 0.5) else ceil(x - 0.5)

        // Chroma::PrepareNotes con MIN_FREQ = 28 e MAX_FREQ = 3520.
        private val MIN_INDEX = max(1, freqToIndex(28.0))
        private val MAX_INDEX = min(FRAME_SIZE / 2, freqToIndex(3520.0))
        private val NOTES = IntArray(FRAME_SIZE).also { notes ->
            for (i in MIN_INDEX until MAX_INDEX) {
                val freq = i.toDouble() * SAMPLE_RATE / FRAME_SIZE
                val octave = StrictMath.log(freq / (440.0 / 16.0)) / StrictMath.log(2.0)
                val note = NUM_BANDS * (octave - floor(octave))
                notes[i] = note.toInt()
            }
        }

        /** L'impronta compressa in base64 url-safe senza padding, come `fpcalc` la stampa e come la vuole l'API di AcoustID. */
        fun encode(raw: IntArray): String = base64(compress(raw, ALGORITHM))

        /** FingerprintCompressor: differenze tra interi vicini, posizioni dei bit a 3 bit (e 5 per le eccezioni). */
        internal fun compress(raw: IntArray, algorithm: Int): ByteArray {
            require(raw.size <= 0xFFFFFF) { "Impronta troppo lunga" }
            val normal = BitWriter(raw.size * 8)
            val exceptional = BitWriter(raw.size * 2)
            var normalCount = 0
            var exceptionalCount = 0
            for (i in raw.indices) {
                var x = if (i == 0) raw[0] else raw[i] xor raw[i - 1]
                var bit = 1
                var lastBit = 0
                while (x != 0) {
                    if (x and 1 != 0) {
                        val value = bit - lastBit
                        if (value >= 7) {
                            normal.write(7, 3)
                            exceptional.write(value - 7, 5)
                            exceptionalCount++
                        } else {
                            normal.write(value, 3)
                        }
                        normalCount++
                        lastBit = bit
                    }
                    x = x ushr 1
                    bit++
                }
                normal.write(0, 3)
                normalCount++
            }
            val normalBytes = (normalCount * 3 + 7) / 8
            val exceptionalBytes = (exceptionalCount * 5 + 7) / 8
            val out = ByteArray(4 + normalBytes + exceptionalBytes)
            out[0] = (algorithm and 255).toByte()
            out[1] = (raw.size shr 16 and 255).toByte()
            out[2] = (raw.size shr 8 and 255).toByte()
            out[3] = (raw.size and 255).toByte()
            normal.copyInto(out, 4, normalBytes)
            exceptional.copyInto(out, 4 + normalBytes, exceptionalBytes)
            return out
        }

        private const val BASE64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

        private fun base64(data: ByteArray): String {
            val sb = StringBuilder((data.size * 4 + 2) / 3)
            var i = 0
            while (data.size - i >= 3) {
                val s0 = data[i].toInt() and 255
                val s1 = data[i + 1].toInt() and 255
                val s2 = data[i + 2].toInt() and 255
                sb.append(BASE64[s0 shr 2 and 63])
                sb.append(BASE64[(s0 shl 4 or (s1 shr 4)) and 63])
                sb.append(BASE64[(s1 shl 2 or (s2 shr 6)) and 63])
                sb.append(BASE64[s2 and 63])
                i += 3
            }
            when (data.size - i) {
                2 -> {
                    val s0 = data[i].toInt() and 255
                    val s1 = data[i + 1].toInt() and 255
                    sb.append(BASE64[s0 shr 2 and 63])
                    sb.append(BASE64[(s0 shl 4 or (s1 shr 4)) and 63])
                    sb.append(BASE64[s1 shl 2 and 63])
                }
                1 -> {
                    val s0 = data[i].toInt() and 255
                    sb.append(BASE64[s0 shr 2 and 63])
                    sb.append(BASE64[s0 shl 4 and 63])
                }
            }
            return sb.toString()
        }
    }

    /** Valori di pochi bit uno dopo l'altro, dal bit meno significativo (PackInt3Array / PackInt5Array). */
    private class BitWriter(capacityValues: Int) {
        private var bytes = ByteArray(max(16, capacityValues))
        private var bitPos = 0L

        fun write(value: Int, bits: Int) {
            for (b in 0 until bits) {
                val byteIndex = (bitPos ushr 3).toInt()
                if (byteIndex >= bytes.size) bytes = bytes.copyOf(bytes.size * 2)
                if (value shr b and 1 != 0) {
                    bytes[byteIndex] = (bytes[byteIndex].toInt() or (1 shl (bitPos and 7).toInt())).toByte()
                }
                bitPos++
            }
        }

        fun copyInto(out: ByteArray, at: Int, count: Int) {
            System.arraycopy(bytes, 0, out, at, count)
        }
    }
}

/**
 * Ricampionatore polifase di FFmpeg (resample2.c, coefficienti interi a 16 bit), con i
 * parametri di Chromaprint: filtro da 16 campioni, 256 fasi, niente interpolazione
 * lineare, taglio a 0,8 della banda d'uscita, finestra di Kaiser con beta 9.
 */
internal class Resampler(outRate: Int, inRate: Int) {
    private val filterLength: Int
    private val filterBank: ShortArray
    private val srcIncr = outRate
    private val dstIncr = inRate * PHASE_COUNT
    private var index: Int
    private var frac = 0

    /** Campioni d'ingresso usati dall'ultima chiamata a [resample]. */
    var consumed = 0
        private set

    init {
        val factor = min(outRate * CUTOFF / inRate, 1.0)
        filterLength = max(ceil(FILTER_SIZE / factor).toInt(), 1)
        filterBank = buildFilter(factor, filterLength)
        index = -PHASE_COUNT * ((filterLength - 1) / 2)
    }

    /** av_resample con update_ctx = 1. */
    fun resample(dst: ShortArray, src: ShortArray, srcSize: Int, dstSize: Int): Int {
        var index = index
        var frac = frac
        val dstIncrFrac = dstIncr % srcIncr
        val dstIncrInt = dstIncr / srcIncr
        val len = filterLength
        val bank = filterBank
        var dstIndex = 0
        while (dstIndex < dstSize) {
            val f = len * (index and PHASE_MASK)
            val sampleIndex = index shr PHASE_SHIFT
            var v = 0
            if (sampleIndex < 0) {
                for (i in 0 until len) v += src[abs(sampleIndex + i) % srcSize] * bank[f + i]
            } else if (sampleIndex + len > srcSize) {
                break
            } else {
                for (i in 0 until len) v += src[sampleIndex + i] * bank[f + i]
            }
            v = (v + (1 shl (FILTER_SHIFT - 1))) shr FILTER_SHIFT
            dst[dstIndex] = (if ((v + 32768).toUInt() > 65535u) (v shr 31) xor 32767 else v).toShort()
            frac += dstIncrFrac
            index += dstIncrInt
            if (frac >= srcIncr) {
                frac -= srcIncr
                index++
            }
            dstIndex++
        }
        consumed = max(index, 0) shr PHASE_SHIFT
        if (index >= 0) index = index and PHASE_MASK
        this.frac = frac
        this.index = index
        return dstIndex
    }

    private companion object {
        const val FILTER_SIZE = 16
        const val PHASE_SHIFT = 8
        const val PHASE_COUNT = 1 shl PHASE_SHIFT
        const val PHASE_MASK = PHASE_COUNT - 1
        const val CUTOFF = 0.8
        const val FILTER_SHIFT = 15
        const val WINDOW_TYPE = 9

        /** Funzione di Bessel modificata di ordine 0. */
        fun bessel(x0: Double): Double {
            var v = 1.0
            var lastv = 0.0
            var t = 1.0
            val x = x0 * x0 / 4
            var i = 1
            while (v != lastv) {
                lastv = v
                t *= x / (i * i)
                v += t
                i++
            }
            return v
        }

        /** build_filter: sinc con finestra di Kaiser, ogni fase normalizzata a somma 1 << 15. */
        fun buildFilter(factor0: Double, tapCount: Int): ShortArray {
            val factor = if (factor0 > 1.0) 1.0 else factor0
            val filter = ShortArray(tapCount * PHASE_COUNT)
            val tab = DoubleArray(tapCount)
            val center = (tapCount - 1) / 2
            val scale = 1 shl FILTER_SHIFT
            for (ph in 0 until PHASE_COUNT) {
                var norm = 0.0
                for (i in 0 until tapCount) {
                    val x = Math.PI * ((i - center).toDouble() - ph.toDouble() / PHASE_COUNT) * factor
                    var y = if (x == 0.0) 1.0 else StrictMath.sin(x) / x
                    val w = 2.0 * x / (factor * tapCount * Math.PI)
                    val ww = 1 - w * w
                    y *= bessel(WINDOW_TYPE * sqrt(if (ww > 0) ww else 0.0))
                    tab[i] = y
                    norm += y
                }
                for (i in 0 until tapCount) {
                    // lrintf: prima in float, poi all'intero più vicino (pari a metà strada).
                    val r = Math.rint((tab[i] * scale / norm).toFloat().toDouble())
                    filter[ph * tapCount + i] = r.coerceIn(-32768.0, 32767.0).toInt().toShort()
                }
            }
            return filter
        }
    }
}

/**
 * FFT reale di 4096 punti di KISS FFT in float (kiss_fftr): una FFT complessa di 2048
 * punti (radici 4, 4, 4, 4, 4, 2) e poi la separazione delle due metà.
 */
internal class KissFftr {
    private val twr = FloatArray(N)
    private val twi = FloatArray(N)
    private val superR = FloatArray(N / 2)
    private val superI = FloatArray(N / 2)
    private val tmp = FloatArray(2 * N)

    init {
        val pi = 3.141592653589793238462643383279502884197169399375105820974944
        for (i in 0 until N) {
            val phase = -2 * pi * i / N
            twr[i] = StrictMath.cos(phase).toFloat()
            twi[i] = StrictMath.sin(phase).toFloat()
        }
        for (i in 0 until N / 2) {
            val phase = -3.14159265358979323846264338327 * ((i + 1).toDouble() / N + .5)
            superR[i] = StrictMath.cos(phase).toFloat()
            superI[i] = StrictMath.sin(phase).toFloat()
        }
    }

    /** [input]: 4096 campioni reali; [out]: 2049 complessi interleaved (re, im). */
    fun transform(input: FloatArray, out: FloatArray) {
        work(tmp, 0, input, 0, 1, 0)
        val ncfft = N
        val tdcR = tmp[0]
        val tdcI = tmp[1]
        out[0] = tdcR + tdcI
        out[2 * ncfft] = tdcR - tdcI
        out[1] = 0f
        out[2 * ncfft + 1] = 0f
        for (k in 1..ncfft / 2) {
            val fpkR = tmp[2 * k]
            val fpkI = tmp[2 * k + 1]
            val fpnkR = tmp[2 * (ncfft - k)]
            val fpnkI = -tmp[2 * (ncfft - k) + 1]
            val f1kR = fpkR + fpnkR
            val f1kI = fpkI + fpnkI
            val f2kR = fpkR - fpnkR
            val f2kI = fpkI - fpnkI
            val sr = superR[k - 1]
            val si = superI[k - 1]
            val twR = f2kR * sr - f2kI * si
            val twI = f2kR * si + f2kI * sr
            out[2 * k] = (f1kR + twR) * 0.5f
            out[2 * k + 1] = (f1kI + twI) * 0.5f
            out[2 * (ncfft - k)] = (f1kR - twR) * 0.5f
            out[2 * (ncfft - k) + 1] = (twI - f1kI) * 0.5f
        }
    }

    /** kf_work: [o] e [f] sono indici di numeri complessi. */
    private fun work(out: FloatArray, o: Int, input: FloatArray, f: Int, fstride: Int, fi: Int) {
        val p = FACTORS[fi]
        val m = FACTORS[fi + 1]
        if (m == 1) {
            var src = f
            for (k in 0 until p) {
                out[2 * (o + k)] = input[2 * src]
                out[2 * (o + k) + 1] = input[2 * src + 1]
                src += fstride
            }
        } else {
            var src = f
            for (k in 0 until p) {
                work(out, o + k * m, input, src, fstride * p, fi + 2)
                src += fstride
            }
        }
        if (p == 4) bfly4(out, o, fstride, m) else bfly2(out, o, fstride, m)
    }

    private fun bfly2(d: FloatArray, o: Int, fstride: Int, m: Int) {
        var tw = 0
        for (k in 0 until m) {
            val a = 2 * (o + k)
            val b = 2 * (o + k + m)
            val tR = d[b] * twr[tw] - d[b + 1] * twi[tw]
            val tI = d[b] * twi[tw] + d[b + 1] * twr[tw]
            tw += fstride
            d[b] = d[a] - tR
            d[b + 1] = d[a + 1] - tI
            d[a] += tR
            d[a + 1] += tI
        }
    }

    private fun bfly4(d: FloatArray, o: Int, fstride: Int, m: Int) {
        var tw1 = 0
        var tw2 = 0
        var tw3 = 0
        for (k in 0 until m) {
            val a0 = 2 * (o + k)
            val a1 = a0 + 2 * m
            val a2 = a0 + 4 * m
            val a3 = a0 + 6 * m
            val s0R = d[a1] * twr[tw1] - d[a1 + 1] * twi[tw1]
            val s0I = d[a1] * twi[tw1] + d[a1 + 1] * twr[tw1]
            val s1R = d[a2] * twr[tw2] - d[a2 + 1] * twi[tw2]
            val s1I = d[a2] * twi[tw2] + d[a2 + 1] * twr[tw2]
            val s2R = d[a3] * twr[tw3] - d[a3 + 1] * twi[tw3]
            val s2I = d[a3] * twi[tw3] + d[a3 + 1] * twr[tw3]
            val s5R = d[a0] - s1R
            val s5I = d[a0 + 1] - s1I
            d[a0] += s1R
            d[a0 + 1] += s1I
            val s3R = s0R + s2R
            val s3I = s0I + s2I
            val s4R = s0R - s2R
            val s4I = s0I - s2I
            d[a2] = d[a0] - s3R
            d[a2 + 1] = d[a0 + 1] - s3I
            tw1 += fstride
            tw2 += fstride * 2
            tw3 += fstride * 3
            d[a0] += s3R
            d[a0 + 1] += s3I
            d[a1] = s5R + s4I
            d[a1 + 1] = s5I - s4R
            d[a3] = s5R - s4I
            d[a3 + 1] = s5I + s4R
        }
    }

    private companion object {
        /** Metà della finestra: la FFT complessa che kiss_fftr usa dentro. */
        const val N = 2048

        /** kf_factor(2048): coppie (radice, lunghezza rimasta). */
        val FACTORS = intArrayOf(4, 512, 4, 128, 4, 32, 4, 8, 4, 2, 2, 1)
    }
}
