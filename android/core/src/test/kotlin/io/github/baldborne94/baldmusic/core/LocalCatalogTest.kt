package io.github.baldborne94.baldmusic.core

import io.github.baldborne94.baldmusic.core.library.LibraryNode.Kind
import io.github.baldborne94.baldmusic.core.library.LocalCatalog
import io.github.baldborne94.baldmusic.core.library.LocalTrack
import io.github.baldborne94.baldmusic.core.library.MediaId
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Una piccola libreria del telefono con i casi che contano. */
private object Lib {
    private var nextId = 1L
    private fun t(
        title: String, artist: String?, artistId: Long, album: String?, albumId: Long,
        disc: Int = 0, track: Int = 0, year: Int = 0, added: Long = 0, folder: String = "Music",
    ) = LocalTrack(nextId++, title, artist, artistId, album, albumId, 200_000, disc, track, year, added, folder)

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
        // Raccolta: due artisti nello stesso album.
        t("Hit 1", "Queen", 1, "Hits", 30, track = 1, year = 2000, added = 10, folder = "Music/Hits"),
        t("Hit 2", "Lucio Dalla", 2, "Hits", 30, track = 2, year = 2000, added = 10, folder = "Music/Hits"),
    )
}

class LocalCatalogTest {
    private val catalog = LocalCatalog(streamUri = { "content://audio/$it" }, random = Random(42))
    private fun children(kind: String, id: String? = null) = catalog.children(MediaId(LocalCatalog.KEY, kind, id), Lib.all)

    @Test
    fun `gli album con un solo brano non stanno tra gli album`() {
        assertEquals(listOf("Anidride", "Hits", "Innuendo", "Live Killers"), children(LocalCatalog.ALBUMS).map { it.title })
        assertEquals(listOf("Singolo"), children(LocalCatalog.SINGLES).map { it.title })
    }

    @Test
    fun `un album si ascolta per disco e traccia, poi per titolo`() {
        assertEquals(listOf("Apertura", "Secondo", "Lato B"), children(LocalCatalog.ALBUM, "10").map { it.title })
        assertEquals(listOf("Alfa", "Zeta"), children(LocalCatalog.ALBUM, "20").map { it.title })
    }

    @Test
    fun `i brani portano disco, traccia, indirizzo e il loro contenitore`() {
        val first = children(LocalCatalog.ALBUM, "10").first()
        assertEquals(Kind.TRACK, first.kind)
        assertEquals(1, first.discNumber)
        assertEquals(1, first.trackNumber)
        assertEquals("content://audio/2", first.streamUri)
        assertEquals(MediaId(LocalCatalog.KEY, LocalCatalog.ALBUM, "10"), MediaId.parse(first.mediaId)?.parent)
        assertEquals(null, children(LocalCatalog.ALBUM, "20").first().trackNumber)
    }

    @Test
    fun `una raccolta con piu artisti e di Artisti vari`() {
        val hits = children(LocalCatalog.ALBUMS).first { it.title == "Hits" }
        assertEquals("Artisti vari", hits.subtitle)
        assertEquals(null, hits.artist)
    }

    @Test
    fun `l'artista mostra gli album dal piu recente e poi i singoli`() {
        val queen = children(LocalCatalog.ARTIST, "1")
        assertEquals(listOf("Innuendo", "Live Killers", "Singoli e brani sparsi"), queen.map { it.title })
        // Oggi il suo brano nella raccolta "Hits" finisce tra i singoli: l'album si conta
        // sui soli brani dell'artista. Con l'artista dell'album (fase 1) andrà in "Compare in".
        assertEquals("2 brani", queen.last().subtitle)
        assertEquals(listOf("Singolo", "Hit 1"), children(LocalCatalog.ARTIST_SINGLES, "1").map { it.title })
    }

    @Test
    fun `l'elenco artisti conta album e singoli`() {
        val artists = children(LocalCatalog.ARTISTS)
        assertEquals(listOf("Lucio Dalla", "Queen"), artists.map { it.title })
        assertEquals("2 album · 2 singoli", artists.last().subtitle)
    }

    @Test
    fun `aggiunti di recente prima gli album poi i brani sparsi`() {
        assertEquals(listOf("Anidride", "Live Killers", "Innuendo", "Hits", "Singolo"), children(LocalCatalog.RECENT).map { it.title })
    }

    @Test
    fun `decenni dal piu recente, ognuno col suo nome`() {
        val decades = children(LocalCatalog.DECADES)
        assertEquals(listOf("Anni 2000", "Anni 90", "Anni 80", "Anni 70"), decades.map { it.title })
        assertEquals(listOf("Apertura", "Secondo", "Lato B"), children(LocalCatalog.DECADE, "1970").map { it.title })
    }

    @Test
    fun `cartelle in ordine, col percorso nel sottotitolo`() {
        val folders = children(LocalCatalog.FOLDERS)
        assertEquals(listOf("Download", "Dalla", "Hits", "Queen"), folders.map { it.title })
        assertEquals("Music · 2 brani", folders[1].subtitle)
    }

    @Test
    fun `la ricerca trova artisti, album e brani`() {
        val r = catalog.search("dalla", Lib.all)
        assertEquals(Kind.ARTIST, r.first().kind)
        assertTrue(r.any { it.kind == Kind.TRACK && it.title == "Zeta" })
        assertEquals(emptyList(), catalog.search("  ", Lib.all))
    }

    @Test
    fun `la radio parte dal brano scelto, senza ripetizioni, e riaperta resta uguale`() {
        val radio = children(LocalCatalog.RADIO, "4")
        assertEquals("Uno", radio.first().title)
        assertEquals(radio.size, radio.map { it.mediaId }.distinct().size)
        assertEquals(Lib.all.size, radio.size)
        assertEquals(radio, children(LocalCatalog.RADIO, "4"))
    }

    @Test
    fun `il mix casuale si ritrova identico per ricostruire la coda`() {
        val mix = children(LocalCatalog.SHUFFLE)
        assertEquals(mix, children(LocalCatalog.SHUFFLE, "last"))
        assertEquals(Lib.all.size, mix.size)
    }
}
