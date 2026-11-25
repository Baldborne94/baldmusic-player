package io.github.baldborne94.baldmusic.core

import io.github.baldborne94.baldmusic.core.nas.FileBrowserClient
import io.github.baldborne94.baldmusic.core.nas.NasException
import io.github.baldborne94.baldmusic.core.nas.NasTrash
import io.github.baldborne94.baldmusic.core.nas.NasTrasher
import io.github.baldborne94.baldmusic.core.subsonic.Song
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import java.net.URLDecoder
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Un File Browser finto con i file in memoria, che legge le richieste come quello vero
 * (http/resource.go): token in `X-Auth`, cartelle con POST e «/» in fondo, spostamenti con
 * PATCH `action=rename`, destinazione decodificata due volte.
 */
private class FakeFileBrowser : Dispatcher() {
    val files = sortedSetOf<String>()
    val dirs = sortedSetOf("/")
    val requests = mutableListOf<String>()

    @Synchronized
    override fun dispatch(request: RecordedRequest): MockResponse {
        val url = request.url
        val path = url.encodedPath
        requests += "${request.method} ${url.encodedPath}${url.encodedQuery?.let { "?$it" } ?: ""}"
        if (path == "/api/login") {
            val body = request.body?.utf8().orEmpty()
            return if ("\"username\":\"ale\"" in body && "\"password\":\"segreta\"" in body) ok("aaa.bbb.ccc") else code(403)
        }
        if (request.headers["X-Auth"] != "aaa.bbb.ccc") return code(401)
        val target = "/" + url.pathSegments.drop(2).filter { it.isNotEmpty() }.joinToString("/")
        return when (request.method) {
            "POST" -> {
                var d = ""
                for (p in target.split('/').filter { it.isNotEmpty() }) { d += "/$p"; dirs += d }
                ok("")
            }
            "PATCH" -> {
                // Come il server: Query().Get e poi url.QueryUnescape.
                val dst = URLDecoder.decode(url.queryParameter("destination")!!, "UTF-8")
                when {
                    target !in files -> code(404)
                    NasTrash.parentOf(dst) !in dirs -> code(500)
                    else -> {
                        val final = if (dst in files) dst.replace(".mp3", "(1).mp3") else dst
                        files -= target
                        files += final
                        ok("")
                    }
                }
            }
            else -> code(405)
        }
    }

    private fun ok(body: String) = MockResponse.Builder().body(body).build()
    private fun code(c: Int) = MockResponse.Builder().code(c).build()
}

class NasTrashTest {
    @Test
    fun `il percorso di Navidrome diventa quello dentro la cartella della musica`() {
        assertEquals("Disney/01 - Hakuna Matata.mp3", NasTrash.relative("/music/Disney/01 - Hakuna Matata.mp3"))
        assertEquals("/Musica/Disney/01 - Hakuna Matata.mp3", NasTrash.source("Disney/01 - Hakuna Matata.mp3"))
        assertEquals("/Cestino BaldMusic/Disney/01 - Hakuna Matata.mp3", NasTrash.destination("Disney/01 - Hakuna Matata.mp3"))
        assertEquals("/Cestino BaldMusic/Disney", NasTrash.parentOf("/Cestino BaldMusic/Disney/01 - Hakuna Matata.mp3"))
        // Percorso finto (manca «Report Real Path»), fuori dalla musica, o che prova a uscirne: niente.
        assertNull(NasTrash.relative("Avril/Let Go/Uno.mp3"))
        assertNull(NasTrash.relative("/altro/Disney/a.mp3"))
        assertNull(NasTrash.relative("/music/../etc/passwd"))
        assertNull(NasTrash.relative("/music"))
        assertNull(NasTrash.relative(null))
        // Un'altra cartella in Navidrome si imposta.
        assertEquals("Rock/a.mp3", NasTrash.relative("/data/musica/Rock/a.mp3", NasTrash.Config(navidromeRoot = "/data/musica/")))
    }

    @Test
    fun `la destinazione si manda codificata una volta, perche File Browser la decodifica due`() {
        assertEquals("/Cestino%20BaldMusic/AC%2BDC/100%25%20%C3%A8.mp3", NasTrash.encodedOnce("/Cestino BaldMusic/AC+DC/100% è.mp3"))
    }

    private val server = MockWebServer()
    private val fb = FakeFileBrowser()

    @BeforeTest fun start() {
        server.dispatcher = fb
        server.start()
    }

    @AfterTest fun stop() = server.close()

    private val client by lazy { FileBrowserClient(server.url("/").toString(), OkHttpClient()) }

    @Test
    fun `i file vanno nel cestino del NAS con lo stesso percorso, anche coi nomi strani`() = runBlocking {
        fb.files += listOf("/Musica/Disney/01 - Hakuna Matata.mp3", "/Musica/Rock/AC+DC - 100% è.mp3")
        val token = client.login("ale", "segreta")
        val result = NasTrasher(client).trash(
            token,
            listOf(
                Song("d1", "Hakuna Matata", path = "/music/Disney/01 - Hakuna Matata.mp3"),
                Song("r1", "100%", path = "/music/Rock/AC+DC - 100% è.mp3"),
                Song("x", "Senza percorso", path = "Artista/Album/x.mp3"),
                Song("gone", "Sparito", path = "/music/Disney/sparito.mp3"),
            ),
        )
        assertEquals(listOf("d1", "r1"), result.moved)
        assertEquals(setOf("x", "gone"), result.failed.keys)
        assertEquals(sortedSetOf("/Cestino BaldMusic/Disney/01 - Hakuna Matata.mp3", "/Cestino BaldMusic/Rock/AC+DC - 100% è.mp3"), fb.files)
        // Nessuna cancellazione: solo cartelle e spostamenti.
        assertTrue(fb.requests.none { it.startsWith("DELETE") })
    }

    @Test
    fun `un file con lo stesso nome gia nel cestino non si sovrascrive`() = runBlocking {
        fb.files += listOf("/Musica/Disney/a.mp3", "/Cestino BaldMusic/Disney/a.mp3")
        val token = client.login("ale", "segreta")
        NasTrasher(client).trash(token, listOf(Song("a", "A", path = "/music/Disney/a.mp3")))
        assertEquals(sortedSetOf("/Cestino BaldMusic/Disney/a(1).mp3", "/Cestino BaldMusic/Disney/a.mp3"), fb.files)
    }

    @Test
    fun `password sbagliata o NAS spento - un errore chiaro`() = runBlocking {
        assertEquals(403, assertFailsWith<NasException> { client.login("ale", "sbagliata") }.code)
        server.close()
        val e = assertFailsWith<NasException> { client.login("ale", "segreta") }
        assertNull(e.code)
        assertTrue("Tailscale" in e.message!!)
    }
}
