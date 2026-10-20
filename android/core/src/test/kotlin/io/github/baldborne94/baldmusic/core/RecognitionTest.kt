package io.github.baldborne94.baldmusic.core

import io.github.baldborne94.baldmusic.core.acoustid.AcoustIdClient
import io.github.baldborne94.baldmusic.core.acoustid.AcoustIdResult
import io.github.baldborne94.baldmusic.core.acoustid.Candidate
import io.github.baldborne94.baldmusic.core.acoustid.Lookup
import io.github.baldborne94.baldmusic.core.acoustid.MbArtist
import io.github.baldborne94.baldmusic.core.acoustid.MbMedium
import io.github.baldborne94.baldmusic.core.acoustid.MbRecording
import io.github.baldborne94.baldmusic.core.acoustid.MbRelease
import io.github.baldborne94.baldmusic.core.acoustid.MbReleaseGroup
import io.github.baldborne94.baldmusic.core.acoustid.MbTrack
import io.github.baldborne94.baldmusic.core.acoustid.MbDate
import io.github.baldborne94.baldmusic.core.acoustid.Proposal
import io.github.baldborne94.baldmusic.core.acoustid.Recognition
import io.github.baldborne94.baldmusic.core.acoustid.RecognitionIndex
import io.github.baldborne94.baldmusic.core.library.FileStamp
import io.github.baldborne94.baldmusic.core.library.FileTags
import io.github.baldborne94.baldmusic.core.library.LocalCatalog
import io.github.baldborne94.baldmusic.core.library.LocalTrack
import io.github.baldborne94.baldmusic.core.library.ReplayGain
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecognitionTest {
    private val numb = javaClass.getResource("/acoustid/numb.json")!!.readText()

    private suspend fun results(): List<AcoustIdResult> = MockWebServer().use { server ->
        server.enqueue(MockResponse.Builder().body(numb).build())
        server.start()
        (AcoustIdClient(OkHttpClient(), "chiave", server.url("/v2/").toString()).lookup("AQAA", 186) as Lookup.Found).results
    }

    // --- AcoustID ---

    @Test
    fun `la richiesta va in POST con chiave, durata, impronta e i dati dei dischi`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse.Builder().body(numb).build())
            server.start()
            val found = AcoustIdClient(OkHttpClient(), "chiave", server.url("/v2/").toString()).lookup("AQAAimpronta", 186)
            assertIs<Lookup.Found>(found)
            assertEquals(listOf(0.97, 0.41), found.results.map { it.score })
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/v2/lookup", request.url.encodedPath)
            val form = request.body!!.utf8().split('&').associate { pair ->
                val (k, v) = pair.split('=', limit = 2)
                java.net.URLDecoder.decode(k, "UTF-8") to java.net.URLDecoder.decode(v, "UTF-8")
            }
            assertEquals("chiave", form["client"])
            assertEquals("186", form["duration"])
            assertEquals("AQAAimpronta", form["fingerprint"])
            assertEquals("recordings releasegroups releases tracks compress", form["meta"])
            assertTrue(request.headers["User-Agent"]!!.startsWith("BaldMusic"))
        }
    }

    @Test
    fun `chiave sbagliata e errori del servizio`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse.Builder().code(400).body("""{"status":"error","error":{"code":4,"message":"invalid API key"}}""").build())
            server.enqueue(MockResponse.Builder().body("""{"status":"error","error":{"code":3,"message":"invalid fingerprint"}}""").build())
            server.enqueue(MockResponse.Builder().code(503).body("<html>giù</html>").build())
            server.start()
            val client = AcoustIdClient(OkHttpClient(), "x", server.url("/v2/").toString())
            assertEquals(Lookup.Failed("invalid API key", badKey = true), client.lookup("f", 10))
            assertEquals(Lookup.Failed("invalid fingerprint", badKey = false), client.lookup("f", 10))
            // Il servizio non risponde: il file resta da riconoscere.
            assertFailsWith<IOException> { client.lookup("f", 10) }
        }
    }

    // --- Quale disco ---

    @Test
    fun `si sceglie l'album originale, non la raccolta, il live o il singolo`() = runTest {
        val p = Recognition.propose(results(), 185_000, "numb")!!
        assertEquals("rec-numb", p.recordingMbid)
        assertEquals(0.97, p.score)
        assertTrue(p.sure)
        assertEquals(listOf("rg-meteora", "rg-single", "rg-greatest", "rg-live"), p.candidates.map { it.releaseGroupMbid })
        val t = p.tags
        assertEquals("Numb", t.title)
        assertEquals("Linkin Park", t.artist)
        assertEquals("Meteora", t.album)
        assertEquals("Linkin Park", t.albumArtist)
        // Traccia e disco dell'edizione più vecchia (2003), non della deluxe del 2013.
        assertEquals("rel-meteora", t.albumMbid)
        assertEquals(13, t.track)
        assertEquals(1, t.disc)
        assertEquals(2003, t.year)
        assertEquals(2003, t.originalYear)
        assertEquals(listOf("album"), t.releaseTypes)
        assertEquals(listOf("f59c5520-5f46-4d2c-b2c4-822eabf53419"), t.artistMbids)
        assertEquals(false, t.compilation)
    }

    @Test
    fun `un brano dal vivo preferisce il disco dal vivo`() = runTest {
        val p = Recognition.propose(results(), 185_000, "Numb (Live)")!!
        assertEquals("rg-live", p.candidates[0].releaseGroupMbid)
        assertEquals("rg-meteora", p.candidates[1].releaseGroupMbid)
    }

    @Test
    fun `una raccolta di artisti vari e una compilation`() = runTest {
        val greatest = Recognition.propose(results(), 185_000, null)!!.candidates.first { it.releaseGroupMbid == "rg-greatest" }.tags
        assertEquals(true, greatest.compilation)
        assertEquals("Various Artists", greatest.albumArtist)
        assertEquals(2, greatest.disc)
        assertEquals(7, greatest.track)
        assertEquals(listOf("album", "compilation"), greatest.releaseTypes)
    }

    @Test
    fun `niente proposta se la somiglianza e bassa o la durata non torna`() = runTest {
        val all = results()
        assertNull(Recognition.propose(all.filter { it.score < 0.5 }, 185_000, null))
        // Il file dura 4 minuti: la registrazione di 3 non è lui.
        assertNull(Recognition.propose(all, 245_000, null))
        assertNull(Recognition.propose(emptyList(), 185_000, null))
    }

    @Test
    fun `tra due registrazioni con la stessa impronta vince quella col titolo del file`() {
        fun rec(id: String, title: String) = MbRecording(id, title, 200.0, listOf(MbArtist("a", "A")), listOf(MbReleaseGroup("rg-$id", "Disco $id", "Album")))
        val results = listOf(AcoustIdResult("x", 0.95, listOf(rec("1", "Hakuna Matata"), rec("2", "Il cerchio della vita"))))
        assertEquals("2", Recognition.propose(results, 200_000, "il cerchio della vita")!!.recordingMbid)
    }

    @Test
    fun `artisti con le loro congiunzioni`() {
        val r = MbRecording(
            "r", "Under Pressure", 248.0,
            listOf(MbArtist("q", "Queen", " & "), MbArtist("b", "David Bowie")),
            listOf(MbReleaseGroup("rg", "Hot Space", "Album", artists = listOf(MbArtist("q", "Queen")), releases = listOf(MbRelease("rel", date = MbDate(1982), mediums = listOf(MbMedium(1, tracks = listOf(MbTrack(position = 11)))))))),
        )
        val t = Recognition.propose(listOf(AcoustIdResult("x", 0.9, listOf(r))), 248_000, null)!!.tags
        assertEquals("Queen & David Bowie", t.artist)
        assertEquals(listOf("Queen", "David Bowie"), t.artists)
        assertEquals(listOf("q", "b"), t.artistMbids)
        assertEquals(11, t.track)
        assertEquals(1982, t.originalYear)
    }

    // --- Cartelle ---

    private fun cand(rg: String, year: Int, rank: Int = 0) = Candidate(rg, FileTags(title = rg, album = rg, originalYear = year, releaseGroupMbid = rg), rank)

    @Test
    fun `i file di una cartella che escono sullo stesso album ci vanno insieme`() {
        val proposals = mapOf(
            // Il primo è anche su una colonna sonora più vecchia: da solo sceglierebbe quella.
            1L to Proposal(0.95, "r1", listOf(cand("colonna-sonora", 1999), cand("album", 2000))),
            2L to Proposal(0.95, "r2", listOf(cand("album", 2000))),
            3L to Proposal(0.95, "r3", listOf(cand("album", 2000), cand("raccolta", 2010, rank = 100))),
            // Altra cartella: non conta.
            4L to Proposal(0.95, "r4", listOf(cand("colonna-sonora", 1999), cand("album", 2000))),
        )
        val out = Recognition.harmonize(proposals) { if (it == 4L) "Music/Altro" else "Music/Meteora" }
        assertEquals("album", out.getValue(1L).tags.album)
        assertEquals("album", out.getValue(2L).tags.album)
        assertEquals("album", out.getValue(3L).tags.album)
        assertEquals("colonna-sonora", out.getValue(4L).tags.album)
    }

    @Test
    fun `in una cartella-playlist ognuno resta sul suo disco, e mai una raccolta al posto dell'album`() {
        val proposals = mapOf(
            1L to Proposal(0.95, "r1", listOf(cand("re-leone", 1994), cand("disney-hits", 2005, rank = 100))),
            2L to Proposal(0.95, "r2", listOf(cand("aladdin", 1992), cand("disney-hits", 2005, rank = 100))),
        )
        val out = Recognition.harmonize(proposals) { "Music/Disney Italia" }
        assertEquals("re-leone", out.getValue(1L).tags.album)
        assertEquals("aladdin", out.getValue(2L).tags.album)
    }

    // --- Indice ---

    private val stamp = FileStamp(1, modified = 100, size = 5000)
    private val proposal = Proposal(0.95, "r", listOf(cand("a", 2000), cand("b", 1990)))

    @Test
    fun `una proposta vale solo dopo applica, e solo finche il file non cambia`() {
        val ix = RecognitionIndex()
        assertEquals(listOf(stamp), ix.pending(listOf(stamp)))
        ix.put(stamp, proposal)
        assertEquals(emptyList(), ix.pending(listOf(stamp)))
        assertNull(ix.tagsFor(stamp))
        ix.decide(listOf(1L), RecognitionIndex.Decision.ACCEPTED)
        assertEquals("a", ix.tagsFor(stamp)?.album)
        // Ripassato da Picard (o ricopiato): si riconosce da capo.
        val changed = stamp.copy(modified = 200)
        assertNull(ix.tagsFor(changed))
        assertEquals(listOf(changed), ix.pending(listOf(changed)))
        ix.decide(listOf(1L), RecognitionIndex.Decision.REJECTED)
        assertNull(ix.tagsFor(stamp))
    }

    @Test
    fun `scegliere un altro disco lo applica, riprova e file spariti`() {
        val ix = RecognitionIndex()
        ix.put(stamp, proposal)
        ix.put(FileStamp(2, 1, 1), null)
        assertEquals(1, ix.notFound())
        ix.choose(1, 1)
        assertEquals("b", ix.tagsFor(stamp)?.album)
        ix.forgetNotFound()
        assertEquals(0, ix.notFound())
        assertEquals(listOf(FileStamp(2, 1, 1)), ix.pending(listOf(FileStamp(2, 1, 1))))
        ix.forget(listOf(1L))
        assertEquals(listOf(stamp), ix.pending(listOf(stamp)))
        ix.put(stamp, proposal)
        assertTrue(ix.retain(emptySet()))
        assertEquals(emptyMap(), ix.proposals())
    }

    @Test
    fun `l'indice si salva e si rilegge, e cambiando versione si riparte`() {
        val ix = RecognitionIndex()
        ix.put(stamp, proposal)
        ix.decide(listOf(1L), RecognitionIndex.Decision.ACCEPTED)
        val back = RecognitionIndex.fromJson(ix.toJson())
        assertEquals("a", back.tagsFor(stamp)?.album)
        assertEquals(listOf(stamp), RecognitionIndex.fromJson(ix.toJson().replace("\"version\":1", "\"version\":0")).pending(listOf(stamp)))
        assertEquals(listOf(stamp), RecognitionIndex.fromJson("rotto").pending(listOf(stamp)))
    }

    @Test
    fun `l'accordo per cartella non cambia le proposte gia decise`() {
        val ix = RecognitionIndex()
        ix.put(FileStamp(1, 1, 1), Proposal(0.95, "r1", listOf(cand("colonna-sonora", 1999), cand("album", 2000))))
        ix.put(FileStamp(2, 1, 1), Proposal(0.95, "r2", listOf(cand("album", 2000))))
        ix.put(FileStamp(3, 1, 1), Proposal(0.95, "r3", listOf(cand("colonna-sonora", 1999), cand("album", 2000))))
        ix.decide(listOf(3L), RecognitionIndex.Decision.ACCEPTED)
        ix.harmonize { "Music/Meteora" }
        assertEquals("album", ix.proposals().getValue(1L).proposal!!.tags.album)
        assertEquals("colonna-sonora", ix.proposals().getValue(3L).proposal!!.tags.album)
    }

    // --- Nella libreria ---

    @Test
    fun `i brani riconosciuti formano l'album, restano ReplayGain e cartella`() {
        fun t(id: Long, title: String) = LocalTrack(id, title, "artista sbagliato", 1, title, id, 200_000, 0, 0, 1970, 0, "Music/Download",
            replayGain = ReplayGain(trackGain = -6f))
        val poor = listOf(t(1, "numb"), t(2, "faint"))
        val recognized = poor.mapIndexed { i, track ->
            Recognition.applyTo(track, FileTags(title = track.title.replaceFirstChar { it.uppercase() }, artist = "Linkin Park", album = "Meteora",
                albumArtist = "Linkin Park", track = i + 1, originalYear = 2003, year = 2003, releaseGroupMbid = "rg-meteora", albumMbid = "rel", releaseTypes = listOf("album")))
        }
        assertEquals(-6f, recognized[0].replayGain?.trackGain)
        assertEquals("Music/Download", recognized[0].folder)
        // Prima: due "album" da un brano; dopo: un album vero con due brani.
        assertEquals(LooseCount(2), LooseCount(LocalCatalog.albumFacts(poor).values.count { it.loose != null }))
        assertEquals(LooseCount(0), LooseCount(LocalCatalog.albumFacts(recognized).values.count { it.loose != null }))
        assertNotNull(recognized.firstOrNull { it.title == "Numb" && it.album == "Meteora" && it.shownYear == 2003 })
    }

    private data class LooseCount(val n: Int)
}
