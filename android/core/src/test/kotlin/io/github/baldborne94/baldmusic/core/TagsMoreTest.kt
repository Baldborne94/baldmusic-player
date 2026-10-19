package io.github.baldborne94.baldmusic.core

import io.github.baldborne94.baldmusic.core.library.FileStamp
import io.github.baldborne94.baldmusic.core.library.FileTags
import io.github.baldborne94.baldmusic.core.library.RawTag
import io.github.baldborne94.baldmusic.core.library.ReplayGain
import io.github.baldborne94.baldmusic.core.library.TagIndex
import io.github.baldborne94.baldmusic.core.update.AppUpdate
import io.github.baldborne94.baldmusic.core.update.ReleaseInfo
import io.github.baldborne94.baldmusic.core.update.UpdateClient
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TagsMoreTest {
    private fun tags(vararg pairs: Pair<String, String>) = FileTags.from(pairs.map { (k, v) -> RawTag(k, listOf(v)) })

    @Test
    fun `ReplayGain con la virgola, col segno piu e senza dB`() {
        assertEquals(-6.5f, tags("REPLAYGAIN_TRACK_GAIN" to "-6,50 dB").replayGain?.trackGain)
        assertEquals(1.25f, tags("REPLAYGAIN_TRACK_GAIN" to "+1.25 DB").replayGain?.trackGain)
        assertEquals(-3f, tags("REPLAYGAIN_ALBUM_GAIN" to "-3").replayGain?.albumGain)
        assertEquals(0.5f, tags("REPLAYGAIN_TRACK_GAIN" to "-1 dB", "REPLAYGAIN_TRACK_PEAK" to "0,5").replayGain?.trackPeak)
    }

    @Test
    fun `un ReplayGain illeggibile e come se mancasse`() {
        assertNull(tags("REPLAYGAIN_TRACK_GAIN" to "NaN dB").replayGain)
        assertNull(tags("REPLAYGAIN_TRACK_GAIN" to "Infinity").replayGain)
        assertNull(tags("REPLAYGAIN_TRACK_GAIN" to "forte").replayGain)
        // Solo i picchi, senza guadagno, non servono a niente.
        assertNull(tags("REPLAYGAIN_TRACK_PEAK" to "0.9").replayGain)
        val rg = tags("REPLAYGAIN_TRACK_GAIN" to "-2 dB", "REPLAYGAIN_TRACK_PEAK" to "NaN").replayGain!!
        assertNull(rg.trackPeak)
    }

    @Test
    fun `Opus - guadagno R128 dell'album e il ReplayGain scritto vince`() {
        assertEquals(5f + 256 / 256f, tags("R128_ALBUM_GAIN" to "256").replayGain?.albumGain)
        assertEquals(-4f, tags("REPLAYGAIN_TRACK_GAIN" to "-4 dB", "R128_TRACK_GAIN" to "-512").replayGain?.trackGain)
    }

    @Test
    fun `chiavi in minuscolo con spazi e valori vuoti ignorati`() {
        val t = FileTags.from(
            listOf(
                RawTag(" title ", listOf("  Brano  ")),
                RawTag("ARTIST", listOf("", "  ")),
                RawTag("artist", listOf("Vero")),
                RawTag("txxx:musicbrainz album type", listOf("album; live")),
            ),
        )
        assertEquals("Brano", t.title)
        assertEquals("Vero", t.artist)
        assertEquals(listOf("album", "live"), t.releaseTypes)
    }

    @Test
    fun `tipi di uscita uniti da barra, punto e virgola o virgola, senza doppioni`() {
        assertEquals(listOf("album", "compilation", "live"), tags("RELEASETYPE" to "Album/Compilation, live;album").releaseTypes)
    }

    @Test
    fun `numero di traccia e disco - zeri davanti, totali, numeri di vinile`() {
        assertEquals(7, tags("TRCK" to " 07 ").track)
        assertEquals(2, tags("TPOS" to "2/3").disc)
        assertEquals(0, tags("TRCK" to "A1").track)
        assertEquals(0, tags("TRCK" to "0").track)
        assertEquals(0, tags("TRCK" to "-3").track)
    }

    @Test
    fun `anni - data completa, mese, zero`() {
        assertEquals(1994, tags("TDRC" to "1994-11").year)
        assertEquals(0, tags("DATE" to "0000").year)
        assertEquals(0, tags("DATE" to "sconosciuto").year)
        assertEquals(1999, tags("TORY" to "1999").originalYear)
        // La data zero vale zero anche come anno originale.
        assertEquals(0, tags("ORIGINALDATE" to "1970-01-01").originalYear)
    }

    @Test
    fun `compilation scritta in tanti modi`() {
        assertTrue(tags("TCMP" to "1").compilation)
        assertTrue(tags("COMPILATION" to "TRUE").compilation)
        assertFalse(tags("COMPILATION" to "0").compilation)
        assertFalse(tags("TITLE" to "x").compilation)
    }

    @Test
    fun `ID MusicBrainz multipli con punto e virgola, senza doppioni`() {
        val t = tags("MUSICBRAINZ_ARTISTID" to "a1; a2;a1", "MUSICBRAINZ_ALBUMID" to " r1 ")
        assertEquals(listOf("a1", "a2"), t.artistMbids)
        assertEquals("r1", t.albumMbid)
    }

    @Test
    fun `l'indice tiene tutti i campi, anche il ReplayGain misurato`() {
        val index = TagIndex()
        val f = FileStamp(3, 10, 20)
        val tags = FileTags(title = "T", releaseTypes = listOf("single"), replayGain = ReplayGain(trackGain = -5f, measured = true), originalYear = 1990)
        index.put(f, tags)
        assertEquals(tags, TagIndex.fromJson(index.toJson()).tags(f))
        // Stesso file con dimensione diversa: si rilegge.
        assertNull(index.tags(f.copy(size = 21)))
    }

    @Test
    fun `l'indice si rifa dopo la regola del ReplayGain illeggibile`() {
        assertEquals(3, TagIndex.VERSION)
        assertEquals(0, TagIndex.fromJson("""{"version":2,"entries":{"1":{"modified":1,"size":1,"tags":{"title":"x"}}}}""").size())
        assertEquals(1, TagIndex.fromJson("""{"version":3,"entries":{"1":{"modified":1,"size":1,"tags":{"title":"x","nuovo":1}}}}""").size())
    }
}

class AppUpdateMoreTest {

    @Test
    fun `APK con un indirizzo completo o un nome con spazi`() {
        val abs = ReleaseInfo(versionCode = 2, apk = "https://example.org/a/b.apk")
        assertEquals("https://example.org/a/b.apk", AppUpdate.apkUrl(abs))
        val spaced = ReleaseInfo(versionCode = 2, apk = "bald music.apk")
        assertEquals("https://h.example/d/bald%20music.apk", AppUpdate.apkUrl(spaced, "https://h.example/d/version.json"))
    }

    @Test
    fun `version json minimo - campi mancanti, versione negativa o scritta come testo`() {
        val info = AppUpdate.parse("""{"versionCode": 7}""")!!
        assertEquals("baldmusic.apk", info.apk)
        assertEquals(emptyList(), info.history)
        assertNull(info.size)
        assertNull(AppUpdate.parse("""{"versionCode": -1}"""))
        assertNull(AppUpdate.parse("""{"versionName": "1.0"}"""))
        assertNull(AppUpdate.parse(""))
    }

    @Test
    fun `novita solo tra la versione installata e quella pubblicata`() {
        val info = ReleaseInfo(
            versionCode = 10,
            history = listOf(
                io.github.baldborne94.baldmusic.core.update.ReleaseNotes(12, changes = listOf("futuro")),
                io.github.baldborne94.baldmusic.core.update.ReleaseNotes(9, changes = listOf("nove")),
                io.github.baldborne94.baldmusic.core.update.ReleaseNotes(10, changes = listOf("dieci")),
            ),
        )
        assertEquals(listOf(10, 9), info.changesBetween(8).map { it.versionCode })
        assertEquals(emptyList(), info.changesBetween(11))
        assertEquals(listOf(10, 9), info.changesBetween(0).map { it.versionCode })
    }

    @Test
    fun `si chiede sempre al server, senza cache, e si seguono i rimandi di GitHub`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "/objects/version.json").build())
            server.enqueue(MockResponse.Builder().body("""{"versionCode": 5}""").build())
            server.start()
            val latest = UpdateClient(OkHttpClient(), server.url("/releases/version.json").toString()).latest()
            assertEquals(5, latest?.versionCode)
            val first = server.takeRequest()
            assertEquals("no-cache", first.headers["Cache-Control"])
            assertEquals("/objects/version.json", server.takeRequest().url.encodedPath)
        }
    }

    @Test
    fun `una risposta che non e version json non propone aggiornamenti`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse.Builder().body("<html>rate limit</html>").build())
            server.start()
            assertNull(UpdateClient(OkHttpClient(), server.url("/version.json").toString()).latest())
        }
    }
}
