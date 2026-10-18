package io.github.baldborne94.baldmusic.core

import io.github.baldborne94.baldmusic.core.library.MediaId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MediaIdTest {
    @Test
    fun `andata e ritorno con contenitore`() {
        val album = MediaId("remote", "album", "al/1 è|x")
        val track = MediaId.track("remote", "tr|2", album)
        val parsed = MediaId.parse(track.toString())!!
        assertEquals(track, parsed)
        assertTrue(parsed.isTrack)
        assertEquals("al/1 è|x", parsed.parent?.id)
    }

    @Test
    fun `il contenitore di un brano non si porta dietro altri contenitori`() {
        val nested = MediaId.track("local", "1", MediaId.track("local", "2", MediaId("local", "album", "3")))
        assertNull(nested.parent?.parent)
    }

    @Test
    fun `radice e sezioni`() {
        assertNull(MediaId.parse("root"))
        assertEquals(MediaId("local"), MediaId.parse("local"))
        assertEquals(MediaId("local", "albums"), MediaId.parse("local/albums"))
        assertEquals("local/albums", MediaId("local", "albums").toString())
    }
}
