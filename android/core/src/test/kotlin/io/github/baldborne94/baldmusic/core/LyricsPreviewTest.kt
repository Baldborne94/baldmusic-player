package io.github.baldborne94.baldmusic.core

import io.github.baldborne94.baldmusic.core.lyrics.LyricLine
import io.github.baldborne94.baldmusic.core.lyrics.Lyrics
import io.github.baldborne94.baldmusic.core.lyrics.LyricsPreview
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LyricsPreviewTest {
    private val song = Lyrics(
        synced = listOf(LyricLine(10_000, "Prima riga"), LyricLine(15_000, ""), LyricLine(20_000, "Seconda riga"), LyricLine(25_000, "Ultima")),
    )

    @Test
    fun `sotto il titolo la riga di adesso e la prossima`() {
        assertEquals(LyricsPreview(null, "Prima riga", synced = true), song.preview(0))
        assertEquals(LyricsPreview("Prima riga", "♪", synced = true), song.preview(12_000))
        assertEquals(LyricsPreview("♪", "Seconda riga", synced = true), song.preview(16_000))
        assertEquals(LyricsPreview("Ultima", null, synced = true), song.preview(99_000))
    }

    @Test
    fun `senza tempi le prime due righe, ferme`() {
        val plain = Lyrics(plain = "\n  Ciao mondo \n\nseconda\nterza")
        assertEquals(LyricsPreview("Ciao mondo", "seconda", synced = false), plain.preview(0))
        assertEquals(plain.preview(0), plain.preview(60_000))
        assertEquals(LyricsPreview("♪ Brano strumentale ♪", null, synced = false), Lyrics(plain = "♪ Brano strumentale ♪").preview(0))
    }

    @Test
    fun `senza testo niente`() {
        assertNull(Lyrics().preview(0))
        assertNull(Lyrics(plain = "  \n ").preview(0))
    }
}
