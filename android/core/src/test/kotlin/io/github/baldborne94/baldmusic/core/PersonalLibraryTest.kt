package io.github.baldborne94.baldmusic.core

import io.github.baldborne94.baldmusic.core.library.ContainerRef
import io.github.baldborne94.baldmusic.core.library.LibraryNode
import io.github.baldborne94.baldmusic.core.library.LibraryNode.Kind
import io.github.baldborne94.baldmusic.core.library.LibraryTree
import io.github.baldborne94.baldmusic.core.library.MediaId
import io.github.baldborne94.baldmusic.core.library.MusicSource
import io.github.baldborne94.baldmusic.core.library.PersonalSource
import io.github.baldborne94.baldmusic.core.library.TrackRef
import io.github.baldborne94.baldmusic.core.library.UserLibrary
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun ref(n: Int, parent: String? = "local/album/7") =
    TrackRef(if (parent != null) "local/track/$n|$parent" else "local/track/$n", "Brano $n", artwork = "local/7")

/** Il telefono: brani 1..9, tutti risolvibili. */
private class Phone : MusicSource {
    override val key = "local"
    override val title = "Telefono"
    override suspend fun sections() = emptyList<LibraryNode>()
    override suspend fun children(id: MediaId) = emptyList<LibraryNode>()
    override suspend fun track(id: MediaId) =
        LibraryNode(id.toString(), "Brano ${id.id}", Kind.TRACK, streamUri = "content://t/${id.id}")
    override suspend fun search(query: String) = emptyList<LibraryNode>()
}

class PersonalLibraryTest {

    @Test
    fun `mi piace ignora il contenitore e si toglie col secondo tocco`() {
        val lib = UserLibrary().toggleLike(ref(1))
        assertTrue(lib.isLiked("local/track/1|me/liked"))
        assertEquals("local/track/1", lib.liked.single().id)
        assertFalse(lib.toggleLike(ref(1, parent = "local/songs")).isLiked("local/track/1"))
    }

    @Test
    fun `gli ascoltati di recente vanno in cima senza doppioni`() {
        val a = ContainerRef("local/album/1", "A", kind = "ALBUM")
        val b = ContainerRef("local/album/2", "B", kind = "ALBUM")
        val lib = UserLibrary().recordPlay(ref(1), a, 1).recordPlay(ref(2), b, 2).recordPlay(ref(3), a, 3)
        assertEquals(listOf("local/album/1", "local/album/2"), lib.recent.map { it.id })
    }

    @Test
    fun `i piu ascoltati contano gli ascolti`() {
        var lib = UserLibrary()
        repeat(3) { lib = lib.recordPlay(ref(5), null, it.toLong()) }
        lib = lib.recordPlay(ref(6), null, 10)
        assertEquals(listOf("local/track/5", "local/track/6"), lib.topTracks().map { it.id })
    }

    @Test
    fun `una playlist non duplica i brani`() {
        val lib = UserLibrary().createPlaylist("p", "Viaggio", 0, listOf(ref(1)))
            .addToPlaylist("p", listOf(ref(1, "me/liked"), ref(2), ref(2)))
        assertEquals(listOf("local/track/1", "local/track/2"), lib.playlists.single().tracks.map { it.id })
        assertEquals(1, lib.removeFromPlaylist("p", "local/track/2|me/playlist/p").playlists.single().tracks.size)
    }

    @Test
    fun `cancellare una playlist la toglie anche dai recenti`() {
        val lib = UserLibrary().createPlaylist("p", "X", 0)
            .recordPlay(ref(1), ContainerRef("me/playlist/p", "X", kind = "PLAYLIST"), 1)
            .deletePlaylist("p")
        assertTrue(lib.recent.isEmpty())
    }

    @Test
    fun `ricerche recenti senza doppioni`() {
        val lib = UserLibrary().addSearch("dalla").addSearch("Muse").addSearch("Dalla").addSearch("x")
        assertEquals(listOf("Dalla", "Muse"), lib.searches)
    }

    @Test
    fun `toccare un brano che ti piace riproduce i preferiti da li, risolti sul telefono`() = runTest {
        var lib = UserLibrary()
        listOf(3, 2, 1).forEach { lib = lib.toggleLike(ref(it)) }
        val tree = LibraryTree({ listOf(PersonalSource { lib }, Phone()) })
        val second = tree.children("me/liked")[1]
        assertEquals("local/track/2|me/liked", second.mediaId)
        val (queue, start) = tree.queueFor(second.mediaId)
        assertEquals(listOf("content://t/1", "content://t/2", "content://t/3"), queue.map { it.streamUri })
        assertEquals(1, start)
    }

    @Test
    fun `la musica tua non resta in cache`() = runTest {
        var lib = UserLibrary()
        val tree = LibraryTree({ listOf(PersonalSource { lib }) })
        assertEquals(0, tree.children("me/liked").size)
        lib = lib.toggleLike(ref(1))
        assertEquals(1, tree.children("me/liked").size)
    }

    @Test
    fun `una playlist si trova per ID`() = runTest {
        val lib = UserLibrary().createPlaylist("p", "Viaggio", 0, listOf(ref(1)))
        val tree = LibraryTree({ listOf(PersonalSource { lib }) })
        val node = tree.node("me/playlist/p")
        assertEquals("Viaggio", node?.title)
        assertEquals("local/7", node?.artwork)
    }
}

class ListeningStatsTest {
    @Test
    fun `classifiche e minuti dagli ascolti`() {
        fun t(n: Int, artist: String, album: String) =
            io.github.baldborne94.baldmusic.core.library.TrackRef("local/track/$n", "Brano $n", artist, album, "local/$album", 180_000)
        var lib = UserLibrary()
        repeat(3) { lib = lib.recordPlay(t(1, "Queen", "A Night"), null, it.toLong()) }
        repeat(2) { lib = lib.recordPlay(t(2, "Muse", "Origin"), null, it.toLong()) }
        lib = lib.recordPlay(t(3, "Queen", "Jazz"), null, 9)
        val s = io.github.baldborne94.baldmusic.core.library.ListeningStats.from(lib)
        assertEquals(6, s.totalPlays)
        assertEquals(18, s.minutes)
        assertEquals(2, s.artists)
        assertEquals(listOf("Queen" to 4, "Muse" to 2), s.topArtists.map { it.name to it.plays })
        assertEquals("Brano 1", s.topTracks.first().name)
        assertEquals("A Night", s.topAlbums.first().name)
    }
}
