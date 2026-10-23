package io.github.baldborne94.baldmusic.core

import io.github.baldborne94.baldmusic.core.library.ContainerRef
import io.github.baldborne94.baldmusic.core.library.FileTags
import io.github.baldborne94.baldmusic.core.library.LibraryNode
import io.github.baldborne94.baldmusic.core.library.LibraryNode.Kind
import io.github.baldborne94.baldmusic.core.library.LibraryTree
import io.github.baldborne94.baldmusic.core.library.LocalCatalog
import io.github.baldborne94.baldmusic.core.library.LocalTrack
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Il telefono come lo vede l'albero: LocalCatalog sopra un elenco fisso di brani, come fa LocalSource. */
private class CatalogPhone(val tracks: List<LocalTrack>) : MusicSource {
    private val catalog = LocalCatalog(streamUri = { "content://audio/$it" })
    override val key = LocalCatalog.KEY
    override val title = "Telefono"
    override suspend fun sections() = catalog.sections()
    override suspend fun children(id: MediaId) = catalog.children(id, tracks)
    override suspend fun track(id: MediaId) =
        id.id?.toLongOrNull()?.let { n -> tracks.firstOrNull { it.id == n } }?.let { catalog.node(it, id.parent, tracks) }
    override suspend fun search(query: String) = catalog.search(query, tracks)
}

/** Un server con brani dagli ID numerici, come certi server Subsonic. */
private class NumericServer : MusicSource {
    override val key = "remote"
    override val title = "Server"
    override suspend fun sections() = emptyList<LibraryNode>()
    override suspend fun children(id: MediaId) = emptyList<LibraryNode>()
    override suspend fun track(id: MediaId) =
        LibraryNode(id.toString(), "Server ${id.id}", Kind.TRACK, streamUri = "https://server/${id.id}")
    override suspend fun search(query: String) = emptyList<LibraryNode>()
}

class MediaIdMoreTest {

    // --- Andata e ritorno degli ID ---

    @Test
    fun `ID con spazi, barre, barre verticali, accenti e simboli tornano uguali`() {
        val weird = listOf("Music/Disney Italia", "n:queen|innuendo", "Città è più bella", "a+b c%20d", "100% puro", "rg:1/2|3", "", "#&?=")
        weird.forEach { raw ->
            val folder = MediaId(LocalCatalog.KEY, LocalCatalog.FOLDER, raw)
            assertEquals(folder, MediaId.parse(folder.toString()), raw)
            val track = MediaId.track(LocalCatalog.KEY, raw, folder)
            val parsed = MediaId.parse(track.toString())!!
            assertEquals(raw, parsed.id)
            assertEquals(raw, parsed.parent?.id)
            assertEquals(track.toString(), parsed.toString())
        }
    }

    @Test
    fun `nell'ID scritto c'e una sola barra verticale, quella del contenitore`() {
        val track = MediaId.track("local", "1|2", MediaId("local", "album", "a|b/c"))
        assertEquals(1, track.toString().count { it == '|' })
        assertEquals("local/folder/Music%2FDisney+Italia", MediaId("local", "folder", "Music/Disney Italia").toString())
        assertEquals("Music/Disney Italia", MediaId.parse("local/folder/Music%2FDisney+Italia")?.id)
    }

    @Test
    fun `l'ID di base toglie il contenitore e lascia stare il resto`() {
        assertEquals("local/track/5", UserLibrary.baseId("local/track/5|me/playlist/p"))
        assertEquals("local/track/5", UserLibrary.baseId("local/track/5"))
        assertEquals("remote/track/al%7C1", UserLibrary.baseId(MediaId.track("remote", "al|1", MediaId("remote", "album", "x")).toString()))
        assertNull(MediaId.parse(""))
        assertNull(MediaId.parse("   "))
        val withParent = MediaId("local", "track", "1").withParent(MediaId("local", "songs"))
        assertEquals("local/track/1|local/songs", withParent.toString())
        assertFalse(MediaId("local", "album", "1").isTrack)
    }

    // --- Albero sopra il catalogo del telefono ---

    private var n = 7000L
    private fun t(title: String, album: String, track: Int, folder: String = "Music/Queen", albumArtist: String? = "Queen") =
        LocalTrack(n++, title, "Queen", 1, album, 1, 200_000, 0, track, 1991, 0, folder)
            .withTags(FileTags(title = title, artist = "Queen", album = album, albumArtist = albumArtist, track = track, year = 1991))

    @Test
    fun `toccare un brano di un album col nome strano accoda l'album da quel brano`() = runTest {
        val tracks = listOf(t("Innuendo", "Innuendo | Città/Live", 1), t("I'm Going Slightly Mad", "Innuendo | Città/Live", 2), t("Headlong", "Innuendo | Città/Live", 3))
        val tree = LibraryTree({ listOf(CatalogPhone(tracks)) })
        val album = tree.children("local/albums").single()
        val second = tree.children(album.mediaId)[1]
        assertEquals(album.mediaId, MediaId.parse(second.mediaId)?.parent?.toString())
        val (queue, start) = tree.queueFor(second.mediaId)
        assertEquals(listOf("Innuendo", "I'm Going Slightly Mad", "Headlong"), queue.map { it.title })
        assertEquals(1, start)
        assertTrue(queue.all { it.streamUri != null })
    }

    @Test
    fun `toccare un brano in una cartella accoda la cartella`() = runTest {
        val tracks = listOf(t("A", "Alfa", 1, folder = "Music/Disney Italia"), t("B", "Beta", 1, folder = "Music/Disney Italia"), t("C", "Gamma", 1, folder = "Music/Altro"))
        val tree = LibraryTree({ listOf(CatalogPhone(tracks)) })
        val folder = tree.children("local/folders").first { it.title == "Disney Italia" }
        val b = tree.children(folder.mediaId).first { it.title == "B" }
        val (queue, start) = tree.queueFor(b.mediaId)
        assertEquals(listOf("A", "B"), queue.map { it.title })
        assertEquals(1, start)
    }

    @Test
    fun `in una playlist con brani del telefono e del server parte il brano toccato`() = runTest {
        val tracks = listOf(t("Dal telefono", "Innuendo", 1))
        val phoneId = "local/track/${tracks[0].id}"
        // Lo stesso numero come ID sul server: sono due brani diversi.
        val serverId = "remote/track/${tracks[0].id}"
        val lib = UserLibrary().createPlaylist("p", "Mista", 0, listOf(TrackRef(serverId, "Dal server"), TrackRef(phoneId, "Dal telefono")))
        val tree = LibraryTree({ listOf(PersonalSource { lib }, CatalogPhone(tracks), NumericServer()) })
        val nodes = tree.children("me/playlist/p")
        val (queue, start) = tree.queueFor(nodes[1].mediaId)
        assertEquals(listOf("Server ${tracks[0].id}", "Dal telefono"), queue.map { it.title })
        assertEquals(1, start)
    }

    @Test
    fun `un brano sparito dal contenitore si suona da solo`() = runTest {
        val tracks = listOf(t("A", "Uno", 1), t("B", "Uno", 2))
        val tree = LibraryTree({ listOf(CatalogPhone(tracks)) })
        val other = MediaId.track("local", "${tracks[0].id}", MediaId("local", "album", "inesistente")).toString()
        val (queue, start) = tree.queueFor(other)
        assertEquals(listOf("A"), queue.map { it.title })
        assertEquals(0, start)
        assertEquals(emptyList<LibraryNode>() to 0, tree.queueFor("local/track/999999"))
        assertEquals(emptyList<LibraryNode>() to 0, tree.queueFor(""))
    }

    @Test
    fun `nodi della radice, delle sorgenti e dei contenitori gia elencati`() = runTest {
        val tracks = listOf(t("A", "Uno", 1), t("B", "Uno", 2))
        val tree = LibraryTree({ listOf(CatalogPhone(tracks)) })
        assertEquals("BaldMusic", tree.node(MediaId.ROOT)?.title)
        assertEquals("Telefono", tree.node("local")?.title)
        assertNull(tree.node("sconosciuta/albums"))
        val album = tree.children("local/albums").single()
        // Il titolo del contenitore viene dall'elenco già letto.
        assertEquals("Uno", tree.node(album.mediaId)?.title)
        assertEquals("", tree.node("local/album/mai-visto")?.title)
        assertEquals(emptyList(), tree.children("sconosciuta/albums"))
    }

    // --- La tua musica ---

    @Test
    fun `sezioni della tua musica, prima le raccolte, coi conteggi`() = runTest {
        val lib = UserLibrary(
            liked = listOf(TrackRef("local/track/1", "A")),
            playlists = listOf(UserPlaylist("c", "Disney", folder = "Music/Disney"), UserPlaylist("p", "Mia"), UserPlaylist("q", "Altra")),
        )
        val sections = PersonalSource { lib }.sections()
        assertEquals(listOf("Raccolte", "Brani che ti piacciono", "I tuoi mix", "Le tue playlist", "Ascoltati di recente", "I più ascoltati"), sections.map { it.title })
        assertEquals(listOf("1 raccolta", "1 brano", null, "2 playlist", null, null), sections.map { it.subtitle })
    }

    @Test
    fun `i brani di raccolte, playlist e piu ascoltati portano il loro contenitore`() = runTest {
        var lib = UserLibrary(playlists = listOf(UserPlaylist("c", "Disney", listOf(TrackRef("local/track/1", "A"), TrackRef("remote/track/x", "B")), folder = "Music/Disney")))
        lib = lib.recordPlay(TrackRef("local/track/2|local/album/9", "C"), null, 1)
        val src = PersonalSource { lib }
        assertEquals(
            listOf("local/track/1|me/playlist/c", "remote/track/x|me/playlist/c"),
            src.children(MediaId(PersonalSource.KEY, PersonalSource.PLAYLIST, "c")).map { it.mediaId },
        )
        assertEquals(listOf("local/track/2|me/top"), src.children(MediaId(PersonalSource.KEY, PersonalSource.TOP)).map { it.mediaId })
        assertEquals(emptyList(), src.children(MediaId(PersonalSource.KEY, PersonalSource.PLAYLIST, "nessuna")))
        assertNull(src.track(MediaId.track("local", "1", null)))
    }

    @Test
    fun `un brano ricordato con un ID rovinato non si mostra`() = runTest {
        val lib = UserLibrary(liked = listOf(TrackRef("local/album/1", "Non un brano"), TrackRef("local/track/1", "Buono")))
        assertEquals(listOf("Buono"), PersonalSource { lib }.children(MediaId(PersonalSource.KEY, PersonalSource.LIKED)).map { it.title })
    }

    @Test
    fun `gli ascoltati di recente tengono il loro tipo, un tipo sconosciuto e un album`() = runTest {
        val lib = UserLibrary(
            playlists = listOf(UserPlaylist("p", "Mia")),
            recent = listOf(ContainerRef("me/playlist/p", "Mia", kind = "PLAYLIST"), ContainerRef("local/x/1", "Strano", kind = "BOH")),
        )
        assertEquals(listOf(Kind.PLAYLIST, Kind.ALBUM), PersonalSource { lib }.children(MediaId(PersonalSource.KEY, PersonalSource.RECENT)).map { it.kind })
    }

    @Test
    fun `le raccolte sono in ordine alfabetico e la ricerca le trova senza badare ad accenti e apostrofi`() = runTest {
        val lib = UserLibrary(
            playlists = listOf(
                UserPlaylist("b", "movie Soundtracks", folder = "Music/Movie Soundtracks"),
                UserPlaylist("a", "Canzoni d’Amore", folder = "Music/Canzoni d’Amore"),
                UserPlaylist("c", "Città", folder = "Music/Città"),
            ),
        )
        val src = PersonalSource { lib }
        assertEquals(listOf("Canzoni d’Amore", "Città", "movie Soundtracks"), src.children(MediaId(PersonalSource.KEY, PersonalSource.COLLECTIONS)).map { it.title })
        assertEquals(listOf("Canzoni d’Amore"), src.search("canzoni d'amore").map { it.title })
        assertEquals(listOf("Città"), src.search("citta").map { it.title })
        assertEquals(emptyList(), src.search("  "))
        assertEquals("me/playlist/b", src.node(MediaId(PersonalSource.KEY, PersonalSource.PLAYLIST, "b"))?.mediaId)
        assertEquals("Raccolte", src.node(MediaId(PersonalSource.KEY, PersonalSource.COLLECTIONS))?.title)
    }

    @Test
    fun `il mosaico vuole quattro copertine diverse`() {
        fun pl(vararg art: String?) = UserPlaylist("p", "P", art.mapIndexed { i, a -> TrackRef("local/track/$i", "T$i", artwork = a) })
        assertEquals("local/1", PersonalSource.artworkOf(pl("local/1", "local/2", "local/3")))
        assertEquals("local/1", PersonalSource.artworkOf(pl("local/1", "local/1", "local/1", "local/1", "local/2")))
        assertEquals("mosaic/local/1,local/2,local/3,local/4", PersonalSource.artworkOf(pl(null, "local/1", "local/2", "local/1", "local/3", "local/4", "local/5")))
        assertEquals(null, PersonalSource.artworkOf(pl(null, null)))
    }
}
