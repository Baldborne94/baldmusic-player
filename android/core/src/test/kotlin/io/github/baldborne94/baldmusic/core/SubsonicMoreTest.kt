package io.github.baldborne94.baldmusic.core

import io.github.baldborne94.baldmusic.core.subsonic.Credentials
import io.github.baldborne94.baldmusic.core.subsonic.SubsonicClient
import io.github.baldborne94.baldmusic.core.subsonic.SubsonicException
import io.github.baldborne94.baldmusic.core.subsonic.md5Hex
import io.github.baldborne94.baldmusic.core.subsonic.normalizeServerUrl
import io.github.baldborne94.baldmusic.core.subsonic.randomSalt
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
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SubsonicMoreTest {
    private val server = MockWebServer()

    @BeforeTest fun start() = server.start()
    @AfterTest fun stop() = server.close()

    private fun client(url: String = server.url("/").toString()) =
        SubsonicClient(Credentials.create(url, "ale", "sesame", "c19b2d"), OkHttpClient())

    private fun ok(body: String) = server.enqueue(MockResponse.Builder().body("""{"subsonic-response":{"status":"ok"$body}}""").build())

    @Test
    fun `la password non si salva e non viaggia, solo sale e token`() {
        val creds = Credentials.create("pc.tail1.ts.net", "ale", "segreto-123", "ab12")
        assertEquals(md5Hex("segreto-123" + "ab12"), creds.token)
        assertFalse(creds.toString().contains("segreto-123"))
        val url = SubsonicClient(creds, OkHttpClient()).url("ping")
        assertNull(url.queryParameter("p"))
        assertFalse(url.toString().contains("segreto-123"))
        assertEquals("ab12", url.queryParameter("s"))
        assertEquals(creds.token, url.queryParameter("t"))
        assertEquals("baldmusic", url.queryParameter("c"))
        assertEquals("1.16.1", url.queryParameter("v"))
        // Anche lo stream e le copertine: stessa autenticazione, mai la password.
        val stream = SubsonicClient(creds, OkHttpClient()).streamUrl("s1").toHttpUrl()
        assertNull(stream.queryParameter("p"))
        assertEquals(creds.token, stream.queryParameter("t"))
    }

    @Test
    fun `sale casuale, esadecimale e diverso a ogni accesso`() {
        val a = randomSalt()
        val b = randomSalt()
        assertEquals(16, a.length)
        assertTrue(a.all { it in '0'..'9' || it in 'a'..'f' })
        assertNotEquals(a, b)
        val creds = Credentials.create("localhost", "ale", "sesame")
        assertEquals(md5Hex("sesame" + creds.salt), creds.token)
    }

    @Test
    fun `password con accenti e spazi in UTF-8, senza toglierli`() {
        assertEquals("4b3b4358de20e07bf97e641708081847", Credentials.create("x", "u", "pàssword", "ab12").token)
        assertEquals("a0a17e8d9fe0436ed31ee0003ab73a2c", Credentials.create("x", "u", " segreto ", "x1").token)
        // Il nome utente invece si pulisce dagli spazi digitati per sbaglio.
        assertEquals("ale", Credentials.create("x", "  ale ", "p", "s").user)
    }

    @Test
    fun `indirizzo con lo schema in maiuscolo o con un percorso`() {
        assertEquals("HTTP://LOCALHOST:4533", normalizeServerUrl("HTTP://LOCALHOST"))
        assertEquals("http://192.168.1.10:4533/navidrome", normalizeServerUrl("192.168.1.10/navidrome/"))
        assertEquals("https://pc.tail1.ts.net/music", normalizeServerUrl("https://pc.tail1.ts.net/music"))
    }

    @Test
    fun `Navidrome dietro un percorso del proxy`() = runTest {
        ok("")
        client(server.url("/navidrome").toString()).ping()
        assertEquals("/navidrome/rest/ping", server.takeRequest().url.encodedPath)
    }

    @Test
    fun `indirizzo non valido, errore chiaro`() = runTest {
        val e = assertFailsWith<SubsonicException> { SubsonicClient(Credentials("pc senza schema", "u", "s", "t"), OkHttpClient()).ping() }
        assertEquals("Indirizzo del server non valido", e.message)
    }

    @Test
    fun `errore del server senza messaggio`() = runTest {
        server.enqueue(MockResponse.Builder().body("""{"subsonic-response":{"status":"failed","error":{"code":70,"message":""}}}""").build())
        val e = assertFailsWith<SubsonicException> { client().album("x") }
        assertEquals("Errore del server", e.message)
        assertEquals(70, e.code)
    }

    @Test
    fun `errore HTTP del proxy`() = runTest {
        server.enqueue(MockResponse.Builder().code(502).body("Bad Gateway").build())
        val e = assertFailsWith<SubsonicException> { client().ping() }
        assertEquals("Il server ha risposto 502", e.message)
        assertNull(e.code)
    }

    @Test
    fun `server spento, si dice di controllare porta e Tailscale`() = runTest {
        val url = server.url("/").toString()
        server.close()
        val e = assertFailsWith<SubsonicException> { client(url).ping() }
        assertTrue(e.message!!.contains("Tailscale"))
    }

    @Test
    fun `JSON senza involucro Subsonic`() = runTest {
        server.enqueue(MockResponse.Builder().body("""{"status":"ok"}""").build())
        assertFailsWith<SubsonicException> { client().ping() }
    }

    @Test
    fun `campi nulli o mancanti prendono i valori predefiniti`() = runTest {
        ok(""","album":{"id":"a1","name":null,"song":null,"releaseTypes":null,"songCount":null}""")
        val album = client().album("a1")
        assertEquals("", album.name)
        assertEquals(emptyList(), album.song)
        assertEquals(emptyList(), album.releaseTypes)
        // Senza numero di brani non si sa: resta tra gli album.
        assertTrue(album.isReal)
        ok(""","song":{"id":"s1","title":null,"duration":null,"replayGain":null}""")
        val song = client().song("s1")
        assertEquals("", song.title)
        assertNull(song.duration)
        assertNull(song.replayGain)
    }

    @Test
    fun `elementi che il server non manda diventano errori o elenchi vuoti`() = runTest {
        ok("")
        assertEquals("Brano non trovato", assertFailsWith<SubsonicException> { client().song("x") }.message)
        ok("")
        assertEquals("Playlist non trovata", assertFailsWith<SubsonicException> { client().playlist("x") }.message)
        ok("")
        assertEquals("Artista non trovato", assertFailsWith<SubsonicException> { client().artist("x") }.message)
        ok("")
        assertEquals(emptyList(), client().playlists())
        ok("")
        assertEquals(emptyList(), client().starred().song)
        ok(""","searchResult3":{}""")
        val r = client().search("x")
        assertTrue(r.artist.isEmpty() && r.album.isEmpty() && r.song.isEmpty())
        ok(""","randomSongs":{}""")
        assertEquals(emptyList(), client().randomSongs())
        ok(""","artists":{"index":[{"name":"A"}]}""")
        assertEquals(emptyList(), client().artists())
    }

    @Test
    fun `OpenSubsonic - ReplayGain, titoli dei dischi, nome di ordinamento, data originale`() = runTest {
        ok(
            ""","album":{"id":"a1","name":"LOTR","year":2002,"originalReleaseDate":{"year":0},
               "discTitles":[{"disc":1,"title":"Fellowship"},{"disc":2}],
               "song":[{"id":"s1","replayGain":{"trackGain":-6.5,"trackPeak":0.95}}]}""",
        )
        val album = client().album("a1")
        // Un anno originale a 0 è "non lo so": si mostra quello dell'edizione.
        assertEquals(2002, album.shownYear)
        assertEquals(listOf("Fellowship", ""), album.discTitles.map { it.title })
        val rg = album.song.single().replayGain!!
        assertEquals(-6.5f, rg.trackGain)
        assertEquals(0.95f, rg.trackPeak)
        assertNull(rg.albumGain)
        ok(""","artist":{"id":"ar1","name":"The Beatles","sortName":"Beatles, The","album":[{"id":"a1","releaseTypes":["Album","Compilation"]}]}""")
        val artist = client().artist("ar1")
        assertEquals("Beatles, The", artist.sortName)
        assertEquals(listOf("Album", "Compilation"), artist.album.single().releaseTypes)
    }

    @Test
    fun `il tipo singolo vale anche scritto in maiuscolo`() = runTest {
        ok(""","albumList2":{"album":[{"id":"a1","songCount":3,"releaseTypes":["SINGLE"]},{"id":"a2","songCount":3,"releaseTypes":["album","single"]},{"id":"a3","songCount":0}]}""")
        assertEquals(listOf(false, false, false), client().albumList("newest").map { it.isReal })
    }

    @Test
    fun `richieste con i parametri giusti`() = runTest {
        ok(""","albumList2":{"album":[]}""")
        client().albumList("newest", 60, 120)
        server.takeRequest().url.let {
            assertEquals("/rest/getAlbumList2", it.encodedPath)
            assertEquals("newest", it.queryParameter("type"))
            assertEquals("60", it.queryParameter("size"))
            assertEquals("120", it.queryParameter("offset"))
        }
        ok(""","searchResult3":{}""")
        client().search("Guns N' Roses & co")
        server.takeRequest().url.let {
            assertEquals("Guns N' Roses & co", it.queryParameter("query"))
            assertEquals("10", it.queryParameter("artistCount"))
            assertEquals("20", it.queryParameter("albumCount"))
            assertEquals("50", it.queryParameter("songCount"))
        }
        ok("")
        client().scrobble("s1", submission = true)
        server.takeRequest().url.let {
            assertEquals("/rest/scrobble", it.encodedPath)
            assertEquals("true", it.queryParameter("submission"))
            assertTrue(it.queryParameter("time")!!.toLong() > 1_600_000_000_000L)
        }
        val cover = client().coverUrl("al-1", 300).toHttpUrl()
        assertEquals("/rest/getCoverArt", cover.encodedPath)
        assertEquals("al-1", cover.queryParameter("id"))
        assertEquals("300", cover.queryParameter("size"))
    }
}
