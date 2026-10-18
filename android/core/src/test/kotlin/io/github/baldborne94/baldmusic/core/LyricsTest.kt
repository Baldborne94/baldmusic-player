package io.github.baldborne94.baldmusic.core

import io.github.baldborne94.baldmusic.core.lyrics.LrcLibClient
import io.github.baldborne94.baldmusic.core.lyrics.LyricLine
import io.github.baldborne94.baldmusic.core.lyrics.Lyrics
import io.github.baldborne94.baldmusic.core.lyrics.parseLrc
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LyricsTest {
    @Test
    fun `legge tempi, righe multiple e offset`() {
        val lines = parseLrc(
            """
            [ar:Artista]
            [offset:+500]
            [00:12.30]Prima
            [00:05.5][00:20.00]Ritornello
            [01:02.123]Ultima
            testo senza tempo
            """.trimIndent(),
        )
        assertEquals(
            listOf(LyricLine(5000, "Ritornello"), LyricLine(11800, "Prima"), LyricLine(19500, "Ritornello"), LyricLine(61623, "Ultima")),
            lines,
        )
    }

    @Test
    fun `riga corrente in base alla posizione`() {
        val l = Lyrics(synced = listOf(LyricLine(1000, "a"), LyricLine(2000, "b"), LyricLine(3000, "c")))
        assertEquals(-1, l.lineAt(500))
        assertEquals(0, l.lineAt(1000))
        assertEquals(1, l.lineAt(2999))
        assertEquals(2, l.lineAt(99_000))
    }

    @Test
    fun `LRCLIB - ricerca esatta, poi ricerca libera`() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().code(404).body("{}").build())
            server.enqueue(
                MockResponse.Builder().body(
                    """[{"duration":300,"plainLyrics":"solo testo"},{"duration":201,"syncedLyrics":"[00:01.00]Ciao"}]""",
                ).build(),
            )
            val client = LrcLibClient(OkHttpClient(), server.url("/").toString().trimEnd('/'))
            val lyrics = client.find("Artista", "Titolo", "Album", 200)!!
            assertEquals(listOf(LyricLine(1000, "Ciao")), lyrics.synced)
            assertEquals("/api/get", server.takeRequest().url.encodedPath)
            assertEquals("/api/search", server.takeRequest().url.encodedPath)
        }
    }

    @Test
    fun `nessun risultato`() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().code(404).build())
            server.enqueue(MockResponse.Builder().body("[]").build())
            assertNull(LrcLibClient(OkHttpClient(), server.url("/").toString().trimEnd('/')).find("a", "b", null, null))
        }
    }
}
