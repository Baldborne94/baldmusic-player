package io.github.baldborne94.baldmusic.core

import io.github.baldborne94.baldmusic.core.acoustid.Recognition
import io.github.baldborne94.baldmusic.core.library.ContainerRef
import io.github.baldborne94.baldmusic.core.library.FileTags
import io.github.baldborne94.baldmusic.core.library.FolderCollection
import io.github.baldborne94.baldmusic.core.library.LibraryNode
import io.github.baldborne94.baldmusic.core.library.LibraryNode.Kind
import io.github.baldborne94.baldmusic.core.library.LocalCatalog
import io.github.baldborne94.baldmusic.core.library.LocalTrack
import io.github.baldborne94.baldmusic.core.library.MediaId
import io.github.baldborne94.baldmusic.core.library.PersonalSource
import io.github.baldborne94.baldmusic.core.library.PlayCount
import io.github.baldborne94.baldmusic.core.library.TrackRef
import io.github.baldborne94.baldmusic.core.library.UserLibrary
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun song(id: Int, title: String = "Brano $id", duration: Long = 200_000L + id * 1000) =
    TrackRef("local/track/$id", title, artist = "Artista", artwork = "local/1", durationMs = duration)

private fun folder(path: String, vararg tracks: TrackRef) = FolderCollection(path, path.substringAfterLast('/'), tracks.toList())

/** Raccolte che seguono le cartelle rinominate o spostate. */
class MovedFoldersTest {
    private var next = 0
    private fun sync(lib: UserLibrary, vararg folders: FolderCollection) =
        lib.syncFolders(folders.toList(), folders.flatMap { f -> f.tracks.map { it.id } }.toSet(), 1) { "c${next++}" }

    private val disney = folder("Music/Disney", song(1), song(2), song(3))

    @Test
    fun `gli ascoltati di recente prendono il nome nuovo della raccolta e una raccolta eliminata esce`() = runTest {
        var lib = sync(UserLibrary(), disney, folder("Music/Metal Mix", song(5), song(6)))
        val disneyId = lib.playlists.first { it.folder == "Music/Disney" }.id
        val metalId = lib.playlists.first { it.folder == "Music/Metal Mix" }.id
        // Come li salva il servizio quando ascolti: titolo e sottotitolo di quel momento.
        fun heard(id: String, name: String) =
            ContainerRef(MediaId(PersonalSource.KEY, PersonalSource.PLAYLIST, id).toString(), name, "Raccolta · 3 brani", kind = Kind.PLAYLIST.name)
        lib = lib.recordPlay(song(5), heard(metalId, "Metal Mix"), 1).recordPlay(song(1), heard(disneyId, "Disney"), 2)
        // Le cartelle riordinate sul telefono: Disney rinominata, Metal Mix rinominata e poi la raccolta eliminata.
        lib = sync(lib, folder("Music/Anime e cartoni - Disney", song(1), song(2), song(3)), folder("Music/Metal", song(5), song(6)))
        lib = lib.deletePlaylist(metalId).copy(recent = lib.recent)
        val source = PersonalSource(library = { lib })
        val recent = source.children(MediaId(PersonalSource.KEY, PersonalSource.RECENT))
        assertEquals(listOf("Anime e cartoni - Disney"), recent.map { it.title })
        assertEquals("Raccolta · 3 brani", recent.single().subtitle)
        // Un album o un artista resta com'era.
        val album = ContainerRef("local/album/x", "Meteora", kind = Kind.ALBUM.name)
        lib = lib.recordPlay(song(9), album, 3)
        assertEquals(listOf("Meteora", "Anime e cartoni - Disney"), source.children(MediaId(PersonalSource.KEY, PersonalSource.RECENT)).map { it.title })
        // La pagina si rilegge quando cambia il nome di una raccolta, anche se gli ascolti sono gli stessi.
        val page = "${PersonalSource.KEY}/${PersonalSource.RECENT}"
        assertNotEquals(PersonalSource.shownBy(lib, page), PersonalSource.shownBy(lib.renamePlaylist(disneyId, "Cartoni"), page))
    }

    @Test
    fun `una cartella rinominata si porta dietro la sua raccolta`() {
        val before = sync(UserLibrary(), disney)
        // Lo stesso ID: Android rinomina la cartella senza toccare i file.
        val after = sync(before, folder("Music/Disney Italia", song(1), song(2), song(3)))
        val pl = after.playlists.single()
        assertEquals(before.playlists.single().id, pl.id)
        assertEquals("Music/Disney Italia", pl.folder)
        assertEquals("Disney Italia", pl.name)
        assertEquals(listOf("local/track/1", "local/track/2", "local/track/3"), pl.tracks.map { it.id })
    }

    @Test
    fun `l'ordine, i brani tolti e un nome cambiato a mano restano`() {
        var lib = sync(UserLibrary(), disney)
        val id = lib.playlists.single().id
        lib = lib.reorderPlaylist(id, listOf("local/track/3", "local/track/1", "local/track/2"))
            .renamePlaylist(id, "Cartoni")
            .removeFromPlaylist(id, "local/track/2")
        val after = sync(lib, folder("Music/Film/Disney", song(1), song(2), song(3), song(4)))
        val pl = after.playlists.single()
        assertEquals("Cartoni", pl.name)
        assertEquals("Music/Film/Disney", pl.folder)
        // Il 2 era stato tolto a mano e non torna; il 4 è nuovo ed entra in fondo.
        assertEquals(listOf("local/track/3", "local/track/1", "local/track/4"), pl.tracks.map { it.id })
    }

    @Test
    fun `con ID nuovi i brani si ritrovano per titolo e durata, anche nei preferiti e negli ascolti`() {
        var lib = sync(UserLibrary(), disney)
        lib = lib.toggleLike(song(2)).recordPlay(song(3), null, 5)
        // Copia e cancella: i file sono gli stessi, ma Android dà loro ID nuovi.
        val moved = folder(
            "Music/Disney Italia",
            song(11, "Brano 1", duration = song(1).durationMs!! + 500),
            song(12, "brano 2", duration = song(2).durationMs!!),
            song(13, "Brano 3", duration = song(3).durationMs!! - 1500),
        )
        val after = sync(lib, moved)
        val pl = after.playlists.single()
        assertEquals(lib.playlists.single().id, pl.id)
        assertEquals(listOf("local/track/11", "local/track/12", "local/track/13"), pl.tracks.map { it.id })
        assertEquals(listOf("local/track/12"), after.liked.map { it.id })
        assertEquals(1, after.plays["local/track/13"]?.count)
        assertNull(after.plays["local/track/3"])
    }

    @Test
    fun `una cartella nuova con meno di meta dei brani e una raccolta a parte`() {
        val lib = sync(UserLibrary(), disney)
        val other = folder("Music/Pixar", song(1), song(20), song(21), song(22))
        // La vecchia cartella non c'è più, ma la nuova ha un solo brano su tre: non è la stessa.
        val after = sync(lib, other)
        assertEquals(2, after.playlists.size)
        assertEquals(setOf("Music/Disney", "Music/Pixar"), after.playlists.mapNotNull { it.folder }.toSet())
    }

    @Test
    fun `stesso titolo ma durata diversa non e lo stesso file`() {
        val lib = sync(UserLibrary(), folder("Music/A", song(1), song(2)))
        val after = sync(lib, folder("Music/B", song(31, "Brano 1", duration = 999_000), song(32, "Brano 2", duration = 1_000)))
        assertEquals(setOf("Music/A", "Music/B"), after.playlists.mapNotNull { it.folder }.toSet())
    }

    @Test
    fun `se la vecchia cartella c'e ancora non si sposta niente`() {
        val lib = sync(UserLibrary(), disney)
        val copy = folder("Music/Disney copia", song(1), song(2), song(3))
        val after = sync(lib, disney, copy)
        assertEquals(setOf("Music/Disney", "Music/Disney copia"), after.playlists.mapNotNull { it.folder }.toSet())
    }

    @Test
    fun `una raccolta eliminata non torna quando si rinomina un'altra cartella`() {
        var lib = sync(UserLibrary(), disney, folder("Music/Rock", song(5), song(6)))
        lib = lib.deletePlaylist(lib.playlists.first { it.folder == "Music/Rock" }.id)
        val after = sync(lib, folder("Music/Disney Italia", song(1), song(2), song(3)), folder("Music/Rock", song(5), song(6)))
        assertEquals(listOf("Music/Disney Italia"), after.playlists.mapNotNull { it.folder })
    }

    @Test
    fun `due cartelle spostate insieme vanno ognuna alla sua raccolta`() {
        val rock = folder("Music/Rock", song(5), song(6))
        val lib = sync(UserLibrary(), disney, rock)
        val after = sync(lib, folder("Music/Old/Rock", song(5), song(6)), folder("Music/Old/Disney", song(1), song(2), song(3)))
        assertEquals(2, after.playlists.size)
        val byFolder = after.playlists.associate { it.folder to it.tracks.map { t -> t.id } }
        assertEquals(listOf("local/track/5", "local/track/6"), byFolder["Music/Old/Rock"])
        assertEquals(listOf("local/track/1", "local/track/2", "local/track/3"), byFolder["Music/Old/Disney"])
    }

    @Test
    fun `senza cartelle spostate syncFolders da la stessa istanza`() {
        val lib = sync(UserLibrary(), disney)
        assertTrue(lib === sync(lib, disney))
    }
}

/** I mix della libreria: gli stessi in Home e in Android Auto. */
class MixesTest {
    private val day = 24L * 3600 * 1000
    private val today = 400 * day + 5 * 3600 * 1000

    private fun plays(vararg p: Pair<TrackRef, Pair<Int, Long>>) =
        p.associate { (t, cl) -> t.id to PlayCount(t, cl.first, cl.second) }

    private fun source(lib: UserLibrary, now: Long = today, decades: List<LibraryNode> = emptyList()) =
        PersonalSource(now = { now }, decades = { decades }) { lib }

    private suspend fun PersonalSource.mixes() = children(MediaId(PersonalSource.KEY, PersonalSource.MIXES))
    private suspend fun PersonalSource.mix(kind: String) =
        children(MediaId(PersonalSource.KEY, PersonalSource.MIX, kind)).map { MediaId.parse(it.mediaId)!!.copy(parent = null).toString() }

    @Test
    fun `i mix sono una sezione della tua musica`() = runTest {
        val sections = source(UserLibrary()).sections()
        assertTrue(sections.any { it.mediaId == "me/mixes" && it.title == "I tuoi mix" })
    }

    @Test
    fun `con la libreria vuota resta il mix casuale`() = runTest {
        assertEquals(listOf("Mix casuale"), source(UserLibrary()).mixes().map { it.title })
    }

    @Test
    fun `riscopri prende i brani ascoltati piu volte e non sentiti da due mesi`() = runTest {
        val old = today - 90 * day
        val recent = today - 2 * day
        val forgotten = (1..8).map { song(it) to (3 to old) }
        val lib = UserLibrary(
            plays = plays(*forgotten.toTypedArray(), song(50) to (10 to recent), song(51) to (1 to old)),
            liked = listOf(song(60)),
        )
        val ids = source(lib).mix(PersonalSource.REDISCOVER).toSet()
        // I dimenticati, e il preferito mai ascoltato; non quello sentito ieri né quello sentito una volta.
        assertEquals((1..8).map { "local/track/$it" }.toSet() + "local/track/60", ids)
    }

    @Test
    fun `riscopri compare solo con abbastanza brani`() = runTest {
        val old = today - 90 * day
        val lib = UserLibrary(plays = plays(*(1..7).map { song(it) to (3 to old) }.toTypedArray()))
        assertTrue(source(lib).mixes().none { it.title == "Riscopri" })
        val more = UserLibrary(plays = plays(*(1..8).map { song(it) to (3 to old) }.toTypedArray()))
        assertTrue(source(more).mixes().any { it.title == "Riscopri" && it.mediaId == "me/mix/rediscover" })
    }

    @Test
    fun `per la macchina sono i piu ascoltati e i preferiti`() = runTest {
        val lib = UserLibrary(
            plays = plays(*(1..6).map { song(it) to (5 to today) }.toTypedArray(), song(40) to (2 to today)),
            liked = listOf(song(70), song(71)),
        )
        val ids = source(lib).mix(PersonalSource.DRIVE).toSet()
        assertEquals((1..6).map { "local/track/$it" }.toSet() + setOf("local/track/70", "local/track/71"), ids)
    }

    @Test
    fun `l'ordine di un mix resta uguale tutto il giorno e cambia il giorno dopo`() = runTest {
        val lib = UserLibrary(plays = plays(*(1..40).map { song(it) to (5 to today) }.toTypedArray()))
        val morning = source(lib, now = today).mix(PersonalSource.DRIVE)
        val evening = source(lib, now = today + 12 * 3600 * 1000).mix(PersonalSource.DRIVE)
        val tomorrow = source(lib, now = today + day).mix(PersonalSource.DRIVE)
        assertEquals(morning, evening)
        assertNotEquals(morning, tomorrow)
        assertEquals(morning.toSet(), tomorrow.toSet())
    }

    @Test
    fun `un mix per ognuno dei quattro artisti locali piu ascoltati`() = runTest {
        fun by(id: Int, artist: String) = TrackRef("local/track/$id", "T$id", artist = artist)
        val lib = UserLibrary(
            plays = plays(
                by(1, "Queen") to (9 to today), by(2, "queen") to (2 to today),
                by(3, "Dalla") to (8 to today), by(4, "Mina") to (7 to today),
                by(5, "Elisa") to (6 to today), by(6, "Ultimo") to (1 to today),
                // Dal server: il mix d'artista è dei brani del telefono.
                TrackRef("remote/track/9", "R", artist = "Server") to (50 to today),
            ),
        )
        val artistMixes = source(lib).mixes().filter { it.mediaId.startsWith("local/artistmix/") }
        assertEquals(listOf("Mix Queen", "Mix Dalla", "Mix Mina", "Mix Elisa"), artistMixes.map { it.title })
        assertEquals(MediaId(LocalCatalog.KEY, LocalCatalog.ARTIST_MIX, "Queen").toString(), artistMixes.first().mediaId)
    }

    @Test
    fun `i decenni del telefono diventano mix`() = runTest {
        val decades = listOf(LibraryNode("local/decade/1990", "Anni 90", Kind.SECTION, subtitle = "12 brani"))
        val mix = source(UserLibrary(), decades = decades).mixes().last()
        assertEquals("Mix Anni 90", mix.title)
        assertEquals("local/decade/1990", mix.mediaId)
        assertEquals(Kind.PLAYLIST, mix.kind)
    }

    @Test
    fun `un mix si ritrova per ID, per ricostruire la coda`() = runTest {
        val old = today - 90 * day
        val lib = UserLibrary(plays = plays(*(1..10).map { song(it) to (3 to old) }.toTypedArray()))
        val node = source(lib).node(MediaId(PersonalSource.KEY, PersonalSource.MIX, PersonalSource.REDISCOVER))
        assertEquals("Riscopri", node?.title)
        // I brani portano il mix come contenitore.
        val first = source(lib).children(MediaId(PersonalSource.KEY, PersonalSource.MIX, PersonalSource.REDISCOVER)).first()
        assertEquals("me/mix/rediscover", MediaId.parse(first.mediaId)!!.parent.toString())
    }
}

/** Copertine vere da Cover Art Archive per i brani riconosciuti. */
class RecognizedCoversTest {
    private fun local(id: Long, title: String, albumId: Long) =
        LocalTrack(id, title, "linkin park", 1, title, albumId, 200_000, 0, 0, 0, 0, "Music/Download")

    private val tags = { i: Int, title: String ->
        FileTags(
            title = title, artist = "Linkin Park", album = "Meteora", albumArtist = "Linkin Park", track = i,
            year = 2003, releaseGroupMbid = "a1b2c3d4-0000-1111-2222-333344445555", albumMbid = "rel", releaseTypes = listOf("album"),
        )
    }

    @Test
    fun `un brano riconosciuto prende la copertina del suo disco`() {
        val t = Recognition.applyTo(local(1, "numb", 77), tags(1, "Numb"))
        assertEquals("a1b2c3d4-0000-1111-2222-333344445555", t.coverMbid)
        assertEquals("cover/a1b2c3d4-0000-1111-2222-333344445555~77", LocalCatalog.artworkOf(t))
        assertEquals("cover/a1b2c3d4-0000-1111-2222-333344445555~77", LocalCatalog.ref(t).artwork)
    }

    @Test
    fun `un brano non riconosciuto o passato da Picard tiene la copertina del file`() {
        val plain = local(2, "faint", 78)
        assertEquals("local/78", LocalCatalog.artworkOf(plain))
        val picard = plain.withTags(tags(2, "Faint"))
        assertNull(picard.coverMbid)
        assertEquals("local/78", LocalCatalog.artworkOf(picard))
    }

    @Test
    fun `l'album riconosciuto e i suoi brani mostrano la copertina vera`() {
        val tracks = listOf(
            Recognition.applyTo(local(1, "numb", 77), tags(1, "Numb")),
            Recognition.applyTo(local(2, "faint", 78), tags(2, "Faint")),
        )
        val catalog = LocalCatalog(streamUri = { "content://audio/$it" }, random = Random(1))
        val album = catalog.children(MediaId(LocalCatalog.KEY, LocalCatalog.ALBUMS), tracks).single()
        assertTrue(album.artwork!!.startsWith("cover/a1b2c3d4-0000-1111-2222-333344445555~"))
        val songs = catalog.children(MediaId.parse(album.mediaId)!!, tracks)
        assertEquals(listOf("cover/a1b2c3d4-0000-1111-2222-333344445555~77", "cover/a1b2c3d4-0000-1111-2222-333344445555~78"), songs.map { it.artwork })
    }
}
