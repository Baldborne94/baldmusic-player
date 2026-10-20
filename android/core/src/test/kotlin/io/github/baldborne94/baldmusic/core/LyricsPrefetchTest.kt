package io.github.baldborne94.baldmusic.core

import io.github.baldborne94.baldmusic.core.library.PlayCount
import io.github.baldborne94.baldmusic.core.library.TrackRef
import io.github.baldborne94.baldmusic.core.library.UserLibrary
import io.github.baldborne94.baldmusic.core.library.UserPlaylist
import io.github.baldborne94.baldmusic.core.lyrics.LyricsPrefetch
import kotlin.test.Test
import kotlin.test.assertEquals

class LyricsPrefetchTest {
    private fun t(n: Int) = TrackRef("local/track/$n", "T$n")
    private fun ids(list: List<TrackRef>) = list.map { it.id.substringAfterLast('/').toInt() }

    @Test
    fun `prima i piu ascoltati, poi i preferiti, poi raccolte e playlist a turno, poi il telefono`() {
        val lib = UserLibrary(
            plays = mapOf(t(1).id to PlayCount(t(1), 2, 10), t(2).id to PlayCount(t(2), 9, 5), t(3).id to PlayCount(t(3), 2, 20)),
            liked = listOf(t(4), t(2)),
            playlists = listOf(
                UserPlaylist("p", "Mia", listOf(t(30), t(31))),
                UserPlaylist("a", "Disney", listOf(t(10), t(11), t(12)), folder = "Music/Disney"),
                UserPlaylist("b", "Film", listOf(t(20), t(21)), folder = "Music/Film"),
            ),
        )
        val phone = listOf(t(10), t(40), t(41))
        // 2 (9 ascolti), poi a pari ascolti il più recente (3 prima di 1); i preferiti senza doppioni;
        // un brano per lista a giro, prima le raccolte; il resto del telefono.
        assertEquals(listOf(2, 3, 1, 4, 10, 20, 30, 11, 21, 31, 12, 40, 41), ids(LyricsPrefetch.order(lib, phone)))
    }

    @Test
    fun `lo stesso brano scelto da contenitori diversi conta una volta e il giro ha un limite`() {
        val lib = UserLibrary(liked = listOf(TrackRef("local/track/1|me/liked", "T1")))
        val phone = listOf(t(1)) + (2..10).map(::t)
        val order = LyricsPrefetch.order(lib, phone, limit = 4)
        // L'ID senza contenitore: è quello con cui il brano del telefono si ritrova.
        assertEquals(listOf("local/track/1", "local/track/2", "local/track/3", "local/track/4"), order.map { it.id })
    }

    @Test
    fun `anche i brani del server`() {
        val remote = TrackRef("remote/track/abc", "Numb", artist = "Linkin Park")
        val lib = UserLibrary(liked = listOf(remote))
        assertEquals(listOf("remote/track/abc"), LyricsPrefetch.order(lib, emptyList()).map { it.id })
    }
}
