package io.github.baldborne94.baldmusic.core

import io.github.baldborne94.baldmusic.core.library.Crossfade
import io.github.baldborne94.baldmusic.core.library.FileStamp
import io.github.baldborne94.baldmusic.core.library.LocalTrack
import io.github.baldborne94.baldmusic.core.library.Loudness
import io.github.baldborne94.baldmusic.core.library.LoudnessIndex
import io.github.baldborne94.baldmusic.core.library.LoudnessMeter
import io.github.baldborne94.baldmusic.core.library.Measure
import io.github.baldborne94.baldmusic.core.library.ReplayGain
import io.github.baldborne94.baldmusic.core.library.ReplayGainMode
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VolumeRulesTest {

    private fun near(expected: Float, actual: Float) = assertEquals(expected, actual, 0.001f)

    // ReplayGain

    @Test
    fun `in modalita Album conta il picco dell'album, in Brano quello del brano`() {
        val rg = ReplayGain(trackGain = 0f, albumGain = 0f, trackPeak = 2f, albumPeak = 1.25f)
        near(0.8f, Loudness.volume(rg, ReplayGainMode.ALBUM))
        near(0.5f, Loudness.volume(rg, ReplayGainMode.TRACK))
    }

    @Test
    fun `in modalita Brano senza guadagno del brano si usa quello dell'album`() {
        near(0.5012f, Loudness.volume(ReplayGain(albumGain = -6f), ReplayGainMode.TRACK))
    }

    @Test
    fun `un picco nullo o negativo non conta`() {
        near(0.5012f, Loudness.volume(ReplayGain(trackGain = -6f, trackPeak = 0f), ReplayGainMode.TRACK))
        near(0.5012f, Loudness.volume(ReplayGain(trackGain = -6f, trackPeak = -1f), ReplayGainMode.TRACK))
    }

    @Test
    fun `il volume misurato dall'app vale come quello dei tag`() {
        near(0.5012f, Loudness.volume(ReplayGain(trackGain = -6f, measured = true), ReplayGainMode.ALBUM))
    }

    @Test
    fun `un guadagno non numerico suona com'e invece di ammutolire`() {
        near(1f, Loudness.volume(ReplayGain(trackGain = Float.NaN), ReplayGainMode.TRACK))
        near(1f, Loudness.volume(ReplayGain(albumGain = Float.NEGATIVE_INFINITY), ReplayGainMode.ALBUM))
        near(1f, Loudness.volume(ReplayGain(trackGain = Float.POSITIVE_INFINITY), ReplayGainMode.TRACK))
        near(0.5012f, Loudness.volume(ReplayGain(trackGain = -6f, trackPeak = Float.NaN), ReplayGainMode.TRACK))
    }

    @Test
    fun `il volume resta sempre tra 0 e 1`() {
        listOf(-60f, -20f, -1f, 0f, 3f, 20f).forEach { g ->
            ReplayGainMode.entries.forEach { m ->
                val v = Loudness.volume(ReplayGain(trackGain = g, albumGain = g, trackPeak = 0.5f, albumPeak = 0.5f), m)
                assertTrue(v in 0f..1f, "guadagno $g, modalita $m: $v")
            }
        }
    }

    // Dissolvenza

    @Test
    fun `scelte della dissolvenza, predefinita 6 secondi`() {
        assertEquals(listOf(0, 3, 6, 9, 12), Crossfade.CHOICES)
        assertEquals(6, Crossfade.DEFAULT_SECONDS)
        assertTrue(Crossfade.DEFAULT_SECONDS in Crossfade.CHOICES)
    }

    @Test
    fun `brano abbastanza lungo - il doppio della dissolvenza piu 5 secondi`() {
        assertTrue(Crossfade.applies(12, 29_000, sameAlbum = false))
        assertFalse(Crossfade.applies(12, 28_999, sameAlbum = false))
        assertTrue(Crossfade.applies(3, 11_000, sameAlbum = false))
        // Durata sconosciuta (Media3 la dà negativa): niente dissolvenza.
        assertFalse(Crossfade.applies(6, Long.MIN_VALUE + 1, sameAlbum = false))
        assertFalse(Crossfade.applies(6, 0, sameAlbum = false))
    }

    @Test
    fun `mai nello stesso album, a qualunque durata`() {
        Crossfade.CHOICES.forEach { s -> assertFalse(Crossfade.applies(s, 3_600_000, sameAlbum = true)) }
    }

    @Test
    fun `la sfumatura fuori dai limiti si ferma agli estremi e scende senza salti`() {
        assertEquals(Crossfade.gains(0f), Crossfade.gains(-1f))
        assertEquals(Crossfade.gains(1f), Crossfade.gains(2f))
        var last = Crossfade.gains(0f)
        for (i in 1..20) {
            val g = Crossfade.gains(i / 20f)
            assertTrue(g.first <= last.first && g.second >= last.second)
            last = g
        }
    }

    // Misura del volume

    /** Un seno a 1 kHz a [dbfs] di picco per [seconds], aggiunto al misuratore. */
    private fun LoudnessMeter.sine(dbfs: Double, seconds: Double, startFrame: Long = 0, chunk: Int = 4096): Long {
        val amp = 10.0.pow(dbfs / 20)
        val total = (sampleRate * seconds).toLong()
        var n = 0L
        while (n < total) {
            val frames = minOf(chunk.toLong(), total - n).toInt()
            val buf = FloatArray(frames * channels)
            for (i in 0 until frames) {
                val v = (amp * sin(2 * PI * 1000 * (startFrame + n + i) / sampleRate)).toFloat()
                for (c in 0 until channels) buf[i * channels + c] = v
            }
            addFloats(buf, frames)
            n += frames
        }
        return startFrame + total
    }

    @Test
    fun `EBU Tech 3341 caso 3 - i pezzi piu piani di 10 LU non abbassano la misura`() {
        val m = LoudnessMeter(48_000, 2)
        var at = m.sine(-36.0, 10.0)
        at = m.sine(-23.0, 60.0, at)
        m.sine(-36.0, 10.0, at)
        assertEquals(-23f, m.result()!!.lufs, 0.1f)
    }

    @Test
    fun `EBU Tech 3341 caso 4 - soglia assoluta e relativa insieme`() {
        val m = LoudnessMeter(48_000, 2)
        var at = m.sine(-72.0, 10.0)
        at = m.sine(-36.0, 10.0, at)
        at = m.sine(-23.0, 60.0, at)
        at = m.sine(-36.0, 10.0, at)
        m.sine(-72.0, 10.0, at)
        assertEquals(-23f, m.result()!!.lufs, 0.1f)
    }

    @Test
    fun `stessa misura a pezzi di qualunque grandezza e da 16 bit o in virgola mobile`() {
        val a = LoudnessMeter(44_100, 2).apply { sine(-20.0, 5.0, chunk = 4096) }.result()!!
        val b = LoudnessMeter(44_100, 2).apply { sine(-20.0, 5.0, chunk = 333) }.result()!!
        assertEquals(a.lufs, b.lufs, 0.0001f)
        val shorts = LoudnessMeter(44_100, 2)
        val amp = 10.0.pow(-20.0 / 20)
        val frames = 44_100 * 5
        val buf = ShortArray(frames * 2) { i -> (amp * sin(2 * PI * 1000 * (i / 2) / 44_100) * 32768).toInt().coerceIn(-32768, 32767).toShort() }
        shorts.addShorts(buf, frames)
        assertEquals(a.lufs, shorts.result()!!.lufs, 0.01f)
    }

    @Test
    fun `un file mono misura come lo stesso suono in stereo`() {
        // Un mono si ascolta uguale sui due altoparlanti: ReplayGain 2.0 lo misura come "doppio mono".
        val mono = LoudnessMeter(48_000, 1).apply { sine(-23.0, 20.0) }.result()!!
        val stereo = LoudnessMeter(48_000, 2).apply { sine(-23.0, 20.0) }.result()!!
        assertEquals(stereo.lufs, mono.lufs, 0.05f)
        assertEquals(-23f, mono.lufs, 0.1f)
    }

    @Test
    fun `meno di 400 ms non si misurano`() {
        assertNull(LoudnessMeter(48_000, 2).apply { sine(-20.0, 0.35) }.result())
        assertNotNull(LoudnessMeter(48_000, 2).apply { sine(-20.0, 0.45) }.result())
    }

    @Test
    fun `il picco sopra lo zero dei file in virgola mobile resta`() {
        val m = LoudnessMeter(48_000, 2).apply { sine(3.0, 2.0) }.result()!!
        assertEquals(10.0.pow(3.0 / 20).toFloat(), m.peak, 0.01f)
        // E poi il volume non lo fa distorcere.
        val rg = LoudnessMeter.replayGain(m)
        assertTrue(Loudness.volume(rg, ReplayGainMode.TRACK) <= 1f / m.peak + 0.0001f)
    }

    // Indice delle misure

    private fun track(id: Long, album: String?, albumId: Long, durationMs: Long = 200_000, rg: ReplayGain? = null) =
        LocalTrack(id, "T$id", "A", 1, album, albumId, durationMs, 0, 0, 0, 0, "Music/X", replayGain = rg)

    @Test
    fun `l'album pesa i brani per durata`() {
        val tracks = listOf(track(1, "Disco", 10, durationMs = 300_000), track(2, "Disco", 10, durationMs = 100_000))
        val m = mapOf(1L to Measure(-10f, 0.5f), 2L to Measure(-20f, 0.9f))
        val out = LoudnessIndex.applyTo(tracks) { m[it.id] }
        // Energia media pesata: (3·10^-1 + 1·10^-2) / 4 → circa −11,1 LUFS → guadagno circa −6,9 dB.
        val expected = -18f - (10 * kotlin.math.log10((3 * 10.0.pow(-1.0) + 10.0.pow(-2.0)) / 4)).toFloat()
        out.forEach { assertEquals(expected, it.replayGain!!.albumGain!!, 0.001f) }
        out.forEach { assertEquals(0.9f, it.replayGain!!.albumPeak!!, 0.0001f) }
        assertTrue(out.all { it.replayGain!!.measured })
    }

    @Test
    fun `un solo brano misurato del disco non fa l'album, e i brani senza misura restano senza`() {
        val tracks = listOf(track(1, "Disco", 10), track(2, "Disco", 10, rg = ReplayGain(trackGain = -2f)), track(3, "Disco", 10))
        val out = LoudnessIndex.applyTo(tracks) { if (it.id == 1L) Measure(-12f, 1f) else null }.associateBy { it.id }
        assertEquals(-6f, out.getValue(1).replayGain!!.trackGain!!, 0.001f)
        assertNull(out.getValue(1).replayGain!!.albumGain)
        assertEquals(ReplayGain(trackGain = -2f), out.getValue(2).replayGain)
        assertNull(out.getValue(3).replayGain)
    }

    @Test
    fun `senza nessuna misura i brani restano gli stessi`() {
        val tracks = listOf(track(1, "Disco", 10))
        assertTrue(LoudnessIndex.applyTo(tracks) { null } === tracks)
    }

    @Test
    fun `indice delle misure - file tolti, versione vecchia, file rovinato`() {
        val index = LoudnessIndex()
        index.put(FileStamp(1, 1, 1), Measure(-9f, 0.9f))
        index.put(FileStamp(2, 1, 1), Measure(-10f, 0.8f))
        assertTrue(index.retain(setOf(1)))
        assertFalse(index.retain(setOf(1)))
        assertEquals(listOf(FileStamp(2, 1, 1)), index.pending(listOf(FileStamp(1, 1, 1), FileStamp(2, 1, 1))))
        // Cambia la dimensione: si rimisura anche a parità di data.
        assertNull(index.measure(FileStamp(1, 1, 2)))
        assertTrue(index.toJson().contains("\"version\":${LoudnessIndex.VERSION}"))
        // Le misure della versione 1 (mono 3 dB più piani) si rifanno.
        assertEquals(2, LoudnessIndex.VERSION)
        assertEquals(1, LoudnessIndex.fromJson("""{"version":1,"entries":{"1":{"modified":1,"size":1,"lufs":-9}}}""").pending(listOf(FileStamp(1, 1, 1))).size)
        assertEquals(1, LoudnessIndex.fromJson("""{"entries":{"1":{"modified":1,"size":1,"lufs":-9}}}""").pending(listOf(FileStamp(1, 1, 1))).size)
        assertEquals(1, LoudnessIndex.fromJson("{rotto").pending(listOf(FileStamp(1, 1, 1))).size)
        // Un file che non si è potuto misurare non si riprova, ma non ha misura.
        val failed = LoudnessIndex.fromJson(LoudnessIndex().apply { put(FileStamp(3, 1, 1), null) }.toJson())
        assertNull(failed.measure(FileStamp(3, 1, 1)))
        assertTrue(failed.pending(listOf(FileStamp(3, 1, 1))).isEmpty())
    }

    @Test
    fun `misura salvata senza picco vale picco 1`() {
        val index = LoudnessIndex.fromJson("""{"version":${LoudnessIndex.VERSION},"entries":{"5":{"modified":1,"size":1,"lufs":-11.5}}}""")
        assertEquals(Measure(-11.5f, 1f), index.measure(FileStamp(5, 1, 1)))
    }
}
