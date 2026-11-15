package io.github.baldborne94.baldmusic.core

import io.github.baldborne94.baldmusic.core.library.LibraryNode
import io.github.baldborne94.baldmusic.core.library.LibraryNode.Kind
import io.github.baldborne94.baldmusic.core.library.LibraryTree
import io.github.baldborne94.baldmusic.core.library.MediaId
import io.github.baldborne94.baldmusic.core.library.MusicSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Una sorgente che trova un risultato dopo [takesMs]. */
private class SlowSource(override val key: String, private val takesMs: Long, override val network: Boolean) : MusicSource {
    override val title = key
    override suspend fun sections() = emptyList<LibraryNode>()
    override suspend fun children(id: MediaId) = emptyList<LibraryNode>()
    override suspend fun track(id: MediaId): LibraryNode? = null
    override suspend fun search(query: String): List<LibraryNode> {
        delay(takesMs)
        return listOf(LibraryNode("$key/artist/1", "$query da $key", Kind.ARTIST))
    }
}

class SearchWaitTest {
    @Test
    fun `il server che non risponde non blocca la ricerca a voce`() = runBlocking {
        // In macchina col PC spento il server risponde dopo il timeout: prima la ricerca aspettava lui.
        val tree = LibraryTree({ listOf(SlowSource("local", 0, network = false), SlowSource("remote", 20_000, network = true)) })
        val started = System.nanoTime()
        val found = tree.search("linkin park", waitMs = 300)
        val tookMs = (System.nanoTime() - started) / 1_000_000
        assertEquals(listOf("local/artist/1"), found.map { it.mediaId })
        assertTrue(tookMs < 5_000, "ha aspettato $tookMs ms")
    }

    @Test
    fun `le sorgenti del telefono si aspettano anche se lente, il server veloce entra`() = runBlocking {
        val tree = LibraryTree({ listOf(SlowSource("local", 600, network = false), SlowSource("remote", 10, network = true)) })
        val found = tree.search("vasco", waitMs = 300)
        assertEquals(listOf("local/artist/1", "remote/artist/1"), found.map { it.mediaId })
    }
}
