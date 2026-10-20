package io.github.baldborne94.baldmusic.core

import io.github.baldborne94.baldmusic.core.acoustid.Chromaprint
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * L'impronta deve essere identica bit per bit a quella della libreria C (Chromaprint 1.6.2,
 * FFT KissFFT, ricampionatore interno): i valori attesi in `resources/chromaprint` li ha
 * scritti la libreria C con gli stessi campioni. L'audio "synth" non è salvato: lo rifà
 * [synth], lo stesso generatore (solo interi) usato per la libreria C; il CRC32 nel
 * manifesto controlla che i campioni siano proprio quelli.
 */
class ChromaprintTest {

    private class Case(
        val name: String, val rate: Int, val channels: Int, val samples: ShortArray, val maxSeconds: Int,
    ) {
        /** Quello che il chiamante darebbe: al massimo [maxSeconds] secondi (0 = tutto). */
        val fed: Int get() = if (maxSeconds > 0) minOf(samples.size, maxSeconds * rate * channels) else samples.size
    }

    private fun resource(name: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/chromaprint/$name")) { "Manca $name" }.use { it.readBytes() }

    private fun text(name: String) = resource(name).decodeToString()

    private fun expectedRaw(name: String): IntArray =
        text("$name.raw.txt").lines().filter { it.isNotBlank() }.map { it.trim().toLong().toInt() }.toIntArray()

    private fun expectedFp(name: String): String = text("$name.fp.txt").trim()

    private fun pcm(name: String): ShortArray {
        val bytes = resource("$name.pcm")
        val out = ShortArray(bytes.size / 2)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(out)
        return out
    }

    private fun crc(samples: ShortArray): Long {
        val bytes = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        bytes.asShortBuffer().put(samples)
        return CRC32().apply { update(bytes.array()) }.value
    }

    private val synthCases: List<Case> by lazy {
        text("synth.txt").lines().filter { it.isNotBlank() }.map { line ->
            val p = line.trim().split(Regex("\\s+"))
            val samples = synth(p[1].toInt(), p[2].toInt(), p[3].toInt(), p[4].toLong().toInt(), p[5].toInt())
            assertEquals(p[7].toLong(), crc(samples), "Il generatore non rifà i campioni di ${p[0]}")
            Case(p[0], p[1].toInt(), p[2].toInt(), samples, p[6].toInt())
        }
    }

    private val fileCases: List<Case> by lazy {
        listOf(
            Case("chirp-11025-mono-20s", 11025, 1, pcm("chirp-11025-mono-20s"), 0),
            Case("tones-22050-mono-8s", 22050, 1, pcm("tones-22050-mono-8s"), 0),
            Case("noise-44100-stereo-4s", 44100, 2, pcm("noise-44100-stereo-4s"), 0),
        )
    }

    private fun fingerprint(c: Case, chunk: Int): IntArray {
        val cp = Chromaprint(c.rate, c.channels)
        var i = 0
        while (i < c.fed) {
            val n = minOf(chunk, c.fed - i)
            cp.consume(c.samples, i, n)
            i += n
        }
        return cp.finish()
    }

    private fun checkIdentical(c: Case) {
        val raw = fingerprint(c, 4096)
        val expected = expectedRaw(c.name)
        assertEquals(expected.size, raw.size, "Lunghezza dell'impronta di ${c.name}")
        assertContentEquals(expected, raw, "Impronta grezza di ${c.name}")
        assertEquals(expectedFp(c.name), Chromaprint.encode(raw), "Impronta compressa di ${c.name}")
    }

    @Test
    fun `audio sintetico a 11025 Hz mono identico alla libreria C`() {
        synthCases.filter { it.rate == 11025 && it.channels == 1 }.forEach(::checkIdentical)
    }

    @Test
    fun `audio sintetico ricampionato e stereo identico alla libreria C`() {
        val others = synthCases.filter { !(it.rate == 11025 && it.channels == 1) }
        assertTrue(others.any { it.rate == 44100 && it.channels == 2 })
        assertTrue(others.any { it.rate == 48000 && it.channels == 2 })
        assertTrue(others.any { it.rate == 22050 && it.channels == 1 })
        assertTrue(others.any { it.channels > 2 })
        others.forEach(::checkIdentical)
    }

    @Test
    fun `oltre 120 secondi il chiamante tronca e l'impronta resta identica`() {
        val long = synthCases.filter { it.maxSeconds == Chromaprint.MAX_SECONDS }
        assertEquals(2, long.size)
        for (c in long) {
            assertTrue(c.samples.size > c.fed)
            checkIdentical(c)
            // Circa 120 s di impronta: un intero ogni 1365 campioni a 11025 Hz.
            assertTrue(expectedRaw(c.name).size in 940..980)
        }
    }

    @Test
    fun `file creati da ffmpeg identici alla libreria C`() {
        fileCases.forEach(::checkIdentical)
    }

    @Test
    fun `a pezzi di ogni misura come tutto insieme`() {
        val cases = listOf(
            synthCases.first { it.name == "synth-44100-stereo-30s" },
            synthCases.first { it.name == "synth-44100-6ch-10s" },
            synthCases.first { it.name == "synth-8000-mono-20s" },
            fileCases.first { it.name == "chirp-11025-mono-20s" },
        )
        for (c in cases) {
            val whole = fingerprint(c, Int.MAX_VALUE)
            assertContentEquals(expectedRaw(c.name), whole, c.name)
            // Anche pezzi che spezzano i campioni dei canali a metà.
            for (chunk in intArrayOf(1, 7, 333, 4095, 32768, 100_001)) {
                if (chunk == 1 && c.samples.size > 1_000_000) continue
                assertContentEquals(whole, fingerprint(c, chunk), "${c.name} a pezzi da $chunk")
            }
        }
    }

    @Test
    fun `audio vuoto o cortissimo non va in errore`() {
        for (c in synthCases.filter { it.samples.size < 10_000 }) checkIdentical(c)
        val empty = Chromaprint(44100, 2).finish()
        assertEquals(0, empty.size)
        assertEquals(expectedFp("synth-11025-mono-0"), Chromaprint.encode(empty))
        val one = Chromaprint(48000, 2)
        one.consume(shortArrayOf(1000))
        assertEquals(0, one.finish().size)
        val tiny = Chromaprint(22050, 1)
        tiny.consume(ShortArray(3) { 30000 })
        assertEquals(0, tiny.finish().size)
        // finish due volte dà la stessa impronta.
        val c = synthCases.first { it.name == "synth-44100-stereo-3s" }
        val cp = Chromaprint(c.rate, c.channels)
        cp.consume(c.samples)
        assertContentEquals(cp.finish(), cp.finish())
    }

    @Test
    fun `frequenza o canali impossibili vengono rifiutati`() {
        assertFailsWith<IllegalArgumentException> { Chromaprint(44100, 0) }
        assertFailsWith<IllegalArgumentException> { Chromaprint(1000, 1) }
        assertFailsWith<IllegalArgumentException> { Chromaprint(44100, 2).consume(ShortArray(4), 2, 3) }
    }

    @Test
    fun `codifica di impronte note`() {
        // Una sola differenza grande (bit 32): esercita i valori "eccezionali" a 5 bit.
        val raw = intArrayOf(0, 1, Int.MIN_VALUE, -1, 0x12345678, 0x12345678)
        // Atteso da chromaprint_encode_fingerprint della libreria C.
        assertEquals("AQAABghyJEmSJEmSJEmSJEkSJGkmJVqiKAEAGA", Chromaprint.encode(raw))
        assertEquals("AQAAAA", Chromaprint.encode(IntArray(0)))
    }

    @Test
    fun `120 secondi a 44100 Hz stereo in pochi secondi`() {
        val c = synthCases.first { it.name == "synth-44100-stereo-130s" }
        repeat(2) { fingerprint(c, 8192) } // riscaldamento del JIT
        val start = System.nanoTime()
        val raw = fingerprint(c, 8192)
        val ms = (System.nanoTime() - start) / 1_000_000
        println("Chromaprint: 120 s a 44100 Hz stereo in $ms ms")
        assertContentEquals(expectedRaw(c.name), raw)
        assertTrue(ms < 5_000, "Troppo lento: $ms ms")
    }

    companion object {
        private val BASE = longArrayOf(261626, 277183, 293665, 311127, 329628, 349228, 369994, 391995, 415305, 440000, 466164, 493883)
        private val GAIN = longArrayOf(4, 3, 2, 1)

        /** Seno approssimato (Bhaskara) con soli interi: uguale in C e in Kotlin. */
        private fun sine(phase: Int): Long {
            val t = ((phase ushr 16) and 0x7FFF).toLong()
            val u = t * (32768 - t)
            val s = (16 * u * 32767) / (5L * 32768 * 32768 - 4 * u)
            return if (phase ushr 31 == 0) s else -s
        }

        private fun freqInc(note: Int, rate: Int): Int {
            val semitone = note % 12
            val octave = note / 12 - 1
            var f = BASE[semitone]
            f = if (octave >= 4) f shl (octave - 4) else f shr (4 - octave)
            return ((f shl 32) / (rate * 1000L)).toInt()
        }

        /**
         * Musica finta ma ricca di note: accordi casuali (fondamentale, terza, quinta e una
         * melodia) con armoniche e decadimento, più un po' di rumore; ogni canale li mescola
         * in proporzioni diverse. I primi [silence] fotogrammi sono silenzio.
         */
        fun synth(rate: Int, channels: Int, frames: Int, seed: Int, silence: Int): ShortArray {
            var st = seed
            fun next(): Int {
                st = st * 1664525 + 1013904223
                return st
            }
            val out = ShortArray(frames * channels)
            val chordLen = rate * 2 / 5
            val inc = IntArray(4)
            val ph = IntArray(4)
            val notes = IntArray(4)
            val vs = LongArray(4)
            var o = 0
            for (n in 0 until frames) {
                val pos = n % chordLen
                if (pos == 0) {
                    val root = 40 + (next() ushr 8) % 24
                    notes[0] = root
                    notes[1] = root + 3 + (next() ushr 8) % 2
                    notes[2] = root + 7
                    notes[3] = 60 + (next() ushr 8) % 25
                    for (v in 0 until 4) inc[v] = freqInc(notes[v], rate)
                }
                if (pos == chordLen / 2) {
                    notes[3] = 60 + (next() ushr 8) % 25
                    inc[3] = freqInc(notes[3], rate)
                }
                val env = 32768 - pos.toLong() * 24576 / chordLen
                for (v in 0 until 4) {
                    val p = ph[v]
                    vs[v] = sine(p) + sine(p * 2) / 2 + sine(p * 3) / 3
                    ph[v] = p + inc[v]
                }
                val noise = (next() ushr 20) - 2048
                for (c in 0 until channels) {
                    var acc = 0L
                    for (v in 0 until 4) acc += vs[v] * GAIN[(v + c) % 4]
                    val value = ((acc * env) shr 15) / 24 + noise / 4
                    out[o++] = if (n < silence) 0 else value.coerceIn(-32768, 32767).toShort()
                }
            }
            return out
        }
    }
}
