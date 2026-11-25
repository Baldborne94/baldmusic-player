package io.github.baldborne94.baldmusic.core

import io.github.baldborne94.baldmusic.core.library.DownloadedSet
import io.github.baldborne94.baldmusic.core.library.ServerDownloads
import io.github.baldborne94.baldmusic.core.subsonic.Song
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Il tasto ⬇ accanto al play di una raccolta, un album o una playlist del server, come su Spotify. */
class DownloadButtonTest {
    private val playlist = "remote/playlist/p1"
    private fun set(vararg ids: String) =
        ServerDownloads(listOf(DownloadedSet(container = playlist, title = "Metal", songs = ids.map { Song(id = it, title = it) })))

    @Test
    fun `non scaricata finche non la chiedi`() {
        val s = ServerDownloads().status(playlist, present = emptySet())!!
        assertEquals(ServerDownloads.Status.State.NONE, s.state)
    }

    @Test
    fun `mentre scarica dice quanti brani ha gia`() {
        val s = set("a", "b", "c", "d").status(playlist, present = setOf("a", "c"))!!
        assertEquals(ServerDownloads.Status.State.DOWNLOADING, s.state)
        assertEquals(2, s.done)
        assertEquals(4, s.total)
        assertEquals(0.5f, s.progress)
    }

    @Test
    fun `scaricata quando ci sono tutti i file`() {
        assertEquals(ServerDownloads.Status.State.DONE, set("a", "b").status(playlist, present = setOf("a", "b", "z"))!!.state)
        // Una playlist vuota chiesta resta «scaricata»: non c'è niente da aspettare.
        assertEquals(ServerDownloads.Status.State.DONE, set().status(playlist, present = emptySet())!!.state)
    }

    @Test
    fun `niente tasto per quello che non si scarica`() {
        // Brani del telefono, artisti del server, un brano dentro una playlist.
        assertNull(ServerDownloads().status("me/playlist/x", emptySet()))
        assertNull(ServerDownloads().status("remote/artist/ar1", emptySet()))
        assertNull(ServerDownloads().status("remote/track/t1|remote/playlist/p1", emptySet()))
    }
}
