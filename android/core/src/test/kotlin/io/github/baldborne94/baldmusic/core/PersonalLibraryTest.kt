package io.github.baldborne94.baldmusic.core

import io.github.baldborne94.baldmusic.core.library.ContainerRef
import io.github.baldborne94.baldmusic.core.library.FolderCollection
import io.github.baldborne94.baldmusic.core.library.LibraryNode
import io.github.baldborne94.baldmusic.core.library.LibraryNode.Kind
import io.github.baldborne94.baldmusic.core.library.LibraryTree
import io.github.baldborne94.baldmusic.core.library.MediaId
import io.github.baldborne94.baldmusic.core.library.MusicSource
import io.github.baldborne94.baldmusic.core.library.PersonalSource
import io.github.baldborne94.baldmusic.core.library.TrackRef
import io.github.baldborne94.baldmusic.core.library.UserLibrary
import io.github.baldborne94.baldmusic.core.library.UserPlaylist
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun ref(n: Int, parent: String? = "local/album/7") =
    TrackRef(if (parent != null) "local/track/$n|$parent" else "local/track/$n", "Brano $n", artwork = "local/7")

/** Il telefono: brani 1..9, tutti risolvibili. */
private class Phone : MusicSource {
    override val key = "local"
    override val title = "Telefono"
    override suspend fun sections() = emptyList<LibraryNode>()
    override suspend fun children(id: MediaId) = emptyList<LibraryNode>()
    override suspend fun track(id: MediaId) =
        LibraryNode(id.toString(), "Brano ${id.id}", Kind.TRACK, streamUri = "content://t/${id.id}")
    override suspend fun search(query: String) = emptyList<LibraryNode>()
}

class PersonalLibraryTest {

    @Test
    fun `mi piace ignora il contenitore e si toglie col secondo tocco`() {
        val lib = UserLibrary().toggleLike(ref(1))
        assertTrue(lib.isLiked("local/track/1|me/liked"))
        assertEquals("local/track/1", lib.liked.single().id)
        assertFalse(lib.toggleLike(ref(1, parent = "local/songs")).isLiked("local/track/1"))
    }

    @Test
    fun `gli ascoltati di recente vanno in cima senza doppioni`() {
        val a = ContainerRef("local/album/1", "A", kind = "ALBUM")
        val b = ContainerRef("local/album/2", "B", kind = "ALBUM")
        val lib = UserLibrary().recordPlay(ref(1), a, 1).recordPlay(ref(2), b, 2).recordPlay(ref(3), a, 3)
        assertEquals(listOf("local/album/1", "local/album/2"), lib.recent.map { it.id })
    }

    @Test
    fun `i piu ascoltati contano gli ascolti`() {
        var lib = UserLibrary()
        repeat(3) { lib = lib.recordPlay(ref(5), null, it.toLong()) }
        lib = lib.recordPlay(ref(6), null, 10)
        assertEquals(listOf("local/track/5", "local/track/6"), lib.topTracks().map { it.id })
    }

    @Test
    fun `una playlist non duplica i brani`() {
        val lib = UserLibrary().createPlaylist("p", "Viaggio", 0, listOf(ref(1)))
            .addToPlaylist("p", listOf(ref(1, "me/liked"), ref(2), ref(2)))
        assertEquals(listOf("local/track/1", "local/track/2"), lib.playlists.single().tracks.map { it.id })
        assertEquals(1, lib.removeFromPlaylist("p", "local/track/2|me/playlist/p").playlists.single().tracks.size)
    }

    @Test
    fun `cancellare una playlist la toglie anche dai recenti`() {
        val lib = UserLibrary().createPlaylist("p", "X", 0)
            .recordPlay(ref(1), ContainerRef("me/playlist/p", "X", kind = "PLAYLIST"), 1)
            .deletePlaylist("p")
        assertTrue(lib.recent.isEmpty())
    }

    @Test
    fun `ricerche recenti senza doppioni`() {
        val lib = UserLibrary().addSearch("dalla").addSearch("Muse").addSearch("Dalla").addSearch("x")
        assertEquals(listOf("Dalla", "Muse"), lib.searches)
    }

    @Test
    fun `toccare un brano che ti piace riproduce i preferiti da li, risolti sul telefono`() = runTest {
        var lib = UserLibrary()
        listOf(3, 2, 1).forEach { lib = lib.toggleLike(ref(it)) }
        val tree = LibraryTree({ listOf(PersonalSource { lib }, Phone()) })
        val second = tree.children("me/liked")[1]
        assertEquals("local/track/2|me/liked", second.mediaId)
        val (queue, start) = tree.queueFor(second.mediaId)
        assertEquals(listOf("content://t/1", "content://t/2", "content://t/3"), queue.map { it.streamUri })
        assertEquals(1, start)
    }

    @Test
    fun `la musica tua non resta in cache`() = runTest {
        var lib = UserLibrary()
        val tree = LibraryTree({ listOf(PersonalSource { lib }) })
        assertEquals(0, tree.children("me/liked").size)
        lib = lib.toggleLike(ref(1))
        assertEquals(1, tree.children("me/liked").size)
    }

    @Test
    fun `una playlist si trova per ID`() = runTest {
        val lib = UserLibrary().createPlaylist("p", "Viaggio", 0, listOf(ref(1)))
        val tree = LibraryTree({ listOf(PersonalSource { lib }) })
        val node = tree.node("me/playlist/p")
        assertEquals("Viaggio", node?.title)
        assertEquals("local/7", node?.artwork)
    }
}

class ListeningStatsTest {
    @Test
    fun `classifiche e minuti dagli ascolti`() {
        fun t(n: Int, artist: String, album: String) =
            io.github.baldborne94.baldmusic.core.library.TrackRef("local/track/$n", "Brano $n", artist, album, "local/$album", 180_000)
        var lib = UserLibrary()
        repeat(3) { lib = lib.recordPlay(t(1, "Queen", "A Night"), null, it.toLong()) }
        repeat(2) { lib = lib.recordPlay(t(2, "Muse", "Origin"), null, it.toLong()) }
        lib = lib.recordPlay(t(3, "Queen", "Jazz"), null, 9)
        val s = io.github.baldborne94.baldmusic.core.library.ListeningStats.from(lib)
        assertEquals(6, s.totalPlays)
        assertEquals(18, s.minutes)
        assertEquals(2, s.artists)
        assertEquals(listOf("Queen" to 4, "Muse" to 2), s.topArtists.map { it.name to it.plays })
        assertEquals("Brano 1", s.topTracks.first().name)
        assertEquals("A Night", s.topAlbums.first().name)
    }

    // --- Raccolte dalle cartelle ---

    private fun local(n: Int) = TrackRef("local/track/$n", "Brano $n")
    private val ids = generateSequence(1) { it + 1 }.map { "r$it" }.iterator()
    private fun sync(lib: UserLibrary, folders: List<FolderCollection>, present: Set<Int> = (1..20).toSet()) =
        lib.syncFolders(folders, present.mapTo(HashSet()) { "local/track/$it" }, now = 1) { ids.next() }

    @Test
    fun `una cartella nuova diventa una raccolta`() {
        val lib = sync(UserLibrary(), listOf(FolderCollection("Music/Disney", "Disney", listOf(local(1), local(2)))))
        val c = lib.collections.single()
        assertEquals("Disney", c.name)
        assertEquals(listOf("local/track/1", "local/track/2"), c.tracks.map { it.id })
        assertTrue(lib.playlists.single().isCollection)
    }

    @Test
    fun `i brani nuovi della cartella si aggiungono, quelli tolti a mano no`() {
        var lib = sync(UserLibrary(), listOf(FolderCollection("Music/Disney", "Disney", listOf(local(1), local(2)))))
        val id = lib.collections.single().id
        lib = lib.removeFromPlaylist(id, "local/track/1|me/playlist/$id")
        lib = sync(lib, listOf(FolderCollection("Music/Disney", "Disney", listOf(local(1), local(2), local(3)))))
        assertEquals(listOf("local/track/2", "local/track/3"), lib.collections.single().tracks.map { it.id })
        // Rimesso a mano, torna e non è più "tolto".
        lib = lib.addToPlaylist(id, listOf(local(1)))
        assertEquals(listOf("local/track/2", "local/track/3", "local/track/1"), lib.collections.single().tracks.map { it.id })
        assertTrue(lib.collections.single().removed.isEmpty())
    }

    @Test
    fun `le aggiunte da fuori restano, i file spariti escono`() {
        var lib = sync(UserLibrary(), listOf(FolderCollection("Music/Disney", "Disney", listOf(local(1), local(2)))))
        val id = lib.collections.single().id
        lib = lib.addToPlaylist(id, listOf(local(9), TrackRef("remote/track/s1", "Dal server")))
        lib = sync(lib, listOf(FolderCollection("Music/Disney", "Disney", listOf(local(2)))), present = setOf(2, 9))
        assertEquals(listOf("local/track/2", "local/track/9", "remote/track/s1"), lib.collections.single().tracks.map { it.id })
    }

    @Test
    fun `una raccolta eliminata non torna, e rinominata resta col suo nome`() {
        var lib = sync(UserLibrary(), listOf(FolderCollection("Music/A", "A", listOf(local(1))), FolderCollection("Music/B", "B", listOf(local(2)))))
        val a = lib.collections.first { it.folder == "Music/A" }.id
        val b = lib.collections.first { it.folder == "Music/B" }.id
        lib = lib.deletePlaylist(a).renamePlaylist(b, "Le mie preferite")
        lib = sync(lib, listOf(FolderCollection("Music/A", "A", listOf(local(1))), FolderCollection("Music/B", "B", listOf(local(2), local(3)))))
        assertEquals(listOf("Le mie preferite"), lib.collections.map { it.name })
        assertEquals(2, lib.collections.single().tracks.size)
    }

    @Test
    fun `se non cambia niente la libreria resta la stessa`() {
        val folders = listOf(FolderCollection("Music/A", "A", listOf(local(1))))
        val lib = sync(UserLibrary(), folders)
        assertTrue(sync(lib, folders) === lib)
    }

    @Test
    fun `raccolte e playlist stanno in sezioni diverse`() = runTest {
        var lib = sync(UserLibrary(), listOf(FolderCollection("Music/A", "A", listOf(local(1)))))
        lib = lib.createPlaylist("p1", "Mia", now = 2)
        val src = PersonalSource { lib }
        assertEquals(listOf("A"), src.children(MediaId(PersonalSource.KEY, PersonalSource.COLLECTIONS)).map { it.title })
        assertEquals(listOf("Mia"), src.children(MediaId(PersonalSource.KEY, PersonalSource.PLAYLISTS)).map { it.title })
        assertEquals("Raccolta · 1 brano", src.children(MediaId(PersonalSource.KEY, PersonalSource.COLLECTIONS)).single().subtitle)
    }

    @Test
    fun `i brani buttati spariscono da preferiti, playlist, raccolte e ascolti`() {
        var lib = sync(UserLibrary(), listOf(FolderCollection("Music/A", "A", listOf(local(1), local(2)))))
        lib = lib.toggleLike(local(1)).createPlaylist("p", "Mia", now = 2, first = listOf(local(1), local(3)))
            .recordPlay(local(1), null, now = 3)
        lib = lib.forgetTracks(listOf("local/track/1|me/collections"))
        assertFalse(lib.isLiked("local/track/1"))
        assertEquals(listOf("local/track/2"), lib.collections.single().tracks.map { it.id })
        assertEquals(listOf("local/track/3"), lib.playlists.first { !it.isCollection }.tracks.map { it.id })
        assertTrue(lib.plays.isEmpty())
    }

    @Test
    fun `una raccolta eliminata insieme ai file torna se la cartella si riempie`() {
        var lib = sync(UserLibrary(), listOf(FolderCollection("Music/A", "A", listOf(local(1)))))
        lib = lib.deletePlaylist(lib.collections.single().id, ignoreFolder = false)
        assertTrue(lib.collections.isEmpty())
        lib = sync(lib, listOf(FolderCollection("Music/A", "A", listOf(local(5)))))
        assertEquals(listOf("local/track/5"), lib.collections.single().tracks.map { it.id })
    }

    // --- Ordine e copertina ---

    private fun song(n: Int, title: String, artist: String, art: String? = "local/$n") = TrackRef("local/track/$n", title, artist = artist, artwork = art)

    @Test
    fun `l'ordine trascinato si salva, e chi manca resta in fondo`() {
        var lib = UserLibrary().createPlaylist("p", "Mia", now = 1, first = listOf(song(1, "A", "x"), song(2, "B", "x"), song(3, "C", "x")))
        lib = lib.reorderPlaylist("p", listOf("local/track/3|me/playlist/p", "local/track/1"))
        assertEquals(listOf("local/track/3", "local/track/1", "local/track/2"), lib.playlists.single().tracks.map { it.id })
    }

    @Test
    fun `si ordina per titolo o per artista`() {
        var lib = UserLibrary().createPlaylist("p", "Mia", now = 1, first = listOf(song(1, "Zeta", "Avril"), song(2, "Alfa", "Tool"), song(3, "Beta", "Avril")))
        assertEquals(listOf("Alfa", "Beta", "Zeta"), lib.sortPlaylist("p", byArtist = false).playlists.single().tracks.map { it.title })
        lib = lib.sortPlaylist("p", byArtist = true)
        assertEquals(listOf("Beta", "Zeta", "Alfa"), lib.playlists.single().tracks.map { it.title })
    }

    @Test
    fun `con quattro copertine diverse la raccolta ha il mosaico`() {
        val four = UserPlaylist("p", "Mia", listOf(song(1, "A", "x"), song(2, "B", "x", art = "local/1"), song(3, "C", "x"), song(4, "D", "x"), song(5, "E", "x")))
        assertEquals("mosaic/local/1,local/3,local/4,local/5", PersonalSource.artworkOf(four))
        val few = UserPlaylist("q", "Poca", listOf(song(1, "A", "x"), song(2, "B", "x", art = null)))
        assertEquals("local/1", PersonalSource.artworkOf(few))
        assertEquals(null, PersonalSource.artworkOf(UserPlaylist("v", "Vuota")))
    }
}
