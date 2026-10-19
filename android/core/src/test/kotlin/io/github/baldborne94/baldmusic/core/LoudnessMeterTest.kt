package io.github.baldborne94.baldmusic.core

import io.github.baldborne94.baldmusic.core.library.FileStamp
import io.github.baldborne94.baldmusic.core.library.LocalTrack
import io.github.baldborne94.baldmusic.core.library.LoudnessIndex
import io.github.baldborne94.baldmusic.core.library.LoudnessMeter
import io.github.baldborne94.baldmusic.core.library.Measure
import io.github.baldborne94.baldmusic.core.library.ReplayGain
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LoudnessMeterTest {

    /** Un seno a 1 kHz in stereo, a [dbfs] di picco, come nei test EBU Tech 3341. */
    private fun sine(dbfs: Double, rate: Int, seconds: Int, chunk: Int = 4096): LoudnessMeter {
        val meter = LoudnessMeter(rate, 2)
        val amp = 10.0.pow(dbfs / 20)
        val total = rate * seconds
        var n = 0
        while (n < total) {
            val frames = minOf(chunk, total - n)
            val buf = ShortArray(frames * 2)
            for (i in 0 until frames) {
                val v = (amp * sin(2 * PI * 1000 * (n + i) / rate) * 32767).toInt().toShort()
                buf[2 * i] = v
                buf[2 * i + 1] = v
            }
            meter.addShorts(buf, frames)
            n += frames
        }
        return meter
    }

    @Test
    fun `seno a -23 dBFS misura -23 LUFS, a 48 e a 44,1 kHz`() {
        val m48 = sine(-23.0, 48_000, 20).result()!!
        assertEquals(-23f, m48.lufs, 0.1f)
        assertEquals(10.0.pow(-23.0 / 20).toFloat(), m48.peak, 0.001f)
        assertEquals(-23f, sine(-23.0, 44_100, 20).result()!!.lufs, 0.1f)
        assertEquals(-33f, sine(-33.0, 48_000, 20).result()!!.lufs, 0.1f)
    }

    @Test
    fun `il silenzio non si misura`() {
        val meter = LoudnessMeter(48_000, 2)
        meter.addShorts(ShortArray(48_000 * 2 * 3), 48_000 * 3)
        assertNull(meter.result())
    }

    @Test
    fun `il silenzio in mezzo non abbassa la misura`() {
        val loud = sine(-20.0, 48_000, 10)
        loud.addShorts(ShortArray(48_000 * 2 * 10), 48_000 * 10)
        assertEquals(-20f, loud.result()!!.lufs, 0.2f)
    }

    @Test
    fun `dalla misura al ReplayGain 2,0`() {
        val rg = LoudnessMeter.replayGain(Measure(lufs = -10f, peak = 0.98f))
        assertEquals(-8f, rg.trackGain!!, 0.0001f)
        assertEquals(0.98f, rg.trackPeak!!, 0.0001f)
        assertTrue(rg.measured)
    }

    private fun track(id: Long, album: String?, albumId: Long, rg: ReplayGain? = null) =
        LocalTrack(id, "T$id", "A", 1, album, albumId, 200_000, 0, 0, 0, 0, "Music/X", replayGain = rg)

    @Test
    fun `album dai brani misurati, i tag veri restano`() {
        val tracks = listOf(track(1, "Disco", 10), track(2, "Disco", 10), track(3, null, 11), track(4, "Altro", 12, ReplayGain(trackGain = -3f)))
        val measures = mapOf(1L to Measure(-8f, 1f), 2L to Measure(-14f, 0.5f), 3L to Measure(-12f, 0.7f), 4L to Measure(-1f, 1f))
        val out = LoudnessIndex.applyTo(tracks) { measures[it.id] }.associateBy { it.id }
        assertEquals(-10f, out.getValue(1).replayGain!!.trackGain!!, 0.001f)
        // Media di energia di -8 e -14 LUFS (stessa durata) ≈ -10,03: guadagno ≈ -7,97 dB.
        assertEquals(-7.97f, out.getValue(1).replayGain!!.albumGain!!, 0.02f)
        assertEquals(1f, out.getValue(2).replayGain!!.albumPeak!!, 0.001f)
        assertNull(out.getValue(3).replayGain!!.albumGain)
        assertEquals(ReplayGain(trackGain = -3f), out.getValue(4).replayGain)
    }

    @Test
    fun `indice - si misura una volta, si rimisura se il file cambia`() {
        val index = LoudnessIndex()
        val f = FileStamp(1, 100, 5000)
        assertEquals(listOf(f), index.pending(listOf(f)))
        index.put(f, Measure(-9f, 0.9f))
        val back = LoudnessIndex.fromJson(index.toJson())
        assertEquals(Measure(-9f, 0.9f), back.measure(f))
        assertTrue(back.pending(listOf(f)).isEmpty())
        assertEquals(1, back.pending(listOf(f.copy(modified = 200))).size)
        back.put(FileStamp(2, 1, 1), null)
        assertTrue(back.pending(listOf(FileStamp(2, 1, 1))).isEmpty())
    }
}
