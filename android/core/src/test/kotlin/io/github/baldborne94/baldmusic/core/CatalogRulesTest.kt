package io.github.baldborne94.baldmusic.core

import io.github.baldborne94.baldmusic.core.library.ArtistNames
import io.github.baldborne94.baldmusic.core.library.FileTags
import io.github.baldborne94.baldmusic.core.library.LibraryNode
import io.github.baldborne94.baldmusic.core.library.LibraryNode.Kind
import io.github.baldborne94.baldmusic.core.library.LocalCatalog
import io.github.baldborne94.baldmusic.core.library.LocalTrack
import io.github.baldborne94.baldmusic.core.library.MediaId
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private var nextTrack = 5000L

/** Un file del telefono come lo vede MediaStore, eventualmente coi tag letti dal file. */
private fun file(
    title: String, artist: String?, album: String?, albumId: Long,
    disc: Int = 0, track: Int = 0, year: Int = 0, added: Long = 0, folder: String = "Music",
    ms: Long = 200_000, id: Long = nextTrack++, tags: FileTags? = null,
): LocalTrack {
    val base = LocalTrack(id, title, artist, 1, album, albumId, ms, disc, track, year, added, folder)
    return tags?.let { base.withTags(it) } ?: base
}

/** Un file passato da Picard: i tag del file valgono più di quelli di MediaStore. */
private fun picard(
    title: String, artist: String, album: String, albumId: Long,
    albumArtist: String? = artist, artistMbids: List<String> = emptyList(), albumArtistMbids: List<String> = emptyList(),
    releaseGroup: String? = null, types: List<String> = listOf("album"), track: Int = 0, disc: Int = 0, year: Int = 0,
    originalYear: Int = 0, artists: List<String> = emptyList(), artistSort: String? = null, albumArtistSort: String? = null,
    compilation: Boolean = false, folder: String = "Music/$artist",
) = file(
    title, artist, album, albumId, folder = folder,
    tags = FileTags(
        title = title, artist = artist, artists = artists, album = album, albumArtist = albumArtist,
        artistMbids = artistMbids, albumArtistMbids = albumArtistMbids, releaseGroupMbid = releaseGroup,
        releaseTypes = types, track = track, disc = disc, year = year, originalYear = originalYear,
        artistSort = artistSort, albumArtistSort = albumArtistSort, compilation = compilation,
    ),
)

class CatalogRulesTest {
    private val catalog = LocalCatalog(streamUri = { "content://audio/$it" }, random = Random(7))
    private fun children(kind: String, all: List<LocalTrack>, id: String? = null) = catalog.children(MediaId(LocalCatalog.KEY, kind, id), all)
    private fun List<LibraryNode>.titles() = map { it.title }
    private fun idOf(node: LibraryNode) = MediaId.parse(node.mediaId)!!.id!!
    private fun artist(name: String, all: List<LocalTrack>) = children(LocalCatalog.ARTISTS, all).first { it.title == name }
    private fun page(name: String, all: List<LocalTrack>) = children(LocalCatalog.ARTIST, all, idOf(artist(name, all)))

    // --- Album veri, singoli e brani sparsi ---

    @Test
    fun `la cartella Disney con un album diverso per brano finisce tutta tra gli sparsi`() {
        val disney = listOf("Hakuna Matata", "Il cerchio della vita", "Il ritmo della giungla", "Un amico in me").mapIndexed { i, title ->
            file(title, listOf("Ivana Spagna", "Ivana Spagna", "Lorena Brancucci", "Riccardo Cocciante")[i], title, 100L + i, year = 1970, folder = "Music/Disney Italia")
        }
        assertEquals(emptyList(), children(LocalCatalog.ALBUMS, disney))
        assertEquals(4, children(LocalCatalog.SINGLES, disney).size)
        // Senza album, la pagina dell'artista mostra subito i brani.
        assertEquals(listOf("Hakuna Matata", "Il cerchio della vita"), page("Ivana Spagna", disney).titles().sorted())
        assertTrue(page("Ivana Spagna", disney).all { it.kind == Kind.TRACK })
    }

    @Test
    fun `gli sparsi sono per lettera dell'artista`() {
        val all = listOf(
            file("Volare", "Il Volo", "Volare", 1),
            file("Help", "The Beatles", "Help", 2),
            file("Hey", "883", "Hey", 3),
        )
        assertEquals(listOf("883" to "#", "The Beatles" to "B", "Il Volo" to "V"), children(LocalCatalog.SINGLES, all).map { it.artist to it.group })
    }

    @Test
    fun `album omonimi di artisti diversi restano separati`() {
        val all = listOf(
            picard("Bohemian Rhapsody", "Queen", "Greatest Hits", 1, track = 1, year = 1981),
            picard("Another One Bites the Dust", "Queen", "Greatest Hits", 1, track = 2, year = 1981),
            picard("Dancing Queen", "ABBA", "Greatest Hits", 1, track = 1, year = 1975),
            picard("Mamma Mia", "ABBA", "Greatest Hits", 1, track = 2, year = 1975),
        )
        val albums = children(LocalCatalog.ALBUMS, all)
        assertEquals(listOf("ABBA", "Queen"), albums.filter { it.title == "Greatest Hits" }.mapNotNull { it.subtitle }.sorted())
        val queen = albums.first { it.subtitle == "Queen" }
        assertEquals(listOf("Bohemian Rhapsody", "Another One Bites the Dust"), children(LocalCatalog.ALBUM, all, idOf(queen)).titles())
    }

    @Test
    fun `senza artista dell'album valgono gli album di Android`() {
        val all = listOf(
            file("Uno", "Queen", "Greatest Hits", 10, track = 1),
            file("Due", "Queen", "Greatest Hits", 10, track = 2),
            file("Tre", "ABBA", "Greatest Hits", 11, track = 1),
            file("Quattro", "ABBA", "Greatest Hits", 11, track = 2),
        )
        val albums = children(LocalCatalog.ALBUMS, all)
        assertEquals(listOf("ABBA", "Queen"), albums.mapNotNull { it.subtitle }.sorted())
    }

    @Test
    fun `un disco segnato come raccolta da MusicBrainz e di Artisti vari anche senza il flag`() {
        val all = listOf(
            picard("Hakuna Matata", "Ivana Spagna", "Disney Hits", 1, albumArtist = "Various Artists", albumArtistMbids = listOf(ArtistNames.VARIOUS_MBID), track = 1),
            picard("Il cerchio della vita", "Ivana Spagna", "Disney Hits", 1, albumArtist = "Various Artists", albumArtistMbids = listOf(ArtistNames.VARIOUS_MBID), track = 2),
        )
        val album = children(LocalCatalog.ALBUMS, all).single()
        assertEquals("Artisti vari", album.subtitle)
        assertNull(album.artist)
        // Il suo artista vero ha il disco in "Compare in", e "Various Artists" non è un artista.
        assertEquals(listOf("Ivana Spagna"), children(LocalCatalog.ARTISTS, all).titles())
        assertEquals(listOf("Compare in"), page("Ivana Spagna", all).titles())
    }

    @Test
    fun `un album con artista dell'album Artisti vari scritto a mano e una raccolta`() {
        val all = listOf(
            picard("A", "Uno", "Estate 2003", 1, albumArtist = "Artisti Vari", track = 1),
            picard("B", "Due", "Estate 2003", 1, albumArtist = "Artisti Vari", track = 2),
        )
        assertEquals("Artisti vari", children(LocalCatalog.ALBUMS, all).single().subtitle)
        assertTrue(children(LocalCatalog.ARTISTS, all).none { ArtistNames.isVarious(it.title) })
    }

    // --- Artisti ---

    @Test
    fun `A feat B senza tag e di A e compare da B`() {
        val all = listOf(
            file("Let Me Go", "Avril Lavigne feat. Chad Kroeger", "Avril Lavigne", 1, track = 1),
            file("Here's to Never Growing Up", "Avril Lavigne", "Avril Lavigne", 1, track = 2),
        )
        assertEquals(listOf("Avril Lavigne", "Chad Kroeger"), children(LocalCatalog.ARTISTS, all).titles())
        // L'album è di Avril (i due brani hanno lei come primo artista), non di artisti vari.
        assertEquals("Avril Lavigne", children(LocalCatalog.ALBUMS, all).single().subtitle)
        assertEquals(listOf("Compare in"), page("Chad Kroeger", all).titles())
        val song = children(LocalCatalog.SONGS, all).first { it.title == "Let Me Go" }
        assertEquals(artist("Avril Lavigne", all).mediaId, song.artistId)
    }

    @Test
    fun `Simon e Garfunkel e AC-DC restano un artista solo anche senza tag`() {
        val all = listOf(
            file("The Sound of Silence", "Simon & Garfunkel", "Sounds of Silence", 1, track = 1),
            file("Mrs. Robinson", "Simon & Garfunkel", "Bookends", 2),
            file("Back in Black", "AC/DC", "Back in Black", 3),
            file("Hells Bells", "AC/DC", "Back in Black", 3),
        )
        assertEquals(listOf("AC/DC", "Simon & Garfunkel"), children(LocalCatalog.ARTISTS, all).titles())
        assertEquals("1 album · 2 brani", artist("AC/DC", all).subtitle)
    }

    @Test
    fun `due artisti con lo stesso nome ma ID MusicBrainz diversi sono due`() {
        val all = listOf(
            picard("Smells Like Teen Spirit", "Nirvana", "Nevermind", 1, artistMbids = listOf("mb-us"), albumArtistMbids = listOf("mb-us")),
            picard("Rainbow Chaser", "Nirvana", "The Story of Simon Simopath", 2, artistMbids = listOf("mb-uk"), albumArtistMbids = listOf("mb-uk")),
        )
        val nirvana = children(LocalCatalog.ARTISTS, all).filter { it.title == "Nirvana" }
        assertEquals(2, nirvana.size)
        assertEquals(setOf("mb:mb-us", "mb:mb-uk"), nirvana.map(::idOf).toSet())
    }

    @Test
    fun `un file senza ID si attacca all'artista col suo ID se il nome e lo stesso`() {
        val all = listOf(
            picard("Innuendo", "Queen", "Innuendo", 1, artistMbids = listOf("mb-queen"), albumArtistMbids = listOf("mb-queen"), track = 1),
            file("Radio Ga Ga", "queen", "The Works", 2),
        )
        val queen = children(LocalCatalog.ARTISTS, all).single()
        assertEquals("mb:mb-queen", idOf(queen))
        assertEquals("2 brani", queen.subtitle)
    }

    @Test
    fun `i nomi di ordinamento di Picard decidono lettera e ordine`() {
        val all = listOf(
            picard("La cura", "Franco Battiato", "Fleurs", 1, artistSort = "Battiato, Franco", albumArtistSort = "Battiato, Franco"),
            picard("Vieni a ballare in Puglia", "Caparezza", "Le dimensioni del mio caos", 2),
            picard("Albachiara", "Vasco Rossi", "Non siamo mica gli americani", 3, artistSort = "Rossi, Vasco"),
        )
        assertEquals(listOf("Franco Battiato" to "B", "Caparezza" to "C", "Vasco Rossi" to "R"), children(LocalCatalog.ARTISTS, all).map { it.title to it.group })
    }

    @Test
    fun `brani e album per lettera senza articoli e accenti, i numeri sotto il cancelletto`() {
        val all = listOf(
            file("L'Amour Toujours", "Gigi D'Agostino", "L'Amour Toujours", 1),
            file("È la mia vita", "Al Bano", "È la mia vita", 2),
            file("99 Luftballons", "Nena", "99 Luftballons", 3),
            file("The Final Countdown", "Europe", "The Final Countdown", 4),
            file("Zombie", "The Cranberries", "Zombie", 5),
        )
        assertEquals(
            listOf("99 Luftballons" to "#", "L'Amour Toujours" to "A", "È la mia vita" to "E", "The Final Countdown" to "F", "Zombie" to "Z"),
            children(LocalCatalog.SONGS, all).map { it.title to it.group },
        )
    }

    @Test
    fun `I tuoi piu ascoltati compaiono da tre artisti ascoltati, non con due`() {
        val all = listOf(file("A", "Queen", "A", 1), file("B", "Muse", "B", 2), file("C", "Tool", "C", 3), file("D", "Korn", "D", 4))
        fun withPlays(vararg n: Int) = LocalCatalog(streamUri = { "x" }, plays = { all.take(n.size).withIndex().associate { (i, t) -> LocalCatalog.ref(t).id to n[i] } })
        val two = withPlays(5, 3).children(MediaId(LocalCatalog.KEY, LocalCatalog.ARTISTS), all)
        assertTrue(two.none { it.title == "I tuoi più ascoltati" })
        val threeCatalog = withPlays(1, 5, 3)
        val three = threeCatalog.children(MediaId(LocalCatalog.KEY, LocalCatalog.ARTISTS), all)
        assertEquals("I tuoi più ascoltati", three.first().title)
        assertEquals("Muse, Tool, Queen", three.first().subtitle)
        // Dentro, dal più ascoltato: niente ordine alfabetico.
        assertEquals(listOf("Muse", "Tool", "Queen"), threeCatalog.children(MediaId.parse(three.first().mediaId)!!, all).titles())
    }

    // --- Discografia e dischi ---

    @Test
    fun `discografia con EP, colonne sonore e compilation, a pari anno per titolo`() {
        fun disc(title: String, album: String, albumId: Long, year: Int, types: List<String>) =
            picard(title, "Hans Zimmer", album, albumId, year = year, types = types)
        val all = listOf(
            disc("a", "Gladiator", 1, 2000, listOf("album", "soundtrack")), disc("b", "Gladiator", 1, 2000, listOf("album", "soundtrack")),
            disc("c", "Inception", 2, 2010, listOf("album", "soundtrack")), disc("d", "Inception", 2, 2010, listOf("album", "soundtrack")),
            disc("e", "The Essential", 3, 2015, listOf("album", "compilation")), disc("f", "The Essential", 3, 2015, listOf("album", "compilation")),
            disc("g", "Zeta EP", 4, 2012, listOf("ep")), disc("h", "Zeta EP", 4, 2012, listOf("ep")),
            disc("i", "Alfa EP", 5, 2012, listOf("ep")), disc("j", "Alfa EP", 5, 2012, listOf("ep")),
        )
        assertEquals(
            listOf("Alfa EP" to "EP", "Zeta EP" to "EP", "Inception" to "Colonne sonore", "Gladiator" to "Colonne sonore", "The Essential" to "Compilation"),
            page("Hans Zimmer", all).map { it.title to it.group },
        )
    }

    @Test
    fun `un album su un disco solo non ha intestazioni dei dischi`() {
        val all = listOf(
            picard("Uno", "Muse", "Drones", 1, disc = 1, track = 1),
            picard("Due", "Muse", "Drones", 1, disc = 1, track = 2),
        )
        val album = children(LocalCatalog.ALBUMS, all).single()
        assertTrue(children(LocalCatalog.ALBUM, all, idOf(album)).all { it.group == null })
        assertEquals("Disco 3", LocalCatalog.discLabel(3, "  "))
    }

    @Test
    fun `l'anno di un album e la prima uscita del brano piu recente`() {
        val all = listOf(
            picard("Uno", "Queen", "News of the World", 1, track = 1, year = 2011, originalYear = 1977),
            picard("Due", "Queen", "News of the World", 1, track = 2, year = 1977),
        )
        assertEquals(1977, children(LocalCatalog.ALBUMS, all).single().year)
        assertEquals(listOf("Anni 70"), children(LocalCatalog.DECADES, all).titles())
    }

    // --- Anni e decenni ---

    @Test
    fun `decenni dal piu recente, senza i brani senza anno`() {
        val all = listOf(
            file("a", "x", "a", 1, year = 2015), file("b", "x", "b", 2, year = 1965),
            file("c", "x", "c", 3, year = 0), file("d", "x", "d", 4, year = 2000),
        )
        val decades = children(LocalCatalog.DECADES, all)
        assertEquals(listOf("Anni 2010", "Anni 2000", "Anni 60"), decades.titles())
        assertEquals(listOf("1 brano", "1 brano", "1 brano"), decades.map { it.subtitle })
        assertEquals(emptyList(), children(LocalCatalog.DECADE, all, "nessuno"))
    }

    @Test
    fun `il 1970 si toglie solo da trenta brani e uno su cinque`() {
        fun epoch(n: Int) = (1..n).map { file("Finto $it", "A$it", "F$it", 1000L + it, year = 1970) }
        fun real(n: Int) = (1..n).map { file("Vero $it", "B$it", "V$it", 2000L + it, year = 2005) }
        // 29 brani del 1970: troppo pochi per dire che è un segnaposto.
        assertTrue(LocalCatalog.withoutPlaceholderYears(epoch(29)).all { it.year == 1970 })
        // 30 su 150 datati: esattamente uno su cinque, si toglie.
        assertTrue(LocalCatalog.withoutPlaceholderYears(epoch(30) + real(120)).take(30).all { it.shownYear == 0 })
        // 30 su 151: meno di uno su cinque, restano.
        assertTrue(LocalCatalog.withoutPlaceholderYears(epoch(30) + real(121)).take(30).all { it.year == 1970 })
    }

    @Test
    fun `il 1970 come prima uscita si toglie e resta l'anno vero`() {
        val fake = (1..40).map { file("x$it", "a", "b", 1, year = 2010).copy(originalYear = 1970) }
        val cleaned = LocalCatalog.withoutPlaceholderYears(fake)
        assertTrue(cleaned.all { it.originalYear == 0 && it.year == 2010 && it.shownYear == 2010 })
    }

    // --- Raccolte dalle cartelle ---

    @Test
    fun `la prima cartella dentro quella principale e la raccolta`() {
        assertEquals("Music/Disney Italia", LocalCatalog.collectionFolder("Music/Disney Italia"))
        assertEquals("Music/Movie Soundtracks", LocalCatalog.collectionFolder("Music/Movie Soundtracks/CD1"))
        assertEquals("Music/Disney", LocalCatalog.collectionFolder("/Music/Disney/"))
        assertEquals("Download/Telegram", LocalCatalog.collectionFolder("Download/Telegram"))
        assertNull(LocalCatalog.collectionFolder("Music"))
        assertNull(LocalCatalog.collectionFolder(""))
        assertNull(LocalCatalog.collectionFolder("Android/media/com.whatsapp"))
    }

    @Test
    fun `nella raccolta a pari arrivo vale l'ordine di disco e traccia`() {
        val all = listOf(
            file("Terzo", "x", "S", 1, disc = 2, track = 1, added = 7, folder = "Music/Movie Soundtracks/CD2"),
            file("Secondo", "x", "S", 1, disc = 1, track = 2, added = 7, folder = "Music/Movie Soundtracks/CD1"),
            file("Primo", "x", "S", 1, disc = 1, track = 1, added = 7, folder = "Music/Movie Soundtracks/CD1"),
        )
        val c = LocalCatalog.folderCollections(all).single()
        assertEquals("Movie Soundtracks", c.name)
        assertEquals(listOf("Primo", "Secondo", "Terzo"), c.tracks.map { it.title })
    }

    // --- Radio e mix ---

    @Test
    fun `il mix di un artista non prende il posto della radio di un brano`() {
        // "negramaro".hashCode() è negativo: la chiave del mix coincideva con l'ID di questo brano.
        val clash = -"negramaro".hashCode().toLong() - 1
        val all = listOf(
            file("Estate", "Negramaro", "Mentre tutto scorre", 1, year = 2005, folder = "Music/Negramaro"),
            file("Solo3min", "Negramaro", "Mentre tutto scorre", 1, year = 2005, folder = "Music/Negramaro"),
            file("Nuvole e lenzuola", "Negramaro", "La finestra", 2, year = 2007, folder = "Music/Negramaro"),
            file("Altro brano", "Altro", "Altro", 3, year = 1980, folder = "Download", id = clash),
        )
        children(LocalCatalog.ARTIST_MIX, all, "Negramaro")
        val radio = children(LocalCatalog.RADIO, all, "$clash")
        assertEquals("Altro brano", radio.first().title)
        assertEquals(all.size, radio.size)
    }

    @Test
    fun `radio e mix casuale non ripropongono i file non piu sul telefono`() {
        val all = (1..12).map { file("Brano $it", "Artista ${it % 3}", "Album $it", it.toLong(), year = 2000 + it, folder = "Music/Mix") }
        val radio = children(LocalCatalog.RADIO, all, "${all[0].id}")
        val trashed = all.first { it.id != all[0].id && radio.any { n -> n.title == it.title } }
        val after = all - trashed
        val again = children(LocalCatalog.RADIO, after, "${all[0].id}")
        assertTrue(again.none { it.title == trashed.title })
        // Il resto della radio non cambia: la coda si ricostruisce uguale.
        assertEquals(radio.filterNot { it.title == trashed.title }.titles(), again.titles())

        children(LocalCatalog.SHUFFLE, all)
        assertTrue(children(LocalCatalog.SHUFFLE, after, "last").none { it.title == trashed.title })
    }
}
