package io.github.baldborne94.baldmusic.core

import io.github.baldborne94.baldmusic.core.library.FileTags
import io.github.baldborne94.baldmusic.core.library.LibraryNode
import io.github.baldborne94.baldmusic.core.library.LibraryNode.Kind
import io.github.baldborne94.baldmusic.core.library.LocalCatalog
import io.github.baldborne94.baldmusic.core.library.LocalTrack
import io.github.baldborne94.baldmusic.core.library.MediaId
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private var nextId = 1L

private fun t(
    title: String, artist: String?, artistId: Long, album: String?, albumId: Long,
    disc: Int = 0, track: Int = 0, year: Int = 0, added: Long = 0, folder: String = "Music",
    tags: FileTags? = null,
): LocalTrack {
    val base = LocalTrack(nextId++, title, artist, artistId, album, albumId, 200_000, disc, track, year, added, folder)
    return tags?.let { base.withTags(it) } ?: base
}

/** Una piccola libreria del telefono senza tag di Picard: quello che sa MediaStore. */
private object Plain {
    val all = listOf(
        // Album su due dischi, elencato fuori ordine.
        t("Lato B", "Queen", 1, "Live Killers", 10, disc = 2, track = 1, year = 1979, added = 100, folder = "Music/Queen"),
        t("Apertura", "Queen", 1, "Live Killers", 10, disc = 1, track = 1, year = 1979, added = 100, folder = "Music/Queen"),
        t("Secondo", "Queen", 1, "Live Killers", 10, disc = 1, track = 2, year = 1979, added = 100, folder = "Music/Queen"),
        // Album più recente dello stesso artista.
        t("Uno", "Queen", 1, "Innuendo", 11, track = 1, year = 1991, added = 50, folder = "Music/Queen"),
        t("Due", "Queen", 1, "Innuendo", 11, track = 2, year = 1991, added = 50, folder = "Music/Queen"),
        // "Album" da un brano solo: un singolo (o un tag sbagliato).
        t("Singolo", "Queen", 1, "Singolo", 12, year = 1985, added = 300, folder = "Download"),
        // Un altro artista, con un album senza numeri di traccia.
        t("Zeta", "Lucio Dalla", 2, "Anidride", 20, year = 2005, added = 200, folder = "Music/Dalla"),
        t("Alfa", "Lucio Dalla", 2, "Anidride", 20, year = 2005, added = 200, folder = "Music/Dalla"),
        // Raccolta: due artisti nello stesso album, senza artista dell'album.
        t("Hit 1", "Queen", 1, "Hits", 30, track = 1, year = 2000, added = 10, folder = "Music/Hits"),
        t("Hit 2", "Lucio Dalla", 2, "Hits", 30, track = 2, year = 2000, added = 10, folder = "Music/Hits"),
    )
}

/** Brani passati da Picard, con i casi visti nella libreria vera. */
private object Picard {
    private const val AVRIL = "a-avril"
    private const val CHAD = "a-chad"
    private const val KORN = "a-korn"
    private const val ACDC = "a-acdc"
    private const val VA = "89ad4ac3-39f7-470e-963a-56509c546377"

    val letMeGo = t(
        "Let Me Go", "Avril Lavigne/Chad Kroeger", 100, "Avril Lavigne", 200,
        tags = FileTags(
            title = "Let Me Go", artist = "Avril Lavigne feat. Chad Kroeger", artists = listOf("Avril Lavigne", "Chad Kroeger"),
            album = "Avril Lavigne", albumArtist = "Avril Lavigne", artistMbids = listOf(AVRIL, CHAD), albumArtistMbids = listOf(AVRIL),
            albumMbid = "r-avril", releaseGroupMbid = "rg-avril", releaseTypes = listOf("album"), track = 3, year = 2013,
        ),
    )

    // Due brani dello stesso disco abbinati a due edizioni diverse: stesso gruppo, una scheda sola.
    val freak = t(
        "Freak on a Leash", "Korn", 101, "Follow the Leader", 201,
        tags = FileTags(
            title = "Freak on a Leash", artist = "Korn", artists = listOf("Korn"), album = "Follow the Leader", albumArtist = "Korn",
            artistMbids = listOf(KORN), albumArtistMbids = listOf(KORN), albumMbid = "r-ftl-1", releaseGroupMbid = "rg-ftl",
            releaseTypes = listOf("album"), track = 3, year = 1998,
        ),
    )
    val gotTheLife = t(
        "Got the Life", "Korn", 101, "Follow the Leader (Deluxe)", 202,
        tags = FileTags(
            title = "Got the Life", artist = "Korn", artists = listOf("Korn"), album = "Follow the Leader", albumArtist = "Korn",
            artistMbids = listOf(KORN), albumArtistMbids = listOf(KORN), albumMbid = "r-ftl-2", releaseGroupMbid = "rg-ftl",
            releaseTypes = listOf("album"), track = 2, year = 2005, originalYear = 1998,
        ),
    )

    // AC/DC con i tag e un file senza: lo stesso artista, mai spezzato in "AC" e "DC".
    val thunder = t(
        "Thunderstruck", "AC/DC", 102, "The Razors Edge", 203,
        tags = FileTags(
            title = "Thunderstruck", artist = "AC/DC", artists = listOf("AC/DC"), album = "The Razors Edge", albumArtist = "AC/DC",
            artistMbids = listOf(ACDC), albumArtistMbids = listOf(ACDC), releaseGroupMbid = "rg-razors", releaseTypes = listOf("album"),
            year = 2003, originalYear = 1990,
        ),
    )
    val highway = t("Highway to Hell", "AC/DC", 103, "Highway to Hell", 204, year = 1979)

    // Senza tag di Picard, artisti uniti da "/" e un ospite nel titolo.
    val summoning = t("The Summoning", "Bear McCreary/Raya Yarbrough", 104, "God of War", 205, year = 2018)
    val settingSail = t("Setting Sail (feat. Raya Yarbrough)", "Bear McCreary", 105, "God of War", 205, year = 2018)

    // Raccolta di artisti vari: due brani, quindi tra le raccolte.
    val giungla = t(
        "Il Ritmo Della Giungla", "Lorena Brancucci", 106, "Disney Hits", 206,
        tags = FileTags(
            title = "Il Ritmo Della Giungla", artist = "Lorena Brancucci", album = "Disney Hits", albumArtist = "Various Artists",
            albumArtistMbids = listOf(VA), compilation = true, releaseGroupMbid = "rg-disney", track = 1, year = 1999,
        ),
    )
    val hakuna = t(
        "Hakuna Matata", "Ivana Spagna", 107, "Disney Hits", 206,
        tags = FileTags(
            title = "Hakuna Matata", artist = "Ivana Spagna", album = "Disney Hits", albumArtist = "Various Artists",
            albumArtistMbids = listOf(VA), compilation = true, releaseGroupMbid = "rg-disney", track = 2, year = 1999,
        ),
    )

    // Lo stesso nome scritto in tre modi.
    val gnr1 = t("Don't Cry", "Guns N' Roses", 108, "Use Your Illusion I", 207, year = 1991)
    val gnr2 = t("November Rain", "Guns N’ Roses", 109, "Use Your Illusion I", 207, year = 1991)
    val gnr3 = t("Patience", "guns n' roses", 110, "G N' R Lies", 208, year = 1988)

    // Un singolo con il lato B: due brani, ma resta un singolo.
    val single = t(
        "Blind", "Korn", 101, "Blind", 209,
        tags = FileTags(
            title = "Blind", artist = "Korn", artists = listOf("Korn"), album = "Blind", albumArtist = "Korn", artistMbids = listOf(KORN),
            albumArtistMbids = listOf(KORN), releaseGroupMbid = "rg-blind", releaseTypes = listOf("single"), track = 1, year = 1994,
        ),
    )
    val singleB = single.copy(id = nextId++, title = "Lato B", track = 2)

    val all = listOf(letMeGo, freak, gotTheLife, thunder, highway, summoning, settingSail, giungla, hakuna, gnr1, gnr2, gnr3, single, singleB)
}

class LocalCatalogTest {
    private val catalog = LocalCatalog(streamUri = { "content://audio/$it" }, random = Random(42))
    private fun children(kind: String, id: String? = null, all: List<LocalTrack> = Plain.all) =
        catalog.children(MediaId(LocalCatalog.KEY, kind, id), all)

    private fun List<LibraryNode>.titles() = map { it.title }
    private fun idOf(node: LibraryNode) = MediaId.parse(node.mediaId)!!.id!!
    private fun artist(name: String, all: List<LocalTrack>) = children(LocalCatalog.ARTISTS, all = all).first { it.title == name }
    private fun artistPage(name: String, all: List<LocalTrack>) = children(LocalCatalog.ARTIST, idOf(artist(name, all)), all)

    // --- Libreria senza tag di Picard ---

    @Test
    fun `gli album con un solo brano non stanno tra gli album`() {
        assertEquals(listOf("Anidride", "Innuendo", "Live Killers"), children(LocalCatalog.ALBUMS).titles())
        assertEquals(listOf("Singolo"), children(LocalCatalog.SINGLES).titles())
    }

    @Test
    fun `un album di piu artisti senza artista dell'album e una raccolta`() {
        val hits = children(LocalCatalog.COMPILATIONS).single()
        assertEquals("Hits", hits.title)
        assertEquals("Artisti vari", hits.subtitle)
        assertEquals(null, hits.artist)
    }

    @Test
    fun `un album si ascolta per disco e traccia, poi per titolo`() {
        val live = children(LocalCatalog.ALBUMS).first { it.title == "Live Killers" }
        assertEquals(listOf("Apertura", "Secondo", "Lato B"), children(LocalCatalog.ALBUM, idOf(live)).titles())
        val anidride = children(LocalCatalog.ALBUMS).first { it.title == "Anidride" }
        assertEquals(listOf("Alfa", "Zeta"), children(LocalCatalog.ALBUM, idOf(anidride)).titles())
    }

    @Test
    fun `i vecchi ID numerici di album e artisti funzionano ancora`() {
        assertEquals(listOf("Apertura", "Secondo", "Lato B"), children(LocalCatalog.ALBUM, "10").titles())
        assertEquals(listOf("Innuendo", "Live Killers", "Singoli e brani sparsi", "Compare in"), children(LocalCatalog.ARTIST, "1").titles())
    }

    @Test
    fun `i brani portano disco, traccia, indirizzo e il loro contenitore`() {
        val parent = MediaId(LocalCatalog.KEY, LocalCatalog.ALBUM, "10")
        val first = catalog.children(parent, Plain.all).first()
        assertEquals(Kind.TRACK, first.kind)
        assertEquals(1, first.discNumber)
        assertEquals(1, first.trackNumber)
        assertEquals("content://audio/2", first.streamUri)
        assertEquals(parent, MediaId.parse(first.mediaId)?.parent)
        assertEquals(children(LocalCatalog.ALBUMS).first { it.title == "Live Killers" }.mediaId, first.albumId)
        assertEquals(artist("Queen", Plain.all).mediaId, first.artistId)
    }

    @Test
    fun `l'artista mostra i suoi album, i brani sparsi e dove compare`() {
        val queen = artistPage("Queen", Plain.all)
        assertEquals(listOf("Innuendo", "Live Killers", "Singoli e brani sparsi", "Compare in"), queen.titles())
        val key = idOf(artist("Queen", Plain.all))
        assertEquals(listOf("Singolo"), children(LocalCatalog.ARTIST_SINGLES, key).titles())
        assertEquals(listOf("Hit 1"), children(LocalCatalog.ARTIST_APPEARS, key).titles())
    }

    @Test
    fun `l'elenco artisti conta album e brani`() {
        val artists = children(LocalCatalog.ARTISTS)
        assertEquals(listOf("Lucio Dalla", "Queen"), artists.titles())
        assertEquals("2 album · 7 brani", artists.last().subtitle)
    }

    @Test
    fun `aggiunti di recente prima gli album poi i brani sparsi`() {
        assertEquals(listOf("Anidride", "Live Killers", "Innuendo", "Hits", "Singolo"), children(LocalCatalog.RECENT).titles())
    }

    @Test
    fun `decenni dal piu recente, ognuno col suo nome`() {
        assertEquals(listOf("Anni 2000", "Anni 90", "Anni 80", "Anni 70"), children(LocalCatalog.DECADES).titles())
        assertEquals(listOf("Apertura", "Secondo", "Lato B"), children(LocalCatalog.DECADE, "1970").titles())
    }

    @Test
    fun `cartelle in ordine, col percorso nel sottotitolo`() {
        val folders = children(LocalCatalog.FOLDERS)
        assertEquals(listOf("Download", "Dalla", "Hits", "Queen"), folders.titles())
        assertEquals("Music · 2 brani", folders[1].subtitle)
    }

    @Test
    fun `la ricerca trova artisti, album e brani`() {
        val r = catalog.search("dalla", Plain.all)
        assertEquals(Kind.ARTIST, r.first().kind)
        assertTrue(r.any { it.kind == Kind.TRACK && it.title == "Zeta" })
        assertEquals(emptyList(), catalog.search("  ", Plain.all))
    }

    @Test
    fun `la radio parte dal brano scelto, senza ripetizioni, e riaperta resta uguale`() {
        val radio = children(LocalCatalog.RADIO, "4")
        assertEquals("Uno", radio.first().title)
        assertEquals(radio.size, radio.map { it.mediaId }.distinct().size)
        assertEquals(Plain.all.size, radio.size)
        assertEquals(radio, children(LocalCatalog.RADIO, "4"))
    }

    @Test
    fun `il mix casuale si ritrova identico per ricostruire la coda`() {
        val mix = children(LocalCatalog.SHUFFLE)
        assertEquals(mix, children(LocalCatalog.SHUFFLE, "last"))
        assertEquals(Plain.all.size, mix.size)
    }

    // --- Libreria passata da Picard ---

    @Test
    fun `un ospite ha la sua pagina, col brano in Compare in`() {
        val chad = artistPage("Chad Kroeger", Picard.all)
        assertEquals(listOf("Compare in"), chad.titles())
        assertEquals(listOf("Let Me Go"), children(LocalCatalog.ARTIST_APPEARS, idOf(artist("Chad Kroeger", Picard.all)), Picard.all).titles())
        // Avril non ha album con due brani: il suo brano si vede subito.
        assertEquals(listOf("Let Me Go"), artistPage("Avril Lavigne", Picard.all).titles())
    }

    @Test
    fun `edizioni diverse dello stesso disco sono un album solo`() {
        val korn = artistPage("Korn", Picard.all)
        assertEquals(listOf("Follow the Leader", "Singoli e brani sparsi"), korn.titles())
        assertEquals(listOf("Got the Life", "Freak on a Leash"), children(LocalCatalog.ALBUM, idOf(korn.first()), Picard.all).titles())
        assertEquals(1998, korn.first().year)
    }

    @Test
    fun `un singolo con il lato B resta tra i singoli`() {
        assertTrue(children(LocalCatalog.ALBUMS, all = Picard.all).none { it.title == "Blind" })
        val korn = idOf(artist("Korn", Picard.all))
        assertEquals(listOf("Blind", "Lato B"), children(LocalCatalog.ARTIST_SINGLES, korn, Picard.all).titles())
    }

    @Test
    fun `AC-DC resta un artista solo, con e senza tag`() {
        val names = children(LocalCatalog.ARTISTS, all = Picard.all).titles()
        assertTrue("AC/DC" in names)
        assertTrue("AC" !in names && "DC" !in names)
        assertEquals("2 brani", artist("AC/DC", Picard.all).subtitle)
    }

    @Test
    fun `artisti uniti dalla barra e ospiti nel titolo sono separati`() {
        val names = children(LocalCatalog.ARTISTS, all = Picard.all).titles()
        assertTrue("Bear McCreary" in names)
        assertTrue("Raya Yarbrough" in names)
        val raya = artistPage("Raya Yarbrough", Picard.all)
        assertEquals(listOf("Compare in"), raya.titles())
        assertEquals(
            listOf("Setting Sail (feat. Raya Yarbrough)", "The Summoning"),
            children(LocalCatalog.ARTIST_APPEARS, idOf(artist("Raya Yarbrough", Picard.all)), Picard.all).titles().sorted(),
        )
    }

    @Test
    fun `lo stesso artista scritto in modi diversi e uno solo`() {
        val gnr = children(LocalCatalog.ARTISTS, all = Picard.all).filter { it.title.lowercase().startsWith("guns") }
        assertEquals(1, gnr.size)
        assertEquals("1 album · 3 brani", gnr.single().subtitle)
    }

    @Test
    fun `le raccolte di artisti vari stanno tra le raccolte, non tra gli artisti`() {
        assertEquals(listOf("Disney Hits"), children(LocalCatalog.COMPILATIONS, all = Picard.all).titles())
        assertTrue(children(LocalCatalog.ALBUMS, all = Picard.all).none { it.title == "Disney Hits" })
        assertTrue(children(LocalCatalog.ARTISTS, all = Picard.all).none { it.title == "Various Artists" })
        assertEquals(listOf("Compare in"), artistPage("Lorena Brancucci", Picard.all).titles())
    }

    @Test
    fun `i decenni usano l'anno della prima uscita`() {
        val nineties = children(LocalCatalog.DECADE, "1990", Picard.all).titles()
        assertTrue("Thunderstruck" in nineties)
        assertTrue("Got the Life" in nineties)
    }

    @Test
    fun `un file senza album nei tag non prende il nome della cartella`() {
        val noAlbum = t("Demo", "Io", 300, "Download", 400, tags = FileTags(title = "Demo", artist = "Io"))
        assertEquals(null, noAlbum.album)
        val node = catalog.children(MediaId(LocalCatalog.KEY, LocalCatalog.SINGLES), listOf(noAlbum)).single()
        assertEquals(null, node.albumId)
    }
}
