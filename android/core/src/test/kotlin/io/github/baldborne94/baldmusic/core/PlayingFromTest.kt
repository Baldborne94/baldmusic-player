package io.github.baldborne94.baldmusic.core

import io.github.baldborne94.baldmusic.core.library.PlayingFrom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlayingFromTest {
    @Test
    fun `il brano porta con se la playlist o raccolta da cui e partito`() {
        assertEquals("me/playlist/abc", PlayingFrom.container("remote/track/t1|me/playlist/abc", requested = null))
        assertEquals("remote/playlist/c1", PlayingFrom.container("remote/track/t1|remote/playlist/c1", requested = "me/liked"))
    }

    @Test
    fun `un brano di continua ad ascoltare riporta alla pagina da cui avevi cominciato`() {
        assertEquals("remote/playlist/c1", PlayingFrom.container("remote/track/t9|remote/random", requested = "remote/playlist/c1"))
        assertEquals("me/playlist/abc", PlayingFrom.container("local/track/5|local/radio/5", requested = "me/playlist/abc"))
        assertNull(PlayingFrom.container("local/track/5|local/radio/5", requested = null))
    }

    @Test
    fun `senza contenitore niente pagina`() {
        // Una puntata di un podcast non ha contenitore.
        assertNull(PlayingFrom.container("podcast/track/p~e", requested = null))
        assertNull(PlayingFrom.container(null, requested = null))
    }
}
