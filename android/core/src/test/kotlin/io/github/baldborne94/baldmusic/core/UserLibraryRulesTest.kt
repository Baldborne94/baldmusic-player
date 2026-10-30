package io.github.baldborne94.baldmusic.core

import io.github.baldborne94.baldmusic.core.library.ContainerRef
import io.github.baldborne94.baldmusic.core.library.FolderCollection
import io.github.baldborne94.baldmusic.core.library.LibraryCleanup
import io.github.baldborne94.baldmusic.core.library.ListeningStats
import io.github.baldborne94.baldmusic.core.library.LocalCatalog
import io.github.baldborne94.baldmusic.core.library.LocalTrack
import io.github.baldborne94.baldmusic.core.library.PlayCount
import io.github.baldborne94.baldmusic.core.library.TrackRef
import io.github.baldborne94.baldmusic.core.library.UserLibrary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun song(n: Int, title: String = "Brano $n", artist: String? = null, album: String? = null, art: String? = null, ms: Long? = null) =
    TrackRef("local/track/$n", title, artist, album, art, ms)

private fun ids(lib: UserLibrary, playlist: String) = lib.playlists.first { it.id == playlist }.tracks.map { it.id }

class UserLibraryRulesTest {

    // --- Playlist ---

    @Test
    fun `una playlist nuova non ha doppioni, nemmeno dallo stesso brano scelto da contenitori diversi`() {
        val lib = UserLibrary().createPlaylist("p", "Viaggio", 0, listOf(song(1), TrackRef("local/track/1|local/album/7", "Brano 1"), song(2)))
        assertEquals(listOf("local/track/1", "local/track/2"), ids(lib, "p"))
    }

    @Test
    fun `nome vuoto diventa La mia playlist, rinominare con un nome vuoto non cambia niente`() {
        var lib = UserLibrary().createPlaylist("p", "   ", 0)
        assertEquals("La mia playlist", lib.playlists.single().name)
        lib = lib.renamePlaylist("p", "  Estate 2025 ")
        assertEquals("Estate 2025", lib.playlists.single().name)
        assertEquals("Estate 2025", lib.renamePlaylist("p", " ").playlists.single().name)
    }

    @Test
    fun `aggiungere a una playlist che non c'e non cambia niente`() {
        val lib = UserLibrary().createPlaylist("p", "Mia", 0, listOf(song(1)))
        assertEquals(lib, lib.addToPlaylist("altra", listOf(song(2))))
    }

    @Test
    fun `togliere da una playlist normale non segna il brano come tolto`() {
        val lib = UserLibrary().createPlaylist("p", "Mia", 0, listOf(song(1), song(2))).removeFromPlaylist("p", "local/track/1|me/playlist/p")
        assertEquals(listOf("local/track/2"), ids(lib, "p"))
        assertTrue(lib.playlists.single().removed.isEmpty())
    }

    @Test
    fun `riordinare tocca solo la playlist giusta`() {
        var lib = UserLibrary().createPlaylist("p", "A", 0, listOf(song(1), song(2))).createPlaylist("q", "B", 0, listOf(song(1), song(2)))
        lib = lib.reorderPlaylist("p", listOf("local/track/2", "local/track/1"))
        assertEquals(listOf("local/track/2", "local/track/1"), ids(lib, "p"))
        assertEquals(listOf("local/track/1", "local/track/2"), ids(lib, "q"))
    }

    @Test
    fun `ordinare per artista ignora maiuscole e accenti, poi il titolo`() {
        val lib = UserLibrary().createPlaylist(
            "p", "Mia", 0,
            listOf(song(1, "Zeta", "elisa"), song(2, "Alfa", "Èlia"), song(3, "Beta", "Elisa"), song(4, "Gamma", null)),
        ).sortPlaylist("p", byArtist = true)
        // Senza artista in cima; "Èlia" sta con la E.
        assertEquals(listOf("Gamma", "Alfa", "Beta", "Zeta"), lib.playlists.single().tracks.map { it.title })
    }

    @Test
    fun `eliminare una playlist normale non tocca le cartelle ignorate`() {
        val lib = UserLibrary().createPlaylist("p", "Mia", 0).deletePlaylist("p")
        assertTrue(lib.playlists.isEmpty())
        assertTrue(lib.ignoredFolders.isEmpty())
    }

    // --- Mi piace, ascolti, ricerche ---

    @Test
    fun `l'ultimo brano che ti piace va in cima`() {
        val lib = UserLibrary().toggleLike(song(1)).toggleLike(song(2)).toggleLike(TrackRef("local/track/3|local/album/1", "Brano 3"))
        assertEquals(listOf("local/track/3", "local/track/2", "local/track/1"), lib.liked.map { it.id })
    }

    @Test
    fun `un ascolto senza contenitore conta il brano e lascia i recenti come sono`() {
        val a = ContainerRef("local/album/1", "A", kind = "ALBUM")
        var lib = UserLibrary().recordPlay(song(1), a, 1)
        lib = lib.recordPlay(TrackRef("local/track/1|local/album/1", "Brano 1"), null, 5)
        assertEquals(listOf("local/album/1"), lib.recent.map { it.id })
        val p = lib.plays.getValue("local/track/1")
        assertEquals(2, p.count)
        assertEquals(5, p.lastPlayed)
        assertEquals("local/track/1", p.track.id)
    }

    @Test
    fun `i recenti tengono al massimo venti contenitori`() {
        var lib = UserLibrary()
        (1..25).forEach { lib = lib.recordPlay(song(it), ContainerRef("local/album/$it", "A$it", kind = "ALBUM"), it.toLong()) }
        assertEquals(UserLibrary.MAX_RECENT, lib.recent.size)
        assertEquals("local/album/25", lib.recent.first().id)
    }

    @Test
    fun `gli ascolti oltre il massimo tengono i piu recenti`() {
        val many = (1..UserLibrary.MAX_PLAYS).associate { "local/track/$it" to PlayCount(song(it), 1, it.toLong()) }
        val lib = UserLibrary(plays = many).recordPlay(song(99_999), null, 1_000_000)
        assertEquals(UserLibrary.MAX_PLAYS, lib.plays.size)
        assertTrue("local/track/99999" in lib.plays)
        assertFalse("local/track/1" in lib.plays)
    }

    @Test
    fun `a pari ascolti tra i piu ascoltati viene prima il piu recente`() {
        val lib = UserLibrary().recordPlay(song(1), null, 1).recordPlay(song(2), null, 2)
        assertEquals(listOf("local/track/2", "local/track/1"), lib.topTracks().map { it.id })
        assertEquals(1, lib.topTracks(limit = 1).size)
    }

    @Test
    fun `le ricerche si tengono senza spazi, al massimo dieci, l'ultima scrittura vince`() {
        var lib = UserLibrary()
        (1..12).forEach { lib = lib.addSearch("  ricerca $it ") }
        assertEquals(UserLibrary.MAX_SEARCHES, lib.searches.size)
        assertEquals("ricerca 12", lib.searches.first())
        lib = lib.addSearch("RICERCA 5")
        assertEquals("RICERCA 5", lib.searches.first())
        assertEquals(1, lib.searches.count { it.equals("ricerca 5", ignoreCase = true) })
        assertTrue(lib.clearSearches().searches.isEmpty())
    }

    // --- Brani buttati e doppioni ---

    @Test
    fun `dimenticare niente lascia la libreria com'e`() {
        val lib = UserLibrary().toggleLike(song(1))
        assertTrue(lib.forgetTracks(emptyList()) === lib)
    }

    @Test
    fun `dimenticare un brano non tocca quelli del server`() {
        val server = TrackRef("remote/track/1", "Dal server")
        val lib = UserLibrary().toggleLike(server).toggleLike(song(1)).forgetTracks(listOf("local/track/1"))
        assertEquals(listOf("remote/track/1"), lib.liked.map { it.id })
    }

    @Test
    fun `la copia tenuta prende il posto di quella buttata`() {
        val lib = UserLibrary().createPlaylist("p", "Mia", 0, listOf(song(1), song(2), song(3)))
            .mergeTracks(mapOf("local/track/2" to song(9)))
        assertEquals(listOf("local/track/1", "local/track/9", "local/track/3"), ids(lib, "p"))
    }

    @Test
    fun `se la copia tenuta c'era gia resta dov'era, senza doppioni`() {
        val lib = UserLibrary().createPlaylist("p", "Mia", 0, listOf(song(9), song(1), song(2)))
            .mergeTracks(mapOf("local/track/2|me/playlist/p" to song(9)))
        assertEquals(listOf("local/track/9", "local/track/1"), ids(lib, "p"))
    }

    @Test
    fun `gli ascolti delle copie si sommano, con l'ultimo ascolto piu recente`() {
        val lib = UserLibrary()
            .recordPlay(song(2), null, 50).recordPlay(song(2), null, 60)
            .recordPlay(song(9), null, 10)
            .mergeTracks(mapOf("local/track/2" to song(9)))
        val p = lib.plays.getValue("local/track/9")
        assertEquals(3, p.count)
        assertEquals(60, p.lastPlayed)
        assertFalse("local/track/2" in lib.plays)
        // Solo la copia buttata ascoltata: i suoi ascolti passano alla copia tenuta.
        val only = UserLibrary().recordPlay(song(2), null, 5).mergeTracks(mapOf("local/track/2" to song(9, "Tenuta")))
        assertEquals("Tenuta", only.plays.getValue("local/track/9").track.title)
    }

    // --- Raccolte dalle cartelle ---

    private var n = 0
    private fun sync(lib: UserLibrary, folders: List<FolderCollection>, present: Set<String>) = lib.syncFolders(folders, present, now = 1) { "c${n++}" }
    private fun present(vararg ids: Int) = ids.mapTo(HashSet()) { "local/track/$it" }

    @Test
    fun `una cartella vuota non fa una raccolta`() {
        val lib = UserLibrary()
        assertTrue(sync(lib, listOf(FolderCollection("Music/Vuota", "Vuota", emptyList())), emptySet()) === lib)
    }

    @Test
    fun `i brani nuovi della cartella entrano in fondo, nell'ordine della cartella`() {
        var lib = sync(UserLibrary(), listOf(FolderCollection("Music/Disney Italia", "Disney Italia", listOf(song(1)))), present(1))
        val id = lib.collections.single().id
        lib = lib.reorderPlaylist(id, listOf("local/track/1"))
        lib = sync(lib, listOf(FolderCollection("Music/Disney Italia", "Disney Italia", listOf(song(3), song(1), song(2)))), present(1, 2, 3))
        assertEquals(listOf("local/track/1", "local/track/3", "local/track/2"), ids(lib, id))
    }

    @Test
    fun `un brano tolto a mano non torna nemmeno dopo che il file sparisce e ricompare`() {
        var lib = sync(UserLibrary(), listOf(FolderCollection("Music/A", "A", listOf(song(1), song(2)))), present(1, 2))
        val id = lib.collections.single().id
        lib = lib.removeFromPlaylist(id, "local/track/1")
        lib = sync(lib, listOf(FolderCollection("Music/A", "A", listOf(song(2)))), present(2))
        lib = sync(lib, listOf(FolderCollection("Music/A", "A", listOf(song(1), song(2)))), present(1, 2))
        assertEquals(listOf("local/track/2"), ids(lib, id))
    }

    @Test
    fun `un brano buttato e poi ripescato torna nella sua raccolta`() {
        var lib = sync(UserLibrary(), listOf(FolderCollection("Music/A", "A", listOf(song(1), song(2)))), present(1, 2))
        val id = lib.collections.single().id
        lib = lib.forgetTracks(listOf("local/track/1"))
        lib = sync(lib, listOf(FolderCollection("Music/A", "A", listOf(song(2)))), present(2))
        lib = sync(lib, listOf(FolderCollection("Music/A", "A", listOf(song(1), song(2)))), present(1, 2))
        assertEquals(listOf("local/track/2", "local/track/1"), ids(lib, id))
    }

    @Test
    fun `una raccolta svuotata resta, e si riempie coi file che tornano`() {
        var lib = sync(UserLibrary(), listOf(FolderCollection("Music/A", "A", listOf(song(1)))), present(1))
        lib = sync(lib, emptyList(), emptySet())
        assertEquals(1, lib.collections.size)
        assertTrue(lib.collections.single().tracks.isEmpty())
        lib = sync(lib, listOf(FolderCollection("Music/A", "A", listOf(song(4)))), present(4))
        assertEquals(1, lib.collections.size)
        assertEquals(listOf("local/track/4"), lib.collections.single().tracks.map { it.id })
    }

    // --- Ripescati dal cestino ---

    private var next = 1L
    private fun file(title: String, folder: String, ms: Long = 216_000) = LocalTrack(next++, title, "Linkin Park", 1, "Album", 1, ms, 0, 0, 0, 0, folder)

    @Test
    fun `un brano ripescato fuori da una raccolta non cambia niente`() {
        val keep = file("In the End", "Music/Linkin Park")
        val loose = file("In the End", "Music")
        val all = listOf(keep, loose)
        val lib = UserLibrary().syncFolders(LocalCatalog.folderCollections(all), all.mapTo(HashSet()) { LocalCatalog.ref(it).id }, 1) { "c" }
        assertEquals(lib, LibraryCleanup.restoreInto(lib, listOf(loose), all))
    }

    @Test
    fun `un brano ripescato non torna in una raccolta eliminata`() {
        val keep = file("In the End", "Music/Linkin Park")
        val gone = file("In the End", "Music/Metal")
        val all = listOf(keep, gone)
        var lib = UserLibrary().syncFolders(LocalCatalog.folderCollections(all), all.mapTo(HashSet()) { LocalCatalog.ref(it).id }, 1) { "c${next++}" }
        lib = lib.mergeTracks(mapOf(LocalCatalog.ref(gone).id to LocalCatalog.ref(keep))).forgetTracks(listOf(LocalCatalog.ref(gone).id))
        lib = lib.deletePlaylist(lib.collections.first { it.folder == "Music/Metal" }.id)
        val restored = LibraryCleanup.restoreInto(lib, listOf(gone), all)
        assertEquals(listOf("Linkin Park"), restored.collections.map { it.name })
    }

    @Test
    fun `un brano ripescato non sostituisce una versione di durata diversa`() {
        val live = file("In the End", "Music/Live", ms = 300_000)
        val gone = file("In the End", "Music/Metal")
        var lib = UserLibrary().syncFolders(LocalCatalog.folderCollections(listOf(gone)), setOf(LocalCatalog.ref(gone).id), 1) { "m" }
        lib = lib.addToPlaylist("m", listOf(LocalCatalog.ref(live))).forgetTracks(listOf(LocalCatalog.ref(gone).id))
        val restored = LibraryCleanup.restoreInto(lib, listOf(gone), listOf(live, gone))
        assertEquals(listOf(LocalCatalog.ref(live).id), ids(restored, "m"))
    }

    // --- Pulizia: doppioni e anteprime ---

    @Test
    fun `doppioni entro tre secondi esatti, non oltre`() {
        val a = file("Numb", "Music/A", ms = 185_000)
        val b = file("Numb", "Music/B", ms = 188_000)
        val c = file("Numb", "Music/C", ms = 191_001)
        val groups = LibraryCleanup.duplicates(listOf(a, b, c))
        assertEquals(1, groups.size)
        assertEquals(setOf(a.id, b.id), (groups.single().others + groups.single().keep).map { it.id }.toSet())
        assertTrue(LibraryCleanup.sameSong(a, b))
        assertFalse(LibraryCleanup.sameSong(b, c.copy(durationMs = 191_001)))
    }

    @Test
    fun `anteprime da 25 a 35 secondi compresi`() {
        val tracks = listOf(24_999L, 25_000L, 35_000L, 35_001L, 0L).map { file("T$it", "Music/X", ms = it) }
        assertEquals(listOf("T25000", "T35000"), LibraryCleanup.previews(tracks).map { it.title })
        // Un file senza durata non è un doppione di niente.
        val zero = listOf(file("Z", "Music/A", ms = 0), file("Z", "Music/B", ms = 0))
        assertTrue(LibraryCleanup.duplicates(zero).isEmpty())
    }

    @Test
    fun `tra i doppioni vince la copia riconosciuta da MusicBrainz`() {
        val plain = file("Numb", "Music/A").copy(albumArtist = "Linkin Park", year = 2003)
        val tagged = file("Numb", "Music/B").copy(albumMbid = "mb")
        assertEquals(tagged.id, LibraryCleanup.duplicates(listOf(plain, tagged)).single().keep.id)
    }

    // --- Statistiche ---

    @Test
    fun `statistiche di una libreria senza ascolti`() {
        val s = ListeningStats.from(UserLibrary())
        assertEquals(0, s.totalPlays)
        assertEquals(0, s.minutes)
        assertTrue(s.topArtists.isEmpty() && s.topTracks.isEmpty() && s.topAlbums.isEmpty())
        assertNull(s.since)
    }

    @Test
    fun `lo stesso artista scritto in modi diversi e uno solo in classifica`() {
        var lib = UserLibrary()
        repeat(2) { lib = lib.recordPlay(song(1, artist = "Guns N' Roses", album = "Appetite for Destruction"), null, it.toLong()) }
        repeat(2) { lib = lib.recordPlay(song(2, artist = "Guns N’ Roses", album = "Appetite for Destruction"), null, 5L + it) }
        lib = lib.recordPlay(song(3, artist = "Queen", album = "Innuendo"), null, 9)
        val s = ListeningStats.from(lib)
        assertEquals(2, s.artists)
        assertEquals(listOf(4, 1), s.topArtists.map { it.plays })
        assertTrue(s.topArtists.first().name.startsWith("Guns N"))
    }

    @Test
    fun `i brani di una raccolta dello stesso disco contano come un album`() {
        var lib = UserLibrary()
        lib = lib.recordPlay(song(1, artist = "Ivana Spagna", album = "Disney Hits", art = "local/20"), null, 1)
        lib = lib.recordPlay(song(2, artist = "Lorena Brancucci", album = "Disney Hits", art = "local/20"), null, 2)
        assertEquals(listOf(2), ListeningStats.from(lib).topAlbums.map { it.plays })
    }

    @Test
    fun `una raccolta eliminata non torna da sola ma si puo ricreare, con tutti i brani`() {
        val anime = FolderCollection("Music/Anime", "Anime", listOf(TrackRef("local/track/1", "Uno"), TrackRef("local/track/2", "Due")))
        val rock = FolderCollection("Music/Rock", "Rock", listOf(TrackRef("local/track/3", "Tre")))
        val vuota = FolderCollection("Music/Vuota", "Vuota", emptyList())
        var n = 0
        var lib = UserLibrary().syncFolders(listOf(anime, rock), setOf("local/track/1", "local/track/2", "local/track/3"), 1) { "p${n++}" }
        lib = lib.deletePlaylist(lib.playlists.first { it.folder == "Music/Anime" }.id)
        lib = lib.syncFolders(listOf(anime, rock), setOf("local/track/1", "local/track/2", "local/track/3"), 2) { "x" }
        assertEquals(listOf("Rock"), lib.collections.map { it.name })
        assertEquals(listOf("Music/Anime"), lib.deletedFolders(listOf(anime, rock, vuota)).map { it.folder })

        lib = lib.restoreFolder(anime, 3) { "nuova" }
        val back = lib.collections.first { it.folder == "Music/Anime" }
        assertEquals(listOf("local/track/1", "local/track/2"), back.tracks.map { it.id })
        assertEquals("Anime", back.name)
        assertTrue(lib.deletedFolders(listOf(anime, rock)).isEmpty())
        // E torna a seguire la cartella: un brano nuovo entra da solo.
        val more = anime.copy(tracks = anime.tracks + TrackRef("local/track/4", "Quattro"))
        lib = lib.syncFolders(listOf(more, rock), setOf("local/track/1", "local/track/2", "local/track/3", "local/track/4"), 4) { "y" }
        assertEquals(listOf("local/track/1", "local/track/2", "local/track/4"), lib.collections.first { it.folder == "Music/Anime" }.tracks.map { it.id })
    }
}
