package io.github.baldborne94.baldmusic.core

import io.github.baldborne94.baldmusic.core.library.ArtistNames
import io.github.baldborne94.baldmusic.core.library.FileTags
import io.github.baldborne94.baldmusic.core.library.FolderCollection
import io.github.baldborne94.baldmusic.core.library.LocalCatalog
import io.github.baldborne94.baldmusic.core.library.LocalTrack
import io.github.baldborne94.baldmusic.core.library.MediaId
import io.github.baldborne94.baldmusic.core.library.SongsCache
import io.github.baldborne94.baldmusic.core.library.TrackRef
import io.github.baldborne94.baldmusic.core.library.UserLibrary
import java.text.Normalizer
import java.util.Locale
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Librerie grandi (20.000 brani, mille artisti): i tempi hanno margini larghi, per non
 * dipendere dal computer della CI, ma prima delle correzioni erano 10–100 volte più lenti.
 */
class LargeLibraryTest {

    private fun library(n: Int, seed: Int = 1): List<LocalTrack> {
        val r = Random(seed)
        val artists = (0 until 1000).map { "${listOf("The ", "", "Il ").random(r)}Artista $it" }
        return (0 until n).map { i ->
            val a = r.nextInt(artists.size)
            val album = r.nextInt(3000)
            val folder = if (r.nextInt(10) < 7) "Music/Raccolta ${r.nextInt(60)}" else "Download"
            val artist = artists[a] + if (r.nextInt(8) == 0) " feat. ${artists[r.nextInt(artists.size)]}" else ""
            val base = LocalTrack(i + 1L, "Titolo $i", artist, a.toLong(), "Album $album", album.toLong(), 120_000L + r.nextInt(300_000), 1, r.nextInt(14), 1960 + r.nextInt(65), r.nextLong(1_000_000), folder)
            if (r.nextBoolean()) base else base.withTags(
                FileTags(
                    title = base.title, artist = artist, artists = listOf(artists[a]), album = base.album, albumArtist = artists[a],
                    artistMbids = listOf("mb-$a"), albumArtistMbids = listOf("mb-$a"), releaseGroupMbid = "rg-$album",
                    releaseTypes = listOf("album"), track = r.nextInt(14), year = base.year,
                ),
            )
        }
    }

    private inline fun ms(block: () -> Unit): Long {
        val start = System.nanoTime()
        block()
        return (System.nanoTime() - start) / 1_000_000
    }

    private fun catalog(plays: () -> Map<String, Int> = { emptyMap() }) =
        LocalCatalog(streamUri = { "content://audio/$it" }, plays = plays, random = Random(1))

    @Test
    fun `artisti, album e brani di 20000 brani si aprono in fretta`() {
        val all = library(20_000)
        val cat = catalog()
        cat.children(MediaId(LocalCatalog.KEY, LocalCatalog.ALBUMS), all) // l'indice si costruisce qui
        // Prima una pagina per artista la rifaceva scorrendo tutti i brani: oltre 5 s.
        val artists = ms { cat.children(MediaId(LocalCatalog.KEY, LocalCatalog.ARTISTS), all) }
        assertTrue(artists < 2_000, "Artisti: $artists ms")
        val search = ms { cat.search("titolo 1234", all) }
        assertTrue(search < 1_000, "Ricerca: $search ms")
    }

    @Test
    fun `un elenco gia calcolato per gli stessi brani non si rifa`() {
        val all = library(2_000)
        val cat = catalog()
        listOf(LocalCatalog.ALBUMS, LocalCatalog.SONGS, LocalCatalog.SINGLES, LocalCatalog.DECADES, LocalCatalog.FOLDERS, LocalCatalog.RECENT).forEach { kind ->
            val id = MediaId(LocalCatalog.KEY, kind)
            assertSame(cat.children(id, all), cat.children(id, all), kind)
        }
        // Brani diversi: elenchi nuovi.
        val fewer = all.drop(1)
        val songs = cat.children(MediaId(LocalCatalog.KEY, LocalCatalog.SONGS), fewer)
        assertEquals(fewer.size, songs.size)
    }

    @Test
    fun `gli artisti piu ascoltati cambiano con gli ascolti anche se i brani sono gli stessi`() {
        val all = library(500)
        var plays = emptyMap<String, Int>()
        val cat = catalog { plays }
        val id = MediaId(LocalCatalog.KEY, LocalCatalog.ARTISTS)
        assertTrue(cat.children(id, all).none { it.mediaId.endsWith(LocalCatalog.TOP_ARTISTS) })
        plays = all.take(30).associate { LocalCatalog.ref(it).id to 5 }
        assertTrue(cat.children(id, all).first().mediaId.endsWith(LocalCatalog.TOP_ARTISTS))
    }

    @Test
    fun `un brano si ritrova per ID`() {
        val all = library(1_000)
        val cat = catalog()
        assertEquals("Titolo 41", cat.track(42, all)?.title)
        assertNull(cat.track(99_999, all))
    }

    @Test
    fun `il cuore di migliaia di righe non scorre i preferiti ogni volta`() {
        val refs = (1..5_000).map { TrackRef("local/track/$it", "T$it") }
        var lib = UserLibrary(liked = refs)
        val time = ms { assertEquals(5_000, refs.count { lib.isLiked(it.id + "|me/liked") }) }
        assertTrue(time < 1_000, "isLiked: $time ms")
        // Il calcolo veloce segue la libreria: toglierne uno lo toglie.
        lib = lib.toggleLike(refs[0])
        assertTrue(!lib.isLiked(refs[0].id) && lib.isLiked(refs[1].id))
    }

    @Test
    fun `una cartella grande spostata con ID nuovi si ritrova in fretta`() {
        val tracks = (1..3_000).map { TrackRef("local/track/$it", "Brano numero $it", durationMs = 180_000L + it) }
        val lib = UserLibrary().syncFolders(listOf(FolderCollection("Music/Tutto", "Tutto", tracks)), tracks.mapTo(HashSet()) { it.id }, 1) { "c" }
        val moved = tracks.mapIndexed { i, t -> t.copy(id = "local/track/${100_000 + i}") }
        var after: UserLibrary? = null
        val time = ms {
            after = lib.syncFolders(listOf(FolderCollection("Music/Tutto nuovo", "Tutto nuovo", moved)), moved.mapTo(HashSet()) { it.id }, 2) { "x" }
        }
        assertTrue(time < 2_000, "Cartella spostata: $time ms")
        assertEquals(moved.map { it.id }, after!!.playlists.single().tracks.map { it.id })
    }

    /** Com'era la chiave dei nomi prima della scorciatoia per i nomi senza accenti. */
    private fun referenceKey(name: String) = Normalizer.normalize(name, Normalizer.Form.NFKD)
        .replace(Regex("\\p{M}+"), "")
        .replace('’', '\'').replace('‘', '\'').replace('´', '\'').replace('`', '\'')
        .lowercase(Locale.ROOT)
        .replace(Regex("\\s+"), " ")
        .trim()

    @Test
    fun `la chiave dei nomi resta quella di prima`() {
        val names = listOf(
            "  The   Beatles\t", "Beyoncé", "Café Tacvba", "Sinéad O’Connor", "L`Aura", "AC/DC", "Ñandú", "ÉRIC", "Ⅻ Roman", "ﬁne", "Mötley Crüe",
            "ROCK  \n ROLL", "", " ", "Björk", "Guns N' Roses", "Ennio Morricone", "Ludovico Einaudi", "日本語", "Zoë",
        )
        names.forEach { assertEquals(referenceKey(it), ArtistNames.key(it), it) }
    }
}

class SongsCacheTest {
    private var now = 0L
    private val cache = SongsCache<Int>(maxAgeMs = 1_000) { now }

    @Test
    fun `senza cambiamenti si tiene l'elenco letto`() {
        assertNull(cache.fresh())
        cache.reading()
        val first = cache.store(listOf(1, 2, 3))
        now = 999
        assertSame(first, cache.fresh())
        now = 1_000
        assertNull(cache.fresh())
    }

    @Test
    fun `i file cambiati fanno rileggere, e gli stessi brani danno lo stesso elenco`() {
        cache.reading()
        val first = cache.store(listOf(1, 2, 3))
        cache.changed()
        assertNull(cache.fresh())
        cache.reading()
        assertSame(first, cache.store(listOf(1, 2, 3)))
        assertSame(first, cache.fresh())
        cache.changed()
        cache.reading()
        assertEquals(listOf(1, 2), cache.store(listOf(1, 2)))
    }

    @Test
    fun `un cambiamento durante la lettura fa rileggere la volta dopo`() {
        cache.reading()
        cache.changed() // arriva mentre si legge
        cache.store(listOf(1))
        assertNull(cache.fresh())
        assertNotNull(cache.store(listOf(1)))
    }
}
