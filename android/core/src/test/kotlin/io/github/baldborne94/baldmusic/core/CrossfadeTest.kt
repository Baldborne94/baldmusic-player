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
}
