package io.github.baldborne94.baldmusic.core

import io.github.baldborne94.baldmusic.core.library.Crossfade
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CrossfadeTest {

    @Test
    fun `sfuma tra brani di album diversi`() {
        assertTrue(Crossfade.applies(6, 200_000, sameAlbum = false))
    }

    @Test
    fun `mai dentro lo stesso album, mai se spenta, mai su brani corti`() {
        assertFalse(Crossfade.applies(6, 200_000, sameAlbum = true))
        assertFalse(Crossfade.applies(0, 200_000, sameAlbum = false))
        // Puntata dopo puntata (anche di podcast diversi): niente dissolvenza.
        assertFalse(Crossfade.applies(6, 3_600_000, sameAlbum = false, episode = true))
        assertFalse(Crossfade.applies(6, 15_000, sameAlbum = false))
    }

    @Test
    fun `dal vecchio al nuovo a potenza costante`() {
        val (o0, i0) = Crossfade.gains(0f)
        assertEquals(1f, o0, 0.0001f)
        assertEquals(0f, i0, 0.0001f)
        val (o1, i1) = Crossfade.gains(1f)
        assertEquals(0f, o1, 0.0001f)
        assertEquals(1f, i1, 0.0001f)
        val (om, im) = Crossfade.gains(0.5f)
        assertEquals(1f, om * om + im * im, 0.0001f)
    }

    @Test
    fun `stesso album resta attaccato solo con la traccia dopo, non in casuale ne in una raccolta`() {
        // Disco ascoltato in ordine: 3 → 4, ultima del disco 1 → prima del disco 2.
        assertTrue(Crossfade.inSequence(true, 1, 3, 1, 4))
        assertTrue(Crossfade.inSequence(true, 1, 12, 2, 1))
        assertTrue(Crossfade.inSequence(true, null, 3, null, 4))
        // Stesso album in un altro ordine: si sfuma.
        assertFalse(Crossfade.inSequence(true, 1, 7, 1, 2))
        assertFalse(Crossfade.inSequence(true, 1, 4, 1, 4))
        // Senza numeri di traccia (file scaricati: Android mette come album il nome della cartella): si sfuma.
        assertFalse(Crossfade.inSequence(true, null, null, null, null))
        assertFalse(Crossfade.inSequence(true, 0, 0, 0, 0))
        // Album diversi: si sfuma sempre.
        assertFalse(Crossfade.inSequence(false, 1, 3, 1, 4))
    }
}
