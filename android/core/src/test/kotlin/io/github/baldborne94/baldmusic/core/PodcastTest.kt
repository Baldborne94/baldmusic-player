package io.github.baldborne94.baldmusic.core

import io.github.baldborne94.baldmusic.core.diagnostics.ProblemReport
import io.github.baldborne94.baldmusic.core.library.LibraryNode
import io.github.baldborne94.baldmusic.core.library.LibraryBackup
import io.github.baldborne94.baldmusic.core.library.MediaId
import io.github.baldborne94.baldmusic.core.library.UserLibrary
import io.github.baldborne94.baldmusic.core.library.VoiceQuery
import io.github.baldborne94.baldmusic.core.podcast.Episode
import io.github.baldborne94.baldmusic.core.podcast.EpisodeLabels
import io.github.baldborne94.baldmusic.core.podcast.EpisodeProgress
import io.github.baldborne94.baldmusic.core.podcast.PodcastBackups
import io.github.baldborne94.baldmusic.core.podcast.PodcastDownloads
import io.github.baldborne94.baldmusic.core.podcast.Podcast
import io.github.baldborne94.baldmusic.core.podcast.PodcastFeed
import io.github.baldborne94.baldmusic.core.podcast.PodcastLibrary
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
        // La descrizione dal CDATA, senza HTML.
        assertEquals("Prima puntata", show.episodes[1].description)
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
        val sections = source.sections()
        // La puntata iniziata mette in cima «Da ascoltare».
        assertEquals(listOf("Da ascoltare", "Il mio podcast"), sections.map { it.title })
        val shows = sections.drop(1)
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

class EpisodeLabelsTest {
    private fun ep(title: String, at: Long) = Episode(key = title, title = title, audioUrl = "https://x/$title.mp3", publishedAt = at)
    private val day = 24L * 3600 * 1000
    private val oct19 = java.time.ZonedDateTime.of(2026, 10, 19, 7, 0, 0, 0, ZoneId.of("Europe/Rome")).toInstant().toEpochMilli()

    @Test
    fun `l'etichetta ripetuta in fondo al titolo va nella riga sotto`() {
        val episodes = listOf(
            ep("Il Caso Mo Wilson (Patreon Exclusive)", 5), ep("S15 E08 Il Caso Cinturrino - Roboscroc (Ad-Free)", 4),
            ep("Il Caso Zhu Ling (Patreon Exclusive)", 3), ep("S15 E07 Il Caso X (Ad-Free)", 2),
            ep("Speciale Rapine Folli (Patreon Exclusive)", 1), ep("S15 E06 Il Caso Y (Ad-Free)", 0),
            ep("Il Caso (Parte 1)", 0),
        )
        val tags = EpisodeLabels.tags(episodes)
        assertEquals(setOf("Patreon Exclusive", "Ad-Free"), tags)
        assertEquals("Il Caso Mo Wilson" to "Patreon Exclusive", EpisodeLabels.split("Il Caso Mo Wilson (Patreon Exclusive)", tags))
        assertEquals("S15 E08 Il Caso Cinturrino - Roboscroc" to "Ad-Free", EpisodeLabels.split("S15 E08 Il Caso Cinturrino - Roboscroc (Ad-Free)", tags))
        // Una parentesi che non si ripete resta nel titolo; un titolo che è solo l'etichetta resta com'è.
        assertEquals("Il Caso (Parte 1)" to null, EpisodeLabels.split("Il Caso (Parte 1)", tags))
        assertEquals("(Ad-Free)" to null, EpisodeLabels.split("(Ad-Free)", tags))
    }

    @Test
    fun `il podcast e un elenco unico dalla piu recente, diviso per mese`() = runBlocking {
        val show = Podcast("abc", "Non Aprite Quella Podcast", episodes = listOf(
            ep("Il Caso Mo Wilson (Patreon Exclusive)", oct19),
            ep("S15 E08 Il Caso Cinturrino (Ad-Free)", oct19 - 6 * day),
            ep("Il Caso Zhu Ling (Patreon Exclusive)", oct19 - 21 * day),
            ep("S15 E07 Il Caso X (Ad-Free)", oct19 - 27 * day),
            ep("Speciale Rapine Folli (Patreon Exclusive)", oct19 - 28 * day),
            ep("S15 E06 Il Caso Y (Ad-Free)", oct19 - 35 * day),
        ))
        val source = PodcastSource({ listOf(show) }, { PodcastLibrary() }, ZoneId.of("Europe/Rome"))
        val page = source.children(MediaId(PodcastSource.KEY, PodcastSource.SHOW, "abc"))
        // L'ordine di uscita, esclusive e stagioni mescolate come escono.
        assertEquals(listOf("Il Caso Mo Wilson", "S15 E08 Il Caso Cinturrino", "Il Caso Zhu Ling", "S15 E07 Il Caso X", "Speciale Rapine Folli", "S15 E06 Il Caso Y"), page.map { it.title })
        assertEquals(listOf("Ottobre 2026", "Ottobre 2026", "Settembre 2026", "Settembre 2026", "Settembre 2026", "Settembre 2026"), page.map { it.group })
        assertTrue(page[0].subtitle!!.startsWith("Patreon Exclusive · 19 ott 2026"))
        assertTrue(page.all { it.kind == LibraryNode.Kind.TRACK })
        assertEquals("Senza data", EpisodeLabels.month(null, ZoneId.of("Europe/Rome")))
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

class PodcastDownloadsTest {
    private val day = 24L * 3600 * 1000
    private val now = 100 * day
    private fun ep(n: Int, at: Long = now - n * day) = Episode("e$n", "Puntata $n", "https://x/$n.mp3", publishedAt = at)
    private val show = Podcast("abc", "Non Aprite Quella Podcast", image = "https://x/c.jpg", episodes = (1..6).map { ep(it) })
    private fun key(n: Int) = "abc~e$n"

    @Test
    fun `si tengono le ultime non ascoltate e quelle chieste, le ascoltate si tolgono`() {
        var lib = PodcastLibrary().withAutoDownload(2)
        // Le ultime due: la 1 e la 2 (la più recente è la 1).
        var plan = PodcastDownloads.plan(listOf(show), lib, present = emptySet())
        assertEquals(listOf(key(1), key(2)), plan.download.map { it.key })
        assertTrue(plan.download.none { it.manual })
        // Ascoltata la 1: entra la 3, e il file della 1 si toglie.
        lib = lib.mark(key(1), finished = true, now)
        plan = PodcastDownloads.plan(listOf(show), lib, present = setOf(key(1), key(2)))
        assertEquals(listOf(key(3)), plan.download.map { it.key })
        assertEquals(setOf(key(1)), plan.delete)
        // Una vecchia chiesta a mano: si scarica per prima, con qualsiasi rete.
        lib = lib.wantDownload(key(6), on = true)
        plan = PodcastDownloads.plan(listOf(show), lib, present = setOf(key(2)))
        assertEquals(key(6), plan.download.first().key)
        assertTrue(plan.download.first().manual)
        // Spento lo scaricamento da solo: restano solo quelle chieste; un podcast non più seguito va via.
        plan = PodcastDownloads.plan(listOf(show), lib.withAutoDownload(0), present = setOf(key(2), key(6), "altro~x"))
        assertTrue(plan.download.isEmpty())
        assertEquals(setOf(key(2), "altro~x"), plan.delete)
        assertEquals(10, PodcastLibrary().withAutoDownload(99).autoDownload)
    }

    @Test
    fun `segnare come ascoltata o da ascoltare`() {
        val lib = PodcastLibrary().wantDownload(key(1), true).record(key(1), 600_000, 3_600_000, 1)
        val done = lib.mark(key(1), finished = true, 2)
        assertTrue(done.progress.getValue(key(1)).finished)
        assertEquals(0, done.resumeAt(key(1)))
        assertFalse(key(1) in done.downloads)
        assertNull(done.mark(key(1), finished = false, 3).progress[key(1)])
        assertTrue(PodcastLibrary().wantDownload(key(1), true).unfollow("abc").downloads.isEmpty())
        // Finita ascoltandola: esce anche da quelle chieste a mano.
        assertFalse(key(1) in lib.record(key(1), 3_580_000, 3_600_000, 4).downloads)
        assertTrue(key(1) in lib.record(key(1), 900_000, 3_600_000, 4).downloads)
    }

    @Test
    fun `da ascoltare - prima quelle iniziate, poi le nuove, mai le ascoltate`() = runBlocking {
        val lib = PodcastLibrary()
            .record(key(5), 600_000, 3_600_000, now - day)
            .record(key(4), 900_000, 3_600_000, now)
            .mark(key(1), finished = true, now)
        val source = PodcastSource({ listOf(show) }, { lib }, ZoneId.of("Europe/Rome"), now = { now })
        val next = source.children(MediaId(PodcastSource.KEY, PodcastSource.UPNEXT))
        // Iniziate dall'ultima ascoltata (4, poi 5), poi nuove non cominciate (2, 3, 6: entro 14 giorni).
        assertEquals(listOf("Puntata 4", "Puntata 5", "Puntata 2", "Puntata 3", "Puntata 6"), next.map { it.title })
        assertEquals(listOf("Da finire", "Da finire", "Nuove", "Nuove", "Nuove"), next.map { it.group })
        assertEquals("Da ascoltare", source.sections().first().title)
        // Niente da ascoltare: niente sezione.
        val all = (1..6).fold(PodcastLibrary()) { l, n -> l.mark(key(n), true, now) }
        assertEquals(listOf("Non Aprite Quella Podcast"), PodcastSource({ listOf(show) }, { all }, now = { now }).sections().map { it.title })
    }

    @Test
    fun `una puntata scaricata suona dal telefono`() = runBlocking {
        val source = PodcastSource({ listOf(show) }, { PodcastLibrary() }, ZoneId.of("Europe/Rome"), downloaded = { if (it == key(2)) "file:///data/podcast/e2.mp3" else null }, now = { now })
        val episodes = source.children(MediaId(PodcastSource.KEY, PodcastSource.SHOW, "abc"))
        assertEquals("file:///data/podcast/e2.mp3", episodes[1].streamUri)
        assertTrue(episodes[1].subtitle!!.endsWith("scaricata"))
        assertEquals("https://x/1.mp3", episodes[0].streamUri)
    }
}

class PodcastPlayAndLinkTest {
    private val day = 24L * 3600 * 1000
    private val now = 100 * day
    private fun ep(n: Int, at: Long = now - n * day) = Episode("e$n", "Puntata $n", "https://x/$n.mp3", publishedAt = at)
    private val naqp = Podcast("abc", "Non Aprite Quella Podcast", episodes = (1..6).map { ep(it) })
    private val other = Podcast("zzz", "Un altro podcast", episodes = listOf(Episode("o1", "Altra", "https://y/1.mp3", publishedAt = now - 30 * day)))
    private fun key(n: Int) = "abc~e$n"
    private fun source(lib: PodcastLibrary) = PodcastSource({ listOf(naqp, other) }, { lib }, ZoneId.of("Europe/Rome"), now = { now })

    @Test
    fun `chiedere un podcast suona una puntata sola - quella a meta, poi la nuova, poi la piu recente non ascoltata`() = runBlocking {
        // A metà la 4: riparte quella, non l'archivio dalla più recente.
        var lib = PodcastLibrary().record(key(4), 900_000, 3_600_000, now)
        assertEquals("abc~e4", source(lib).toPlay(MediaId(PodcastSource.KEY, PodcastSource.SHOW, "abc"))?.let { PodcastSource.episodeKey(it.mediaId) })
        // Niente a metà: la più recente uscita da poco.
        lib = PodcastLibrary().mark(key(1), true, now)
        assertEquals("abc~e2", source(lib).toPlay(MediaId(PodcastSource.KEY, PodcastSource.SHOW, "abc"))?.let { PodcastSource.episodeKey(it.mediaId) })
        // Ascoltate tutte quelle recenti dell'altro podcast: la più recente non ascoltata, anche se vecchia.
        assertEquals("zzz~o1", source(PodcastLibrary()).toPlay(MediaId(PodcastSource.KEY, PodcastSource.SHOW, "zzz"))?.let { PodcastSource.episodeKey(it.mediaId) })
        // «Da ascoltare»: la prima della lista.
        assertEquals("abc~e2", source(lib).toPlay(MediaId(PodcastSource.KEY, PodcastSource.UPNEXT))?.let { PodcastSource.episodeKey(it.mediaId) })
        // Non è un contenitore dei podcast.
        assertNull(source(lib).toPlay(MediaId("local", "album", "1")))
    }

    @Test
    fun `con la voce - il nome del podcast, anche a pezzi, e l'ultima puntata`() = runBlocking {
        val lib = PodcastLibrary().record(key(4), 900_000, 3_600_000, now)
        fun k(node: LibraryNode?) = node?.let { PodcastSource.episodeKey(it.mediaId) }
        assertEquals("abc~e4", k(source(lib).forVoice("non aprite quella podcast")))
        assertEquals("abc~e4", k(source(lib).forVoice("Non aprite quella")))
        // «L'ultima puntata»: la più recente, anche con un'altra a metà.
        assertEquals("abc~e1", k(source(lib).forVoice("non aprite quella podcast", latest = true)))
        // Nessun nome: tra tutti.
        assertEquals("abc~e4", k(source(lib).forVoice("")))
        assertNull(source(lib).forVoice("Muschio Selvaggio"))
    }

    @Test
    fun `le richieste vocali di podcast`() {
        assertEquals(VoiceQuery.PodcastAsk("Non Aprite Quella Podcast", false), VoiceQuery.podcast("il podcast Non Aprite Quella Podcast"))
        assertEquals(VoiceQuery.PodcastAsk("Non Aprite Quella Podcast", true), VoiceQuery.podcast("l'ultima puntata di Non Aprite Quella Podcast"))
        assertEquals(VoiceQuery.PodcastAsk("NAQP", false), VoiceQuery.podcast("la puntata del podcast NAQP"))
        assertEquals(VoiceQuery.PodcastAsk("", false), VoiceQuery.podcast("un podcast"))
        assertEquals(VoiceQuery.PodcastAsk("", false), VoiceQuery.podcast("Podcast"))
        // Non parla di podcast: il nome detto da solo lo trova la ricerca.
        assertNull(VoiceQuery.podcast("Non Aprite Quella Podcast"))
        assertNull(VoiceQuery.podcast("Podcasting blues"))
        assertNull(VoiceQuery.podcast("la raccolta Disney Italia"))
    }

    @Test
    fun `un link rifiutato due volte di fila non funziona piu, uno solo no`() {
        var lib = PodcastLibrary().follow("https://www.patreon.com/rss/naqp?auth=A", 1)
        val id = lib.feeds.single().id
        lib = lib.feedChecked(id, refused = true)
        assertTrue(lib.broken().isEmpty())
        lib = lib.feedChecked(id, refused = true)
        assertEquals(listOf(id), lib.broken().map { it.id })
        // Torna a funzionare (o lo incolli di nuovo): l'avviso sparisce.
        assertTrue(lib.feedChecked(id, refused = false).broken().isEmpty())
        // Il link nuovo tiene l'ID, quindi dove eri arrivato.
        val renewed = lib.record("$id~e1", 600_000, 3_600_000, 2).replaceUrl(id, "https://www.patreon.com/rss/naqp?auth=B")
        assertEquals("https://www.patreon.com/rss/naqp?auth=B", renewed.feeds.single().url)
        assertTrue(renewed.broken().isEmpty())
        assertEquals(595_000, renewed.resumeAt("$id~e1"))
    }

    @Test
    fun `il link nuovo di Patreon sostituisce il podcast che segui gia`() {
        val fresh = naqp.copy(id = "nuovo")
        assertEquals("abc", PodcastFeed.replaces(listOf(other, naqp), fresh)?.id)
        // Titolo cambiato ma stesse puntate: è lo stesso podcast.
        assertEquals("abc", PodcastFeed.replaces(listOf(other, naqp), fresh.copy(title = "NAQP"))?.id)
        // Un podcast diverso: si aggiunge.
        assertNull(PodcastFeed.replaces(listOf(naqp), Podcast("x", "Altro", episodes = listOf(ep(9).copy(key = "q")))))
    }

    @Test
    fun `la descrizione della puntata - HTML in testo semplice`() {
        val html = """
            <p>Ospite: <b>Mario</b> &amp; Luigi.</p><p>Parliamo di <a href="https://it.wikipedia.org/wiki/Shining">Shining</a> e di https://naqp.it.</p>
            <ul><li>Uno</li><li>Due&nbsp;cose</li></ul>Fine<br/>riga&#8217;s &egrave;
        """.trimIndent()
        assertEquals(
            "Ospite: Mario & Luigi.\n\nParliamo di Shining (https://it.wikipedia.org/wiki/Shining) e di https://naqp.it.\n\n• Uno\n• Due cose\n\nFine\nriga’s è",
            PodcastFeed.plainText(html),
        )
        assertEquals("Solo testo", PodcastFeed.plainText("  Solo   testo "))
        assertTrue(PodcastFeed.plainText("a".repeat(5000)).length <= PodcastFeed.MAX_DESCRIPTION + 1)
    }
}

class PodcastBackupTest {
    private val oldUrl = "https://www.patreon.com/rss/naqp?auth=VECCHIO"
    private val newUrl = "https://www.patreon.com/rss/naqp?auth=NUOVO"
    private fun ep(n: Int) = Episode("e$n", "Puntata $n", "https://x/$n.mp3?token-hash=SEGRETO", publishedAt = n * 1000L)
    private fun show(id: String, title: String = "Non Aprite Quella Podcast") = Podcast(id, title, episodes = (1..4).map(::ep))

    private fun listened(): Pair<PodcastLibrary, Podcast> {
        var lib = PodcastLibrary().follow(oldUrl, 1).withAutoDownload(5)
        val id = lib.feeds.single().id
        lib = lib.record("$id~e2", 600_000, 3_600_000, 10).mark("$id~e1", true, 11).wantDownload("$id~e3", true)
        return lib to show(id)
    }

    @Test
    fun `nel backup ascoltate e punti di ripresa, mai il link`() {
        val (lib, naqp) = listened()
        val backup = assertNotNull(PodcastBackups.create(lib, listOf(naqp)))
        assertEquals("Non Aprite Quella Podcast", backup.shows.single().title)
        assertEquals(setOf("e1", "e2"), backup.shows.single().progress.keys)
        assertEquals(setOf("e3"), backup.shows.single().downloads)
        assertEquals(5, backup.autoDownload)
        val text = LibraryBackup.encode(LibraryBackup.create(UserLibrary(), emptyList(), 1, podcasts = backup))
        assertFalse("auth=" in text || "VECCHIO" in text || "patreon.com" in text || "token-hash" in text, text)
        assertEquals(backup, LibraryBackup.decode(text)?.podcasts)
        assertNull(PodcastBackups.create(PodcastLibrary(), emptyList()))
    }

    @Test
    fun `su un telefono nuovo il podcast aspetta il link e poi riprende da dove eri`() {
        val (lib, naqp) = listened()
        val backup = PodcastBackups.create(lib, listOf(naqp))!!
        // Ripristinato prima di seguire il podcast: aspetta, e resta anche nel backup dopo.
        var fresh = PodcastBackups.restore(PodcastLibrary(), backup, emptyList())
        assertEquals(1, fresh.pending.size)
        assertEquals(5, fresh.autoDownload)
        assertEquals(backup.shows, PodcastBackups.create(fresh, emptyList())?.shows)
        // Incolli il link nuovo (ID diverso): stesso podcast per titolo, tornano ascoltate e ripresa.
        fresh = fresh.follow(newUrl, 20)
        val newId = fresh.feeds.single().id
        assertFalse(newId == naqp.id)
        fresh = PodcastBackups.adopt(fresh, show(newId))
        assertTrue(fresh.pending.isEmpty())
        assertEquals(595_000, fresh.resumeAt("$newId~e2"))
        assertTrue(fresh.progress.getValue("$newId~e1").finished)
        assertTrue("$newId~e3" in fresh.downloads)
    }

    @Test
    fun `ripristinato su un podcast che segui gia vince il punto piu recente`() {
        val (lib, naqp) = listened()
        val backup = PodcastBackups.create(lib, listOf(naqp))!!
        // Sul telefono la puntata 2 è andata avanti dopo il backup: resta quella di adesso.
        val now = PodcastLibrary().follow(oldUrl, 1).record("${naqp.id}~e2", 1_200_000, 3_600_000, 99)
        val restored = PodcastBackups.restore(now, backup, listOf(naqp))
        assertTrue(restored.pending.isEmpty())
        assertEquals(1_195_000, restored.resumeAt("${naqp.id}~e2"))
        assertTrue(restored.progress.getValue("${naqp.id}~e1").finished)
        // Titolo cambiato ma le stesse puntate: è lo stesso podcast.
        assertTrue(PodcastBackups.matches(backup.shows.single(), show("altro", "NAQP")))
        assertFalse(PodcastBackups.matches(backup.shows.single(), Podcast("x", "Altro", episodes = listOf(ep(9)))))
    }

    @Test
    fun `i punti di ripresa entrano nel backup automatico come gli ascolti`() {
        val lib = UserLibrary()
        assertNull(LibraryBackup.autoWait(lib, lib, false, lastAt = 0, nowMs = 1_000))
        // Cambiati solo i podcast: al massimo ogni mezz'ora.
        assertEquals(LibraryBackup.PLAYS_EVERY_MS - 1_000, LibraryBackup.autoWait(lib, lib, false, lastAt = 0, nowMs = 1_000, podcastsChanged = true))
    }

    @Test
    fun `le descrizioni stanno in un file a parte`() {
        val full = Podcast("a", "P", episodes = listOf(ep(1).copy(description = "Di cosa parla"), ep(2)))
        val (light, notes) = PodcastFeed.withoutNotes(full)
        assertTrue(light.episodes.all { it.description == null })
        assertEquals(mapOf("e1" to "Di cosa parla"), notes)
        assertEquals(full.episodes.map { it.key }, light.episodes.map { it.key })
    }
}
