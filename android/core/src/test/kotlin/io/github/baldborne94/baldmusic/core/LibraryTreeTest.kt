package io.github.baldborne94.baldmusic.core

import io.github.baldborne94.baldmusic.core.library.LibraryNode
import io.github.baldborne94.baldmusic.core.library.LibraryNode.Kind
import io.github.baldborne94.baldmusic.core.library.LibraryTree
import io.github.baldborne94.baldmusic.core.library.MediaId
import io.github.baldborne94.baldmusic.core.library.MusicSource
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

private class FakeSource : MusicSource {
    override val key = "local"
    override val title = "Telefono"
    var calls = 0
    private val album = MediaId("local", "album", "7")
    private fun track(n: Int, parent: MediaId?) =
        LibraryNode(MediaId.track("local", "$n", parent).toString(), "Brano $n", Kind.TRACK, streamUri = "content://t/$n")

    override suspend fun sections() = listOf(LibraryNode("local/albums", "Album", Kind.SECTION))
    override suspend fun children(id: MediaId): List<LibraryNode> {
        calls++
        return when (id.kind) {
            "albums" -> listOf(LibraryNode(album.toString(), "Disco", Kind.ALBUM))
            "artist" -> listOf(LibraryNode(album.toString(), "Disco", Kind.ALBUM), LibraryNode("local/artistsingles/1", "Singoli", Kind.SECTION))
            "artistsingles" -> listOf(track(9, MediaId("local", "artistsingles", "1")))
            "album" -> (1..4).map { track(it, album) }
            else -> emptyList()
        }
    }
    override suspend fun track(id: MediaId) = id.id?.toInt()?.let { track(it, id.parent) }
    override suspend fun search(query: String) = emptyList<LibraryNode>()
}

class LibraryTreeTest {
    private val src = FakeSource()
    private val tree = LibraryTree({ listOf(src) })

    @Test
    fun `la radice elenca le sorgenti`() = runTest {
        assertEquals(listOf("local"), tree.children(MediaId.ROOT).map { it.mediaId })
    }

    @Test
    fun `scegliere un brano accoda tutto l'album da quel brano`() = runTest {
        val third = tree.children("local/album/7")[2]
        val (queue, start) = tree.queueFor(third.mediaId)
        assertEquals(4, queue.size)
        assertEquals(2, start)
    }

    @Test
    fun `brano senza contenitore si riproduce da solo`() = runTest {
        val (queue, start) = tree.queueFor(MediaId.track("local", "3", null).toString())
        assertEquals(listOf("Brano 3"), queue.map { it.title })
        assertEquals(0, start)
    }

    @Test
    fun `un artista si riproduce scendendo negli album e nei singoli`() = runTest {
        val (queue, _) = tree.queueFor("local/artist/1")
        assertEquals(5, queue.size)
    }

    @Test
    fun `le pagine successive usano la cache`() = runTest {
        tree.children("local/album/7")
        tree.children("local/album/7")
        assertEquals(1, src.calls)
        tree.invalidate()
        tree.children("local/album/7")
        assertEquals(2, src.calls)
    }
}
