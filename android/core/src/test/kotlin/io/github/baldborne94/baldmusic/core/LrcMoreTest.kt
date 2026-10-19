package io.github.baldborne94.baldmusic.core

import io.github.baldborne94.baldmusic.core.lyrics.LrcLibClient
import io.github.baldborne94.baldmusic.core.lyrics.LyricLine
import io.github.baldborne94.baldmusic.core.lyrics.Lyrics
import io.github.baldborne94.baldmusic.core.lyrics.parseLrc
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LrcMoreTest {

    @Test
    fun `tempi coi millesimi, coi centesimi dopo i due punti e senza frazione`() {
        assertEquals(
            listOf(LyricLine(1_000, "a"), LyricLine(2_340, "b"), LyricLine(3_007, "c"), LyricLine(605_000, "d")),
            parseLrc("[00:01]a\n[00:02:34]b\n[0:03.007]c\n[10:05.00]d"),
        )
    }

    @Test
    fun `una riga con piu tempi si ripete, anche con spazi tra i tempi`() {
        assertEquals(
            listOf(LyricLine(10_000, "Rit"), LyricLine(20_000, "Strofa"), LyricLine(30_000, "Rit"), LyricLine(50_000, "Rit")),
            parseLrc("[00:10.00] [00:30.00][00:50.00]  Rit  \n[00:20.00]Strofa"),
        )
    }

    @Test
    fun `una riga col tempo e senza testo resta, vuota, le righe vuote del file no`() {
        assertEquals(
            listOf(LyricLine(1_000, "Prima"), LyricLine(5_000, ""), LyricLine(9_000, "Dopo")),
            parseLrc("[00:01.00]Prima\n\n   \n[00:05.00]\n[00:09.00]Dopo\n"),
        )
    }

    @Test
    fun `i metadati non sono righe, anche la durata scritta come un tempo`() {
        val text = "[ti:Titolo]\n[ar:Artista]\n[al:Album]\n[by:Qualcuno]\n[length: 03:20]\n[re:LRC Maker]\n[00:01.00]Uno"
        assertEquals(listOf(LyricLine(1_000, "Uno")), parseLrc(text))
    }

    @Test
    fun `offset negativo ritarda, e si legge anche con gli spazi`() {
        assertEquals(listOf(LyricLine(1_250, "x")), parseLrc("[offset:-250]\n[00:01.00]x"))
        assertEquals(listOf(LyricLine(750, "x")), parseLrc("[offset: +250 ]\n[00:01.00]x"))
        // Un offset che porterebbe prima dell'inizio si ferma a zero.
        assertEquals(listOf(LyricLine(0, "x")), parseLrc("[offset:5000]\n[00:01.00]x"))
    }

    @Test
    fun `fine riga di Windows e righe con lo stesso tempo nell'ordine del file`() {
        assertEquals(
            listOf(LyricLine(1_000, "uno"), LyricLine(1_000, "due"), LyricLine(2_000, "tre")),
            parseLrc("[00:02.00]tre\r\n[00:01.00]uno\r\n[00:01.00]due\r\n"),
        )
    }

    @Test
    fun `un orario scritto nel testo non e un tempo della riga`() {
        assertEquals(listOf(LyricLine(1_000, "Ci vediamo alle [10:30] in piazza")), parseLrc("[00:01.00]Ci vediamo alle [10:30] in piazza"))
    }

    @Test
    fun `i tempi delle singole parole non finiscono nel testo`() {
        // LRC "avanzato": ogni parola col suo tempo tra parentesi angolari.
        assertEquals(listOf(LyricLine(1_000, "Ciao a tutti")), parseLrc("[00:01.00]<00:01.00>Ciao <00:01.50>a <00:01.80>tutti"))
    }

    @Test
    fun `testo vuoto o senza tempi`() {
        assertEquals(emptyList(), parseLrc(""))
        assertEquals(emptyList(), parseLrc("solo testo\nsenza tempi"))
        assertTrue(Lyrics().isEmpty)
        assertTrue(Lyrics(plain = "  ").isEmpty)
        assertEquals(-1, Lyrics(plain = "x").lineAt(5_000))
    }
}

class LrcLibMoreTest {
    private val server = MockWebServer()

    @BeforeTest fun start() = server.start()
    @AfterTest fun stop() = server.close()

    private val client by lazy { LrcLibClient(OkHttpClient(), server.url("/").toString().trimEnd('/')) }

    private fun reply(body: String, code: Int = 200) = server.enqueue(MockResponse.Builder().code(code).body(body).build())

    @Test
    fun `risultato esatto - una sola chiamata coi dati del brano`() = runTest {
        reply("""{"trackName":"Titolo","duration":200,"syncedLyrics":"[00:01.00]Ciao","plainLyrics":"Ciao"}""")
        val lyrics = client.find("Artista", "Titolo", "Album", 200)!!
        assertEquals(listOf(LyricLine(1_000, "Ciao")), lyrics.synced)
        assertEquals("Ciao", lyrics.plain)
        val req = server.takeRequest()
        assertEquals("/api/get", req.url.encodedPath)
        assertEquals("Artista", req.url.queryParameter("artist_name"))
        assertEquals("Titolo", req.url.queryParameter("track_name"))
        assertEquals("Album", req.url.queryParameter("album_name"))
        assertEquals("200", req.url.queryParameter("duration"))
        assertTrue(req.headers["User-Agent"]!!.startsWith("BaldMusic"))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `senza album ne durata non si mandano`() = runTest {
        reply("""{"plainLyrics":"x"}""")
        client.find("A", "T", " ", 0)
        val url = server.takeRequest().url
        assertNull(url.queryParameter("album_name"))
        assertNull(url.queryParameter("duration"))
    }

    @Test
    fun `brano strumentale`() = runTest {
        reply("""{"instrumental":true}""")
        assertEquals("♪ Brano strumentale ♪", client.find("A", "T", null, 100)?.plain)
    }

    @Test
    fun `un risultato esatto senza testo non ferma la ricerca libera`() = runTest {
        reply("""{"trackName":"T","syncedLyrics":"","plainLyrics":null}""")
        reply("""[{"duration":100,"plainLyrics":"trovato"}]""")
        assertEquals("trovato", client.find("A", "T", null, 100)?.plain)
    }

    @Test
    fun `nella ricerca il sincronizzato vince anche se il solo testo ha la durata giusta`() = runTest {
        reply("{}", 404)
        reply(
            """[
              {"duration":200,"plainLyrics":"solo testo"},
              {"duration":260,"syncedLyrics":"[00:01.00]lontano"},
              {"duration":203,"syncedLyrics":"[00:01.00]vicino"}
            ]""",
        )
        assertEquals("vicino", client.find("A", "T", null, 200)?.synced?.single()?.text)
    }

    @Test
    fun `nella ricerca un sincronizzato vuoto non conta come sincronizzato`() = runTest {
        reply("{}", 404)
        reply("""[{"duration":200,"syncedLyrics":"","plainLyrics":""},{"duration":200,"plainLyrics":"buono"}]""")
        assertEquals("buono", client.find("A", "T", null, 200)?.plain)
    }

    @Test
    fun `ricerca che non risponde JSON`() = runTest {
        reply("{}", 404)
        reply("<html>errore</html>")
        assertNull(client.find("A", "T", null, null))
    }

    @Test
    fun `senza rete nessun testo e nessun errore`() = runTest {
        val url = server.url("/").toString().trimEnd('/')
        server.close()
        assertNull(LrcLibClient(OkHttpClient(), url).find("A", "T", null, null))
    }
}
