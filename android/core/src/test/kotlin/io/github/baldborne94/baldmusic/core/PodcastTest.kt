package io.github.baldborne94.baldmusic.core

import io.github.baldborne94.baldmusic.core.diagnostics.ProblemReport
import io.github.baldborne94.baldmusic.core.library.LibraryNode
import io.github.baldborne94.baldmusic.core.library.MediaId
import io.github.baldborne94.baldmusic.core.podcast.Episode
import io.github.baldborne94.baldmusic.core.podcast.EpisodeProgress
import io.github.baldborne94.baldmusic.core.podcast.Podcast
import io.github.baldborne94.baldmusic.core.podcast.PodcastFeed
import io.github.baldborne94.baldmusic.core.podcast.PodcastLibrary
import io.github.baldborne94.baldmusic.core.podcast.PodcastSeasons
import io.github.baldborne94.baldmusic.core.podcast.PodcastSource
import kotlinx.coroutines.runBlocking
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PodcastTest {
    private val feedUrl = "https://www.patreon.com/rss/ilmiopodcast?auth=SEGRETO123&show=42"

    // Come il feed privato di Patreon: RSS 2.0 con i tag di iTunes, l'audio in <enclosure>.
    private val xml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd" xmlns:content="http://purl.org/rss/1.0/modules/content/">
          <channel>
            <title>Il mio podcast</title>
            <itunes:author>Baldborne</itunes:author>
            <itunes:image href="https://c10.patreonusercontent.com/cover.jpg"/>
            <item>
              <title>Puntata 1 &amp; introduzione</title>
              <guid isPermaLink="false">patreon-111</guid>
              <pubDate>Mon, 05 Oct 2026 07:30:00 GMT</pubDate>
              <itunes:duration>45:30</itunes:duration>
              <enclosure url="https://c10.patreonusercontent.com/1.mp3?token-time=1&amp;token-hash=abc" type="audio/mpeg" length="123"/>
              <description><![CDATA[<p>Prima puntata</p>]]></description>
            </item>
            <item>
              <title>Puntata 2</title>
              <guid>patreon-222</guid>
              <pubDate>Mon, 19 Oct 2026 07:30:00 +0000</pubDate>
              <itunes:duration>3725</itunes:duration>
              <enclosure url="https://c10.patreonusercontent.com/2.mp3" type="audio/mpeg"/>
            </item>
            <item>
              <title>Solo testo, niente audio</title>
              <guid>patreon-333</guid>
            </item>
          </channel>
        </rss>
    """.trimIndent()

    private val showId = PodcastFeed.idOf(feedUrl)
    private val show = PodcastFeed.parse(xml, showId, now = 1_000)!!

    @Test
    fun `il feed di Patreon si legge con le puntate dalla piu recente`() {
        assertEquals("Il mio podcast", show.title)
        assertEquals("Baldborne", show.author)
        assertEquals("https://c10.patreonusercontent.com/cover.jpg", show.image)
        // Il post senza audio non è una puntata.
        assertEquals(listOf("Puntata 2", "Puntata 1 & introduzione"), show.episodes.map { it.title })
        assertEquals((3725L) * 1000, show.episodes[0].durationMs)
        assertEquals((45L * 60 + 30) * 1000, show.episodes[1].durationMs)
        assertEquals("https://c10.patreonusercontent.com/1.mp3?token-time=1&token-hash=abc", show.episodes[1].audioUrl)
        assertNotNull(show.episodes[1].publishedAt)
        // Lo stesso feed riletto dà le stesse chiavi: il punto di ripresa resta attaccato alla puntata.
        assertEquals(show.episodes.map { it.key }, PodcastFeed.parse(xml, showId, 2_000)!!.episodes.map { it.key })
    }

    @Test
    fun `l'indirizzo privato non finisce negli ID e i link si ripuliscono`() {
        assertFalse("SEGRETO" in showId)
        assertEquals(12, showId.length)
        assertEquals(feedUrl, PodcastFeed.cleanUrl("  $feedUrl \n"))
        assertEquals("https://feeds.example.com/show", PodcastFeed.cleanUrl("itpc://feeds.example.com/show"))
        assertNull(PodcastFeed.cleanUrl("non è un link"))
        assertNull(PodcastFeed.cleanUrl("ftp://example.com/feed"))
        // Nel resoconto di «Segnala un problema» la chiave e le firme dell'audio spariscono.
        val report = ProblemReport.redact("feed $feedUrl audio ${show.episodes[1].audioUrl}")
        assertFalse("SEGRETO" in report)
        assertFalse("token-hash=abc" in report)
    }

    @Test
    fun `quello che non e un feed non si legge`() {
        assertNull(PodcastFeed.parse("<html><body>Accedi a Patreon</body></html>", "x", 0))
        assertNull(PodcastFeed.parse("non xml", "x", 0))
        // Niente entità esterne: un feed che prova a leggere file del telefono non passa.
        val evil = """<?xml version="1.0"?><!DOCTYPE r [<!ENTITY x SYSTEM "file:///etc/passwd">]><rss><channel><title>&x;</title></channel></rss>"""
        assertTrue(PodcastFeed.parse(evil, "x", 0)?.title.orEmpty().let { "root" !in it })
    }

    @Test
    fun `durate e date dei feed`() {
        assertEquals(3_723_000, PodcastFeed.duration("01:02:03"))
        assertEquals(3_723_000, PodcastFeed.duration("62:03"))
        assertEquals(3_723_000, PodcastFeed.duration("3723"))
        assertNull(PodcastFeed.duration("un'ora"))
        assertNull(PodcastFeed.duration(""))
        assertNotNull(PodcastFeed.date("Tue, 21 Oct 2026 07:30:00 GMT"))
        assertNotNull(PodcastFeed.date("21 Oct 2026 07:30:00 +0200"))
        assertNull(PodcastFeed.date("ieri"))
    }

    @Test
    fun `una puntata riparte da dove eri e finita riparte dall'inizio`() {
        val key = "$showId~abc"
        var lib = PodcastLibrary().follow(feedUrl, 0)
        assertEquals(0, lib.resumeAt(key))
        lib = lib.record(key, 600_000, 3_600_000, 1)
        assertEquals(595_000, lib.resumeAt(key))
        // Appena cominciata: si riparte dall'inizio.
        assertEquals(0, PodcastLibrary().record(key, 10_000, 3_600_000, 1).resumeAt(key))
        // A meno di un minuto dalla fine: finita.
        lib = lib.record(key, 3_560_000, 3_600_000, 2)
        assertTrue(lib.progress.getValue(key).finished)
        assertEquals(0, lib.resumeAt(key))
        // Seguire due volte lo stesso feed non lo raddoppia; smettere toglie anche i punti.
        assertEquals(1, lib.follow(feedUrl, 3).feeds.size)
        assertTrue(lib.unfollow(showId).let { it.feeds.isEmpty() && it.progress.isEmpty() })
        assertEquals(lib, PodcastLibrary.decode(PodcastLibrary.encode(lib)))
        assertEquals(PodcastLibrary(), PodcastLibrary.decode("rovinato"))
    }

    @Test
    fun `i podcast sono una sorgente come le altre, anche in Auto`() = runBlocking {
        val key = PodcastSource.keyOf(show, show.episodes[1])
        val library = PodcastLibrary().follow(feedUrl, 0).record(key, 20 * 60_000, show.episodes[1].durationMs, 1)
        val source = PodcastSource({ listOf(show) }, { library }, ZoneId.of("Europe/Rome"))
        val shows = source.sections()
        assertEquals(listOf("Il mio podcast"), shows.map { it.title })
        assertEquals("podcast/$showId", shows[0].artwork)
        val episodes = source.children(MediaId.parse(shows[0].mediaId)!!)
        assertEquals(LibraryNode.Kind.TRACK, episodes[0].kind)
        // Nell'ID niente indirizzo privato, e nessun contenitore: suona la puntata scelta.
        assertFalse(episodes.any { "SEGRETO" in it.mediaId || "patreonusercontent" in it.mediaId })
        assertNull(MediaId.parse(episodes[0].mediaId)!!.parent)
        assertEquals("5 ott 2026 · 46 min · mancano 26 min", episodes[1].subtitle)
        assertEquals(shows[0].mediaId, episodes[1].albumId)
        // Ritrovata dall'ID, con l'indirizzo da riprodurre.
        val again = source.track(MediaId.parse(episodes[1].mediaId)!!)
        assertEquals(show.episodes[1].audioUrl, again?.streamUri)
        assertEquals(key, PodcastSource.episodeKey(episodes[1].mediaId))
        assertNull(PodcastSource.episodeKey("local/track/5"))
        assertEquals(listOf("Puntata 2"), source.search("puntata 2").map { it.title })
    }

    @Test
    fun `per suonare una puntata si usano gli indirizzi aggiornati`() = runBlocking {
        // Nei feed privati l'audio ha una firma che scade: la copia vecchia va bene per l'elenco, non per suonare.
        val renewed = show.copy(episodes = show.episodes.map { it.copy(audioUrl = it.audioUrl.substringBefore('?') + "?token-hash=nuovo") })
        var refreshed = 0
        val source = PodcastSource({ listOf(show) }, { PodcastLibrary() }, forPlayback = { refreshed++; listOf(renewed) })
        val episode = source.children(MediaId(PodcastSource.KEY, PodcastSource.SHOW, show.id))[1]
        assertEquals(0, refreshed)
        assertEquals("https://c10.patreonusercontent.com/1.mp3?token-hash=nuovo", source.track(MediaId.parse(episode.mediaId)!!)?.streamUri)
        assertEquals(1, refreshed)
    }

    @Test
    fun `minuti come li dice una persona`() {
        assertEquals("1 min", PodcastSource.minutes(10_000))
        assertEquals("46 min", PodcastSource.minutes(45 * 60_000L + 30_000))
        assertEquals("1 h 2 min", PodcastSource.minutes(3_725_000))
        assertEquals("2 h", PodcastSource.minutes(7_200_000))
        assertEquals("", PodcastSource({ emptyList() }, { PodcastLibrary() }).subtitle(Episode("k", "t", "u"), null))
        assertEquals("ascoltata", PodcastSource({ emptyList() }, { PodcastLibrary() }).subtitle(Episode("k", "t", "u"), EpisodeProgress(0, finished = true)))
    }
}

class PodcastSeasonsTest {
    private fun ep(title: String, at: Long, season: Int? = null, number: Int? = null) =
        Episode(key = title, title = title, audioUrl = "https://x/$title.mp3", publishedAt = at, season = season, number = number)

    @Test
    fun `stagione e numero dal titolo, se il feed non li dice`() {
        assertEquals(15 to 8, PodcastSeasons.of(ep("S15 E08 Il Caso Cinturrino - Roboscroc (Ad-Free)", 0)))
        assertEquals(15 to 8, PodcastSeasons.of(ep("S15E08 Il Caso", 0)))
        assertEquals(3 to 4, PodcastSeasons.of(ep("Stagione 3 - Episodio 4: la casa", 0)))
        assertEquals(2 to 5, PodcastSeasons.of(ep("Season 2 Episode 5", 0)))
        assertEquals(1 to 12, PodcastSeasons.of(ep("1x12 Il pilota", 0)))
        assertEquals(7 to null, PodcastSeasons.of(ep("Speciale stagione 7", 0)))
        // I tag del feed valgono più del titolo.
        assertEquals(4 to 2, PodcastSeasons.of(ep("S15 E08 titolo sbagliato", 0, season = 4, number = 2)))
        assertNull(PodcastSeasons.of(ep("Live a Milano", 0)))
        assertNull(PodcastSeasons.of(ep("PS5 e 2 amici", 0)))
    }

    @Test
    fun `le puntate si dividono per stagione, la piu recente prima, poi le altre`() {
        val episodes = listOf(
            ep("S15 E02 Seconda", 400), ep("Live a Milano", 350), ep("S15 E01 Prima", 300),
            ep("S14 E10 Finale", 200), ep("S14 E09 Penultima", 100),
        )
        val seasons = PodcastSeasons.split(episodes)
        assertEquals(listOf(15, 14, null), seasons.map { it.number })
        assertEquals(listOf("S15 E01 Prima", "S15 E02 Seconda"), seasons[0].episodes.map { it.title })
        assertEquals(listOf("Live a Milano"), seasons[2].episodes.map { it.title })
        // Una stagione sola: niente cartelle.
        assertTrue(PodcastSeasons.split(episodes.filter { "S15" in it.title }).isEmpty())
        assertTrue(PodcastSeasons.split(emptyList()).isEmpty())
    }

    @Test
    fun `il podcast si apre su ultime puntate e stagioni, anche in Auto`() = runBlocking {
        val show = Podcast("abc", "Non Aprite Quella Podcast", image = "https://x/cover.jpg", episodes = listOf(
            ep("S15 E02 Seconda", 400), ep("S15 E01 Prima", 300), ep("S14 E01 Vecchia", 100), ep("Trailer", 50),
        ))
        val source = PodcastSource({ listOf(show) }, { PodcastLibrary() })
        val page = source.children(MediaId(PodcastSource.KEY, PodcastSource.SHOW, "abc"))
        assertEquals(listOf("Ultime puntate", "Stagione 15", "Stagione 14", "Altre puntate"), page.map { it.title })
        assertEquals("2 puntate", page[1].subtitle)
        assertEquals(listOf("S15 E01 Prima", "S15 E02 Seconda"), source.children(MediaId.parse(page[1].mediaId)!!).map { it.title })
        assertEquals("S15 E02 Seconda", source.children(MediaId.parse(page[0].mediaId)!!).first().title)
        assertEquals(listOf("Trailer"), source.children(MediaId.parse(page[3].mediaId)!!).map { it.title })
        assertEquals("Stagione 14", source.node(MediaId.parse(page[2].mediaId)!!)?.title)
        // Le puntate dentro una stagione si suonano come sempre.
        val first = source.children(MediaId.parse(page[1].mediaId)!!).first()
        assertEquals("https://x/S15 E01 Prima.mp3", source.track(MediaId.parse(first.mediaId)!!)?.streamUri)
    }
}

class PodcastNewEpisodesTest {
    private val day = 24L * 3600 * 1000
    private val now = 100 * day
    private fun ep(key: String, at: Long?) = Episode(key, "Puntata $key", "https://x/$key.mp3", publishedAt = at)
    private fun show(vararg e: Episode) = Podcast("abc", "Non Aprite Quella Podcast", episodes = e.toList())

    @Test
    fun `si annuncia la puntata appena uscita, non tutto l'archivio`() {
        val before = show(ep("1", now - 10 * day))
        val after = show(ep("2", now - day), ep("1", now - 10 * day))
        assertEquals(listOf("2"), PodcastFeed.newEpisodes(before, after, now).map { it.key })
        // Appena seguito il podcast: nessun annuncio per le centinaia di puntate già uscite.
        assertTrue(PodcastFeed.newEpisodes(null, after, now).isEmpty())
        // Una puntata vecchia ripubblicata, o senza data: niente annuncio.
        assertTrue(PodcastFeed.newEpisodes(before, show(ep("vecchia", now - 60 * day), ep("senza", null), ep("1", 0)), now).isEmpty())
        // Niente di nuovo: niente annuncio.
        assertTrue(PodcastFeed.newEpisodes(after, after, now).isEmpty())
    }

    @Test
    fun `il testo della notifica`() {
        val s = show()
        assertEquals("Nuova puntata di Non Aprite Quella Podcast" to "Puntata 2", PodcastFeed.notice(s, listOf(ep("2", now))))
        assertEquals("2 puntate nuove di Non Aprite Quella Podcast" to "Puntata 3 · Puntata 2", PodcastFeed.notice(s, listOf(ep("3", now), ep("2", now))))
    }
}
