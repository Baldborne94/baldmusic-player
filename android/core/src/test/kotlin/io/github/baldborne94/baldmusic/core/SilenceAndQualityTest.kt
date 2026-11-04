package io.github.baldborne94.baldmusic.core

import io.github.baldborne94.baldmusic.core.library.Crossfade
import io.github.baldborne94.baldmusic.core.library.LibraryCleanup
import io.github.baldborne94.baldmusic.core.library.LocalTrack
import io.github.baldborne94.baldmusic.core.library.LoudnessIndex
import io.github.baldborne94.baldmusic.core.library.LoudnessMeter
import io.github.baldborne94.baldmusic.core.library.Measure
import io.github.baldborne94.baldmusic.core.library.ReplayGain
import io.github.baldborne94.baldmusic.core.library.Trim
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals

class SilenceAndQualityTest {

    @Test
    fun `la misura dice dove comincia e finisce il suono`() {
        val rate = 48_000
        val meter = LoudnessMeter(rate, 2)
        // 2 s di silenzio, 3 s di musica, 4 s di silenzio (con un fruscio a -70 dBFS che non conta).
        meter.addShorts(ShortArray(rate * 2 * 2), rate * 2)
        val music = ShortArray(rate * 3 * 2) { i -> (0.3 * sin(2 * PI * 1000 * (i / 2) / rate) * 32767).toInt().toShort() }
        meter.addShorts(music, rate * 3)
        meter.addShorts(ShortArray(rate * 4 * 2) { if (it % 2 == 0) 10 else -10 }, rate * 4)
        val m = meter.result()!!
        assertEquals(2_000, m.startMs)
        assertEquals(5_000L, m.endMs!!)
        assertEquals(Trim(2_000, 5_000), m.trim)
    }

    @Test
    fun `la dissolvenza parte dalla fine vera del suono, il brano dopo dal suo inizio`() {
        // 4 minuti, ma il suono finisce a 3:52: la dissolvenza comincia da lì, non nel silenzio.
        assertEquals(232_000, Crossfade.audibleEnd(240_000, Trim(0, 232_000)))
        // Meno di un secondo di silenzio è il respiro normale tra due brani: si lascia.
        assertEquals(240_000, Crossfade.audibleEnd(240_000, Trim(0, 239_500)))
        assertEquals(240_000, Crossfade.audibleEnd(240_000, null))
        // Il brano che entra salta il silenzio iniziale, ma non un'introduzione lunghissima e piano.
        assertEquals(2_500, Crossfade.audibleStart(Trim(2_500, 200_000)))
        assertEquals(0, Crossfade.audibleStart(Trim(400, 200_000)))
        assertEquals(0, Crossfade.audibleStart(Trim(40_000, 200_000)))
        assertEquals(0, Crossfade.audibleStart(null))
    }

    @Test
    fun `i silenzi valgono anche per i brani col ReplayGain nei tag, il volume solo per gli altri`() {
        fun t(id: Long, rg: ReplayGain?) = LocalTrack(id, "Brano $id", "A", 1, null, id, 200_000, 0, 0, 0, 0, "Music/X", replayGain = rg)
        val tagged = t(1, ReplayGain(trackGain = -7f))
        val plain = t(2, null)
        val out = LoudnessIndex.applyTo(listOf(tagged, plain)) { Measure(-12f, 0.9f, 1_500, 190_000) }
        assertEquals(Trim(1_500, 190_000), out[0].trim)
        assertEquals(-7f, out[0].replayGain!!.trackGain)
        assertEquals(Trim(1_500, 190_000), out[1].trim)
        assertEquals(true, out[1].replayGain!!.measured)
    }

    private var next = 1L
    private fun copy(folder: String, file: String, kbps: Int = 0, size: Long = 0, mbid: String? = null) =
        LocalTrack(next++, "Break Stuff", "Limp Bizkit", 1, "Significant Other", 1, 166_000, 0, 0, 0, 0, folder, fileName = file, bitrate = kbps * 1000, sizeBytes = size, albumMbid = mbid)

    @Test
    fun `tra due doppioni si tiene quella che suona meglio, poi quella coi tag migliori`() {
        // Prima vinceva la copia coi tag di MusicBrainz anche a 128 kbps.
        val low = copy("Music/Metal", "Break Stuff.mp3", kbps = 128, mbid = "x")
        val high = copy("Music/Nu Metal", "Break Stuff.mp3", kbps = 320)
        assertEquals(high.id, LibraryCleanup.duplicates(listOf(low, high)).single().keep.id)
        // Senza perdita batte qualsiasi mp3.
        val flac = copy("Music/Lossless", "Break Stuff.flac", size = 30_000_000)
        assertEquals(flac.id, LibraryCleanup.duplicates(listOf(high, flac)).single().keep.id)
        // Stessa fascia (320 e 317 kbps): decidono i tag, come prima.
        val tagged = copy("Music/Picard", "Break Stuff.mp3", kbps = 317, mbid = "y")
        assertEquals(tagged.id, LibraryCleanup.duplicates(listOf(high, tagged)).single().keep.id)
        // Senza bitrate di Android si stima dalla dimensione: 166 s a 320 kbps ≈ 6,6 MB.
        assertEquals(320, LibraryCleanup.quality(copy("Music/A", "a.mp3", size = 6_640_000)).kbps)
        assertEquals("MP3 · 320 kbps", LibraryCleanup.quality(high).label)
        assertEquals("FLAC · senza perdita", LibraryCleanup.quality(flac).label)
    }

    @Test
    fun `nella pulizia si sceglie a mano quale copia tenere`() {
        val a = copy("Music/A", "a.mp3", kbps = 320)
        val b = copy("Music/B", "b.mp3", kbps = 128)
        val group = LibraryCleanup.duplicates(listOf(a, b)).single()
        val chosen = group.keeping(b.id)
        assertEquals(b.id, chosen.keep.id)
        assertEquals(listOf(a.id), chosen.others.map { it.id })
        assertEquals(group, group.keeping(999))
    }
}
