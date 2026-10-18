package io.github.baldborne94.baldmusic.core

import io.github.baldborne94.baldmusic.core.subsonic.Credentials
import io.github.baldborne94.baldmusic.core.subsonic.SubsonicClient
import io.github.baldborne94.baldmusic.core.subsonic.SubsonicException
import io.github.baldborne94.baldmusic.core.subsonic.normalizeServerUrl
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SubsonicClientTest {
    private val server = MockWebServer()

    @BeforeTest fun start() = server.start()
    @AfterTest fun stop() = server.close()

    private fun client() = SubsonicClient(
        Credentials.create(server.url("/").toString(), "ale", "sesame", "c19b2d"),
        OkHttpClient(),
    )

    private fun reply(json: String) = server.enqueue(MockResponse.Builder().body(json).build())

    @Test
    fun `token come da esempio ufficiale Subsonic`() {
        assertEquals("26719a1196d2a940705a59634eb18eab", Credentials.create("x", "u", "sesame", "c19b2d").token)
    }

    @Test
    fun `indirizzo normalizzato`() {
        assertEquals("http://192.168.1.10:4533", normalizeServerUrl(" 192.168.1.10:4533/ "))
        assertEquals("https://pc.tail1.ts.net", normalizeServerUrl("https://pc.tail1.ts.net//"))
        // Porta dimenticata: per IP e localhost si aggiunge quella di Navidrome.
        assertEquals("http://192.168.1.191:4533", normalizeServerUrl("http://192.168.1.191"))
        assertEquals("http://192.168.1.191:4533", normalizeServerUrl("192.168.1.191/"))
        assertEquals("http://localhost:4533", normalizeServerUrl("localhost"))
        assertEquals("http://192.168.1.191:8080", normalizeServerUrl("http://192.168.1.191:8080"))
        assertEquals("https://pc.tail1.ts.net", normalizeServerUrl("https://pc.tail1.ts.net"))
    }

    @Test
    fun `legge un album e manda l'autenticazione`() = runTest {
        reply("""{"subsonic-response":{"status":"ok","album":{"id":"a1","name":"Disco","song":[{"id":"s1","title":"Uno","duration":200,"extra":1}]}}}""")
        val album = client().album("a1")
        assertEquals("Disco", album.name)
        assertEquals(200, album.song.single().duration)
        val req = server.takeRequest()
        assertEquals("/rest/getAlbum", req.url.encodedPath)
        assertEquals("ale", req.url.queryParameter("u"))
        assertEquals("26719a1196d2a940705a59634eb18eab", req.url.queryParameter("t"))
        assertEquals("json", req.url.queryParameter("f"))
        assertEquals("a1", req.url.queryParameter("id"))
    }

    @Test
    fun `liste vuote omesse dal server diventano vuote`() = runTest {
        reply("""{"subsonic-response":{"status":"ok","albumList2":{}}}""")
        assertEquals(emptyList(), client().albumList("newest"))
    }

    @Test
    fun `artisti appiattiti dagli indici`() = runTest {
        reply("""{"subsonic-response":{"status":"ok","artists":{"index":[{"name":"A","artist":[{"id":"1","name":"Abba"}]},{"name":"B","artist":[{"id":"2","name":"Bjork"}]}]}}}""")
        assertEquals(listOf("Abba", "Bjork"), client().artists().map { it.name })
    }

    @Test
    fun `errore del server diventa SubsonicException col codice`() = runTest {
        reply("""{"subsonic-response":{"status":"failed","error":{"code":40,"message":"Wrong username or password"}}}""")
        val e = assertFailsWith<SubsonicException> { client().ping() }
        assertEquals(40, e.code)
    }

    @Test
    fun `risposta non Subsonic`() = runTest {
        reply("<html>router</html>")
        assertFailsWith<SubsonicException> { client().ping() }
    }

    @Test
    fun `lo stream chiede sempre il file originale`() {
        val url = client().streamUrl("s1").toHttpUrl()
        assertEquals("raw", url.queryParameter("format"))
        assertEquals(null, url.queryParameter("maxBitRate"))
    }
}
