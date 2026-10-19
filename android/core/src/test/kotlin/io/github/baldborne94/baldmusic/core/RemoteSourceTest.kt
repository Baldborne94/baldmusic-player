package io.github.baldborne94.baldmusic.core

import io.github.baldborne94.baldmusic.core.library.LibraryNode.Kind
import io.github.baldborne94.baldmusic.core.library.LibraryTree
import io.github.baldborne94.baldmusic.core.library.MediaId
import io.github.baldborne94.baldmusic.core.library.RemoteSource
import io.github.baldborne94.baldmusic.core.library.ReplayGain
import io.github.baldborne94.baldmusic.core.subsonic.Credentials
import io.github.baldborne94.baldmusic.core.subsonic.SubsonicClient
import io.github.baldborne94.baldmusic.core.subsonic.SubsonicException
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Un Navidrome finto: ogni chiamata ("getAlbum?id=a1", "getAlbumList2?type=newest",
 * "getArtists"…) ha la sua risposta. Quelle che non conosce rispondono come Navidrome
 * quando un elemento non c'è (status "failed", codice 70).
 */
private class FakeNavidrome : Dispatcher() {
    val routes = HashMap<String, String>()
    val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())

    operator fun set(key: String, body: String) {
        routes[key] = body
    }

    override fun dispatch(request: RecordedRequest): MockResponse {
        val url = request.url
        val method = url.encodedPath.substringAfterLast('/')
        val keys = listOfNotNull(
            url.queryParameter("id")?.let { "$method?id=$it" },
            url.queryParameter("type")?.let { "$method?type=$it" },
            method,
        )
        calls += keys.first()
        val body = keys.firstNotNullOfOrNull { routes[it] }
            ?: return MockResponse.Builder().body("""{"subsonic-response":{"status":"failed","error":{"code":70,"message":"not found"}}}""").build()
        return MockResponse.Builder().body("""{"subsonic-response":{"status":"ok",$body}}""").build()
    }
}

class RemoteSourceTest {
    private val server = MockWebServer()
    private val nd = FakeNavidrome()

    @BeforeTest fun start() {
        server.dispatcher = nd
        server.start()
    }

    @AfterTest fun stop() = server.close()

    private val source by lazy {
        RemoteSource(SubsonicClient(Credentials.create(server.url("/").toString(), "ale", "sesame", "c19b2d"), OkHttpClient()))
    }

    private fun id(kind: String, id: String? = null) = MediaId(RemoteSource.KEY, kind, id)

    private fun song(id: String, title: String, album: String = "al1", extra: String = "") =
        """{"id":"$id","title":"$title","album":"Disco","albumId":"$album","artist":"Avril","artistId":"ar1","duration":200,"coverArt":"al-$album"$extra}"""

    @Test
    fun `le sezioni del server`() = runTest {
        val sections = source.sections()
        assertEquals(
            listOf("remote/recent", "remote/albums", "remote/artists", "remote/playlists", "remote/starred", "remote/random"),
            sections.map { it.mediaId },
        )
        assertTrue(sections.all { it.kind == Kind.SECTION })
        // Le sezioni di album e playlist si mostrano a griglia, le altre a lista.
        assertEquals(listOf(true, true, false, true, false, false), sections.map { it.gridChildren })
    }

    @Test
    fun `tra gli album solo quelli veri, per lettera e senza articolo davanti`() = runTest {
        nd["getAlbumList2?type=alphabeticalByName"] = """"albumList2":{"album":[
            {"id":"a1","name":"Issues","artist":"Korn","songCount":16,"year":2011,"coverArt":"al-a1","releaseTypes":["Album"],"originalReleaseDate":{"year":1999}},
            {"id":"a2","name":"Blind","artist":"Korn","songCount":2,"releaseTypes":["single"]},
            {"id":"a3","name":"Hits","artist":"Vari","songCount":1},
            {"id":"a4","name":"The Wall","artist":"Pink Floyd","songCount":26,"year":1979},
            {"id":"a5","name":"99 Luftballons","artist":"Nena","songCount":4}
        ]}"""
        val albums = source.children(id(RemoteSource.ALBUMS))
        assertEquals(listOf("99 Luftballons", "Issues", "The Wall"), albums.map { it.title })
        assertEquals(listOf("#", "I", "W"), albums.map { it.group })
        val issues = albums[1]
        assertEquals("remote/album/a1", issues.mediaId)
        assertEquals(Kind.ALBUM, issues.kind)
        assertEquals("Korn", issues.subtitle)
        assertEquals("remote/al-a1", issues.artwork)
        // L'anno della prima uscita, non quello della ristampa.
        assertEquals(1999, issues.year)
        assertNull(albums[2].artwork)
        assertEquals("alphabeticalByName", server.takeRequest().url.queryParameter("type"))
    }

    @Test
    fun `aggiunti di recente - gli album veri, poi i brani di singoli e album da un brano`() = runTest {
        nd["getAlbumList2?type=newest"] = """"albumList2":{"album":[
            {"id":"a1","name":"Disco","songCount":10},
            {"id":"s1","name":"Singolo","songCount":2,"releaseTypes":["Single"]},
            {"id":"s2","name":"Sparso","songCount":1}
        ]}"""
        nd["getAlbum?id=s1"] = """"album":{"id":"s1","name":"Singolo","song":[${song("t1", "Lato A", "s1")},${song("t2", "Lato B", "s1")}]}"""
        nd["getAlbum?id=s2"] = """"album":{"id":"s2","name":"Sparso","song":[${song("t3", "Unico", "s2")}]}"""
        val recent = source.children(id(RemoteSource.RECENT))
        assertEquals(listOf("Disco", "Lato A", "Lato B", "Unico"), recent.map { it.title })
        assertEquals(listOf(Kind.ALBUM, Kind.TRACK, Kind.TRACK, Kind.TRACK), recent.map { it.kind })
        // I brani ricordano da dove vengono: la coda è quella dei recenti.
        assertEquals("remote/track/t1|remote/recent", recent[1].mediaId)
    }

    @Test
    fun `brani di un singolo che il server non legge si saltano, gli altri restano`() = runTest {
        nd["getAlbumList2?type=newest"] = """"albumList2":{"album":[{"id":"s1","songCount":1},{"id":"s2","songCount":1}]}"""
        nd["getAlbum?id=s2"] = """"album":{"id":"s2","song":[${song("t3", "Unico", "s2")}]}"""
        assertEquals(listOf("Unico"), source.children(id(RemoteSource.RECENT)).map { it.title })
    }

    @Test
    fun `artisti per lettera col nome di ordinamento, e col nome se quello manca o e vuoto`() = runTest {
        // Navidrome manda "sortName":"" per chi non ha il tag di ordinamento.
        nd["getArtists"] = """"artists":{"index":[
            {"name":"A","artist":[{"id":"1","name":"ABBA","albumCount":3,"sortName":""}]},
            {"name":"B","artist":[{"id":"2","name":"The Beatles","albumCount":1,"sortName":"Beatles, The","coverArt":"ar-2_0"}]},
            {"name":"Z","artist":[{"id":"3","name":"Zucchero"}]},
            {"name":"C","artist":[{"id":"4","name":"Cher","sortName":"  "}]}
        ]}"""
        val artists = source.children(id(RemoteSource.ARTISTS))
        assertEquals(listOf("ABBA", "The Beatles", "Cher", "Zucchero"), artists.map { it.title })
        assertEquals(listOf("A", "B", "C", "Z"), artists.map { it.group })
        assertEquals(listOf("3 album", "1 album", null, null), artists.map { it.subtitle })
        // Senza copertina propria, quella che Navidrome compone per l'artista.
        assertEquals(listOf("remote/ar-1", "remote/ar-2_0"), artists.take(2).map { it.artwork })
        assertTrue(artists.all { it.kind == Kind.ARTIST && it.gridChildren })
        assertEquals("remote/artist/2", artists[1].mediaId)
    }

    @Test
    fun `album su piu dischi con le intestazioni Disco N e il titolo del disco`() = runTest {
        nd["getAlbum?id=lotr"] = """"album":{"id":"lotr","name":"LOTR","discTitles":[{"disc":2,"title":"The Two Towers"}],"song":[
            ${song("t1", "Uno", "lotr", ""","track":1,"discNumber":1""")},
            ${song("t2", "Due", "lotr", ""","track":1,"discNumber":2""")},
            ${song("t3", "Tre", "lotr", ""","track":2,"discNumber":2""")}
        ]}"""
        val tracks = source.children(id(RemoteSource.ALBUM, "lotr"))
        assertEquals(listOf("Disco 1", "Disco 2 · The Two Towers", "Disco 2 · The Two Towers"), tracks.map { it.group })
        assertEquals(listOf(1, 2, 2), tracks.map { it.discNumber })
        assertEquals("remote/track/t2|remote/album/lotr", tracks[1].mediaId)
    }

    @Test
    fun `album su un disco solo senza intestazioni`() = runTest {
        nd["getAlbum?id=a1"] = """"album":{"id":"a1","name":"Disco","song":[
            ${song("t1", "Uno", "a1", ""","discNumber":1""")},${song("t2", "Due", "a1")}
        ]}"""
        assertEquals(listOf(null, null), source.children(id(RemoteSource.ALBUM, "a1")).map { it.group })
    }

    private val discography = """"artist":{"id":"ar1","name":"Avril","album":[
        {"id":"a1","name":"Let Go","songCount":13,"year":2002,"releaseTypes":["album"]},
        {"id":"a2","name":"Under My Skin","songCount":12,"year":2014,"releaseTypes":["album"],"originalReleaseDate":{"year":2004}},
        {"id":"a3","name":"Live at Budokan","songCount":15,"year":2005,"releaseTypes":["album","live"]},
        {"id":"a4","name":"Avril Lavigne","songCount":13,"year":2013},
        {"id":"a5","name":"Remixes","songCount":4,"year":2003,"releaseTypes":["ep"]},
        {"id":"s1","name":"Complicated","songCount":2,"year":2002,"releaseTypes":["single"],"coverArt":"al-s1"},
        {"id":"s2","name":"Hits","songCount":1,"year":2010}
    ]}"""

    @Test
    fun `discografia per tipo, ognuno dal piu recente con l'anno della prima uscita`() = runTest {
        nd["getArtist?id=ar1"] = discography
        val page = source.children(id(RemoteSource.ARTIST, "ar1"))
        assertEquals(
            listOf("Avril Lavigne", "Under My Skin", "Let Go", "Remixes", "Live at Budokan", "Singoli e brani sparsi"),
            page.map { it.title },
        )
        assertEquals(listOf("Album", "Album", "Album", "EP", "Dal vivo", "Altro"), page.map { it.group })
        val singles = page.last()
        assertEquals("remote/artistsingles/ar1", singles.mediaId)
        assertEquals(Kind.SECTION, singles.kind)
        assertEquals("remote/al-s1", singles.artwork)
    }

    @Test
    fun `la sezione dei singoli conta i brani, non i dischi`() = runTest {
        nd["getArtist?id=ar1"] = discography
        // Un singolo con lato A e lato B più un album da un brano: tre brani in due dischi.
        assertEquals("3 brani", source.children(id(RemoteSource.ARTIST, "ar1")).last().subtitle)
    }

    @Test
    fun `singoli e brani sparsi dell'artista per anno e titolo`() = runTest {
        nd["getArtist?id=ar1"] = discography
        nd["getAlbum?id=s1"] = """"album":{"id":"s1","song":[${song("t1", "Complicated", "s1", ""","year":2002""")},${song("t2", "B-side", "s1", ""","year":2002""")}]}"""
        nd["getAlbum?id=s2"] = """"album":{"id":"s2","song":[${song("t3", "Alice", "s2", ""","year":2010""")}]}"""
        val singles = source.children(id(RemoteSource.ARTIST_SINGLES, "ar1"))
        assertEquals(listOf("B-side", "Complicated", "Alice"), singles.map { it.title })
        assertEquals("remote/track/t2|remote/artistsingles/ar1", singles.first().mediaId)
    }

    @Test
    fun `artista senza album veri mostra subito i suoi brani`() = runTest {
        nd["getArtist?id=ar9"] = """"artist":{"id":"ar9","name":"Nena","album":[
            {"id":"s1","name":"99 Luftballons","songCount":1,"year":1983},
            {"id":"s2","name":"Irgendwie","songCount":2,"year":1984,"releaseTypes":["single"]}
        ]}"""
        nd["getAlbum?id=s1"] = """"album":{"id":"s1","song":[${song("t1", "99 Luftballons", "s1", ""","year":1983""")}]}"""
        nd["getAlbum?id=s2"] = """"album":{"id":"s2","song":[${song("t2", "Irgendwie", "s2", ""","year":1984""")},${song("t3", "Lato B", "s2", ""","year":1984""")}]}"""
        val page = source.children(id(RemoteSource.ARTIST, "ar9"))
        assertEquals(listOf("99 Luftballons", "Irgendwie", "Lato B"), page.map { it.title })
        assertTrue(page.all { it.kind == Kind.TRACK })
        // La coda è l'artista: scegliendo un brano si ascoltano tutti i suoi.
        assertEquals("remote/track/t2|remote/artist/ar9", page[1].mediaId)
        val tree = LibraryTree({ listOf(source) })
        val (queue, start) = tree.queueFor(page[1].mediaId)
        assertEquals(listOf("99 Luftballons", "Irgendwie", "Lato B"), queue.map { it.title })
        assertEquals(1, start)
    }

    @Test
    fun `un brano completo - file originale, durata, ReplayGain, album e artista`() = runTest {
        nd["getSong?id=t1"] = """"song":${song("t1", "Complicated", "al1", ""","track":2,"discNumber":1,"year":2002,"replayGain":{"trackGain":-7.5,"albumGain":-8.25,"trackPeak":0.98,"albumPeak":1.0}""")}"""
        val parent = id(RemoteSource.ALBUM, "al1")
        val node = source.track(MediaId.track(RemoteSource.KEY, "t1", parent))!!
        assertEquals("remote/track/t1|remote/album/al1", node.mediaId)
        assertEquals(Kind.TRACK, node.kind)
        assertEquals("Complicated", node.title)
        assertEquals("Avril", node.subtitle)
        assertEquals("Disco", node.album)
        assertEquals(200_000L, node.durationMs)
        assertEquals(2, node.trackNumber)
        assertEquals(2002, node.year)
        assertEquals("remote/al-al1", node.artwork)
        assertEquals("remote/album/al1", node.albumId)
        assertEquals("remote/artist/ar1", node.artistId)
        assertEquals(ReplayGain(trackGain = -7.5f, albumGain = -8.25f, trackPeak = 0.98f, albumPeak = 1.0f), node.replayGain)
        assertFalse(node.replayGain!!.measured)
        val stream = node.streamUri!!.toHttpUrl()
        assertEquals("/rest/stream", stream.encodedPath)
        assertEquals("t1", stream.queryParameter("id"))
        // Sempre il file originale: niente transcodifica né bitrate ridotto.
        assertEquals("raw", stream.queryParameter("format"))
        assertNull(stream.queryParameter("maxBitRate"))
        assertNull(stream.queryParameter("p"))
    }

    @Test
    fun `senza ReplayGain nei tag il brano resta senza, e senza durata pure`() = runTest {
        nd["getSong?id=t1"] = """"song":{"id":"t1","title":"X"}"""
        val node = source.track(MediaId.track(RemoteSource.KEY, "t1", null))!!
        assertNull(node.replayGain)
        assertNull(node.durationMs)
        assertNull(node.albumId)
        assertNull(node.artwork)
        assertEquals("remote/track/t1", node.mediaId)
    }

    @Test
    fun `un brano che il server non ha piu non si risolve`() = runTest {
        assertNull(source.track(MediaId.track(RemoteSource.KEY, "sparito", null)))
        assertNull(source.track(MediaId(RemoteSource.KEY, MediaId.TRACK)))
    }

    @Test
    fun `playlist del server con il numero di brani`() = runTest {
        nd["getPlaylists"] = """"playlists":{"playlist":[
            {"id":"p1","name":"Viaggio","songCount":1,"coverArt":"pl-p1"},
            {"id":"p2","name":"Palestra","songCount":12},
            {"id":"p3","name":"Vuota"}
        ]}"""
        nd["getPlaylist?id=p2"] = """"playlist":{"id":"p2","name":"Palestra","entry":[${song("t1", "Uno")},${song("t2", "Due", "al2")}]}"""
        val lists = source.children(id(RemoteSource.PLAYLISTS))
        assertEquals(listOf("1 brano", "12 brani", null), lists.map { it.subtitle })
        assertEquals(listOf("remote/pl-p1", null, null), lists.map { it.artwork })
        assertTrue(lists.all { it.kind == Kind.PLAYLIST })
        val entries = source.children(id(RemoteSource.PLAYLIST, "p2"))
        assertEquals(listOf("remote/track/t1|remote/playlist/p2", "remote/track/t2|remote/playlist/p2"), entries.map { it.mediaId })
    }

    @Test
    fun `preferiti del server, coi brani che tornano ai preferiti`() = runTest {
        nd["getStarred2"] = """"starred2":{"album":[{"id":"a1"}],"song":[${song("t1", "Uno")}]}"""
        val starred = source.children(id(RemoteSource.STARRED))
        assertEquals(listOf("remote/track/t1|remote/starred"), starred.map { it.mediaId })
    }

    @Test
    fun `mix casuale - si pesca entrando, last ritrova gli stessi brani senza chiedere`() = runTest {
        nd["getRandomSongs"] = """"randomSongs":{"song":[${song("t1", "Uno")},${song("t2", "Due")}]}"""
        val mix = source.children(id(RemoteSource.RANDOM))
        assertEquals(listOf("remote/track/t1|remote/random/last", "remote/track/t2|remote/random/last"), mix.map { it.mediaId })
        val again = source.children(source.stableRandomId())
        assertEquals(mix.map { it.mediaId }, again.map { it.mediaId })
        // Il brano si trova nel mix già pescato: nessuna chiamata a getSong.
        assertEquals("Due", source.track(MediaId.parse(mix[1].mediaId)!!)?.title)
        assertEquals(listOf("getRandomSongs"), nd.calls.toList())
    }

    @Test
    fun `ricerca - artisti, album e brani senza contenitore`() = runTest {
        nd["search3"] = """"searchResult3":{
            "artist":[{"id":"ar1","name":"Avril"}],
            "album":[{"id":"a1","name":"Let Go","artist":"Avril"}],
            "song":[${song("t1", "Complicated")}]
        }"""
        val found = source.search("avril")
        assertEquals(listOf(Kind.ARTIST, Kind.ALBUM, Kind.TRACK), found.map { it.kind })
        assertEquals("remote/track/t1", found.last().mediaId)
        assertEquals("avril", server.takeRequest().url.queryParameter("query"))
    }

    @Test
    fun `con l'albero - un brano dell'album accoda l'album da quel brano`() = runTest {
        nd["getAlbum?id=a1"] = """"album":{"id":"a1","song":[${song("t1", "Uno", "a1")},${song("t2", "Due", "a1")},${song("t3", "Tre", "a1")}]}"""
        val tree = LibraryTree({ listOf(source) })
        val third = tree.children("remote/album/a1")[2]
        val (queue, start) = tree.queueFor(third.mediaId)
        assertEquals(listOf("Uno", "Due", "Tre"), queue.map { it.title })
        assertEquals(2, start)
        assertTrue(queue.all { it.streamUri!!.contains("format=raw") })
    }

    @Test
    fun `con l'albero - un artista si ascolta con gli album e poi i singoli`() = runTest {
        nd["getArtist?id=ar1"] = """"artist":{"id":"ar1","album":[
            {"id":"a1","name":"Let Go","songCount":2,"year":2002},
            {"id":"s1","name":"Complicated","songCount":1,"year":2002}
        ]}"""
        nd["getAlbum?id=a1"] = """"album":{"id":"a1","song":[${song("t1", "Uno", "a1")},${song("t2", "Due", "a1")}]}"""
        nd["getAlbum?id=s1"] = """"album":{"id":"s1","song":[${song("t3", "Complicated", "s1")}]}"""
        val tree = LibraryTree({ listOf(source) })
        assertEquals(listOf("Uno", "Due", "Complicated"), tree.playableIn("remote/artist/ar1").map { it.title })
    }

    @Test
    fun `server spento - l'elenco da errore, la ricerca nell'albero resta vuota`() = runTest {
        server.close()
        assertFailsWith<SubsonicException> { source.children(id(RemoteSource.ALBUMS)) }
        assertEquals(emptyList(), LibraryTree({ listOf(source) }).search("x"))
    }

    @Test
    fun `contenitore sconosciuto e vuoto`() = runTest {
        assertEquals(emptyList(), source.children(id("boh")))
    }
}
