package io.github.baldborne94.baldmusic.core

import io.github.baldborne94.baldmusic.core.library.ArtistNames
import io.github.baldborne94.baldmusic.core.library.Discography
import io.github.baldborne94.baldmusic.core.library.FileTags
import io.github.baldborne94.baldmusic.core.library.LocalCatalog
import io.github.baldborne94.baldmusic.core.library.LocalTrack
import io.github.baldborne94.baldmusic.core.library.MediaId
import io.github.baldborne94.baldmusic.core.library.ReleaseKind
import kotlin.test.Test
import kotlin.test.assertEquals

private var id = 1000L

private fun song(title: String, artist: String, album: String, albumId: Long, disc: Int = 0, track: Int = 0, year: Int = 2000, tags: FileTags? = null): LocalTrack {
    val base = LocalTrack(id++, title, artist, 1, album, albumId, 200_000, disc, track, year, 0, "Music/$artist")
    return tags?.let { base.withTags(it) } ?: base
}

class GroupingTest {

    @Test
    fun `ordine alfabetico come una persona`() {
        val names = listOf("The Beatles", "Il Volo", "Èlia", "AC/DC", "L'Aura", "Zucchero", "50 Cent", "Iron Maiden")
        assertEquals(listOf("50 Cent", "AC/DC", "L'Aura", "The Beatles", "Èlia", "Iron Maiden", "Il Volo", "Zucchero"), names.sortedBy(ArtistNames::sortKey))
        assertEquals("B", ArtistNames.letter("The Beatles"))
        assertEquals("E", ArtistNames.letter("Èlia"))
        assertEquals("#", ArtistNames.letter("50 Cent"))
        assertEquals("#", ArtistNames.letter("Пётр Чайковский"))
    }

    @Test
    fun `tipi di uscita di MusicBrainz`() {
        assertEquals(ReleaseKind.ALBUM, Discography.kindOf(listOf("album")))
        assertEquals(ReleaseKind.ALBUM, Discography.kindOf(emptyList()))
        assertEquals(ReleaseKind.LIVE, Discography.kindOf(listOf("Album", "Live")))
        assertEquals(ReleaseKind.SOUNDTRACK, Discography.kindOf(listOf("album", "soundtrack")))
        assertEquals(ReleaseKind.COMPILATION, Discography.kindOf(listOf("album", "compilation")))
        assertEquals(ReleaseKind.EP, Discography.kindOf(listOf("ep")))
        assertEquals(ReleaseKind.OTHER, Discography.kindOf(listOf("album", "remix")))
    }

    @Test
    fun `artisti per lettera, coi piu ascoltati in cima`() {
        val beatles = song("Help", "The Beatles", "Help!", 1)
        val tracks = listOf(beatles, song("Volare", "Il Volo", "Grande amore", 2), song("Back in Black", "AC/DC", "Back in Black", 3), song("Rain", "The Beatles", "Help!", 1))
        val plays = mapOf(LocalCatalog.ref(beatles).id to 9)
        val catalog = LocalCatalog(streamUri = { "x" }, plays = { plays })
        val artists = catalog.children(MediaId(LocalCatalog.KEY, LocalCatalog.ARTISTS), tracks)
        // Meno di tre artisti ascoltati: niente sezione dei più ascoltati, solo le lettere.
        assertEquals(listOf("AC/DC" to "A", "The Beatles" to "B", "Il Volo" to "V"), artists.map { it.title to it.group })

        val many = tracks + song("Uno", "Queen", "Innuendo", 4) + song("Due", "Muse", "Drones", 5)
        val morePlays = many.associate { LocalCatalog.ref(it).id to 1 } + plays
        val withTop = LocalCatalog(streamUri = { "x" }, plays = { morePlays }).children(MediaId(LocalCatalog.KEY, LocalCatalog.ARTISTS), many)
        assertEquals("I tuoi più ascoltati", withTop.first().title)
        val top = LocalCatalog(streamUri = { "x" }, plays = { morePlays }).children(MediaId.parse(withTop.first().mediaId)!!, many)
        assertEquals("The Beatles", top.first().title)
    }

    @Test
    fun `album su piu dischi con le intestazioni dei dischi`() {
        val tracks = listOf(
            song("Concerning Hobbits", "Howard Shore", "LOTR", 10, disc = 1, track = 1, tags = FileTags(title = "Concerning Hobbits", artist = "Howard Shore", album = "LOTR", albumArtist = "Howard Shore", disc = 1, track = 1, discSubtitle = "The Fellowship")),
            song("Rohan", "Howard Shore", "LOTR", 10, disc = 2, track = 1, tags = FileTags(title = "Rohan", artist = "Howard Shore", album = "LOTR", albumArtist = "Howard Shore", disc = 2, track = 1)),
            song("The Bridge", "Howard Shore", "LOTR", 10, disc = 1, track = 2, tags = FileTags(title = "The Bridge", artist = "Howard Shore", album = "LOTR", albumArtist = "Howard Shore", disc = 1, track = 2, discSubtitle = "The Fellowship")),
        )
        val catalog = LocalCatalog(streamUri = { "x" })
        val album = catalog.children(MediaId(LocalCatalog.KEY, LocalCatalog.ALBUMS), tracks).single()
        val songs = catalog.children(MediaId.parse(album.mediaId)!!, tracks)
        assertEquals(listOf("Concerning Hobbits", "The Bridge", "Rohan"), songs.map { it.title })
        assertEquals(listOf("Disco 1 · The Fellowship", "Disco 1 · The Fellowship", "Disco 2"), songs.map { it.group })
    }

    @Test
    fun `discografia per tipo, ognuno dal piu recente`() {
        fun tagged(title: String, album: String, albumId: Long, year: Int, types: List<String>) =
            song(title, "Linkin Park", album, albumId, year = year, tags = FileTags(title = title, artist = "Linkin Park", album = album, albumArtist = "Linkin Park", year = year, releaseTypes = types))
        val tracks = listOf(
            tagged("Numb", "Meteora", 1, 2003, listOf("album")), tagged("Faint", "Meteora", 1, 2003, listOf("album")),
            tagged("Papercut", "Hybrid Theory", 2, 2000, listOf("album")), tagged("Crawling", "Hybrid Theory", 2, 2000, listOf("album")),
            tagged("Numb (Live)", "Road to Revolution", 3, 2008, listOf("album", "live")), tagged("Faint (Live)", "Road to Revolution", 3, 2008, listOf("album", "live")),
            tagged("Points", "Reanimation", 4, 2002, listOf("album", "remix")), tagged("Pts.OF.Athrty", "Reanimation", 4, 2002, listOf("album", "remix")),
        )
        val catalog = LocalCatalog(streamUri = { "x" })
        val artist = catalog.children(MediaId(LocalCatalog.KEY, LocalCatalog.ARTISTS), tracks).single()
        val page = catalog.children(MediaId.parse(artist.mediaId)!!, tracks)
        assertEquals(
            listOf("Meteora" to "Album", "Hybrid Theory" to "Album", "Road to Revolution" to "Dal vivo", "Reanimation" to "Altro"),
            page.map { it.title to it.group },
        )
    }
}
