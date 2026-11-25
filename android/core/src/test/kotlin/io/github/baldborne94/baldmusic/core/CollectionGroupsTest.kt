package io.github.baldborne94.baldmusic.core

import io.github.baldborne94.baldmusic.core.library.ContainerRef
import io.github.baldborne94.baldmusic.core.library.LibraryNode
import io.github.baldborne94.baldmusic.core.library.RemoteSource
import io.github.baldborne94.baldmusic.core.library.LibraryNode.Kind
import io.github.baldborne94.baldmusic.core.library.MediaId
import io.github.baldborne94.baldmusic.core.library.PersonalSource
import io.github.baldborne94.baldmusic.core.library.PlayCount
import io.github.baldborne94.baldmusic.core.library.TrackRef
import io.github.baldborne94.baldmusic.core.library.UserLibrary
import io.github.baldborne94.baldmusic.core.library.UserPlaylist
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class CollectionGroupsTest {
    private fun track(n: String) = TrackRef("local/track/$n", n)
    private fun collection(name: String, vararg tracks: String) =
        UserPlaylist(name.lowercase(), name, tracks.map(::track), folder = "Music/$name")
    private fun heard(name: String) =
        ContainerRef(MediaId(PersonalSource.KEY, PersonalSource.PLAYLIST, name.lowercase()).toString(), name, kind = Kind.PLAYLIST.name)
    private fun plays(vararg counts: Pair<String, Int>) =
        counts.associate { (n, c) -> "local/track/$n" to PlayCount(track(n), c, 0) }

    private val all = listOf(
        collection("Zucchero", "z"),
        collection("Disney", "d1", "d2"),
        collection("Rock", "r"),
        collection("Anime", "a"),
        collection("Movie", "m"),
    )

    @Test
    fun `senza ascolti le raccolte restano dalla A alla Z senza intestazioni`() {
        val groups = UserLibrary(playlists = all).collectionGroups()
        assertEquals(listOf(null to listOf("Anime", "Disney", "Movie", "Rock", "Zucchero")), groups.map { (g, l) -> g to l.map { it.name } })
    }

    @Test
    fun `prima le ultime ascoltate, poi le piu ascoltate, poi le altre, ognuna una volta`() {
        val lib = UserLibrary(
            playlists = all,
            // L'album non è una raccolta: non conta.
            recent = listOf(heard("Rock"), ContainerRef("local/album/x", "Un album", kind = Kind.ALBUM.name), heard("Zucchero")),
            // Disney ha più ascolti in tutto; Rock è già tra le recenti e non si ripete.
            plays = plays("d1" to 3, "d2" to 4, "m" to 2, "r" to 50),
        )
        val groups = lib.collectionGroups().map { (g, l) -> g to l.map { it.name } }
        assertEquals(
            listOf(
                "Ascoltate di recente" to listOf("Rock", "Zucchero"),
                "Le più ascoltate" to listOf("Disney", "Movie"),
                "Tutte le altre" to listOf("Anime"),
            ),
            groups,
        )
    }

    @Test
    fun `al massimo sei recenti e sei piu ascoltate`() {
        val many = (1..20).map { collection("C%02d".format(it), "t$it") }
        val lib = UserLibrary(
            playlists = many,
            recent = many.take(8).map { heard(it.name) },
            plays = (9..20).associate { "local/track/t$it" to PlayCount(track("t$it"), it, 0) },
        )
        val groups = lib.collectionGroups()
        assertEquals(UserLibrary.RECENT_COLLECTIONS, groups[0].second.size)
        assertEquals(listOf("C20", "C19", "C18", "C17", "C16", "C15"), groups[1].second.map { it.name })
        assertEquals(20, groups.sumOf { it.second.size })
    }

    @Test
    fun `in Android Auto le raccolte hanno le intestazioni dei gruppi`() = runBlocking {
        val lib = UserLibrary(playlists = all, recent = listOf(heard("Movie")))
        val nodes = PersonalSource { lib }.children(MediaId(PersonalSource.KEY, PersonalSource.COLLECTIONS))
        assertEquals(listOf("Movie", "Anime", "Disney", "Rock", "Zucchero"), nodes.map { it.title })
        assertEquals(listOf("Ascoltate di recente", "Tutte le altre", "Tutte le altre", "Tutte le altre", "Tutte le altre"), nodes.map { it.group })
    }

    @Test
    fun `la pagina delle raccolte si ricarica solo quando cambia l'ordine`() {
        val page = MediaId(PersonalSource.KEY, PersonalSource.COLLECTIONS).toString()
        val lib = UserLibrary(playlists = all, recent = listOf(heard("Movie")), plays = plays("d1" to 3))
        // Un ascolto in più a Disney, già la più ascoltata: stesso ordine, niente da ricaricare.
        assertEquals(PersonalSource.shownBy(lib, page), PersonalSource.shownBy(lib.copy(plays = plays("d1" to 4)), page))
        // Ascoltata un'altra raccolta: va in cima.
        assertNotEquals(PersonalSource.shownBy(lib, page), PersonalSource.shownBy(lib.touchRecent(heard("Rock")), page))
    }

    private fun server(name: String) = LibraryNode(
        mediaId = MediaId(RemoteSource.KEY, RemoteSource.PLAYLIST, "s-$name").toString(),
        title = name,
        kind = Kind.PLAYLIST,
        subtitle = "Raccolta · 10 brani",
    )

    @Test
    fun `con la musica solo sul NAS le raccolte del server stanno in Raccolte`() = runBlocking {
        // Il telefono non ha più musica: nessuna raccolta sua (resoconto: «non vedo le raccolte»).
        val lib = UserLibrary(recent = listOf(ContainerRef(server("Movie").mediaId, "Movie", kind = Kind.PLAYLIST.name)))
        val source = PersonalSource(serverCollections = { listOf(server("Rock"), server("Anime"), server("Movie")) }) { lib }
        val nodes = source.children(MediaId(PersonalSource.KEY, PersonalSource.COLLECTIONS))
        assertEquals(listOf("Movie", "Anime", "Rock"), nodes.map { it.title })
        assertEquals(listOf("Ascoltate di recente", "Tutte le altre", "Tutte le altre"), nodes.map { it.group })
        assertEquals("remote/playlist/s-Movie", nodes[0].mediaId)
    }

    @Test
    fun `una raccolta del server col nome di una del telefono con brani non si ripete`() = runBlocking {
        val lib = UserLibrary(playlists = listOf(collection("Disney", "d1"), collection("Vuota")))
        val source = PersonalSource(serverCollections = { listOf(server("disney"), server("Vuota"), server("Rock")) }) { lib }
        val nodes = source.children(MediaId(PersonalSource.KEY, PersonalSource.COLLECTIONS))
        // Disney del telefono (suona senza rete); Vuota due volte: quella del telefono non ha brani.
        assertEquals(
            listOf("me/playlist/disney", "remote/playlist/s-Rock", "me/playlist/vuota", "remote/playlist/s-Vuota"),
            nodes.map { it.mediaId },
        )
    }

    @Test
    fun `il numero delle raccolte conta anche quelle del server gia lette, senza rete`() = runBlocking {
        val lib = UserLibrary(playlists = listOf(collection("Disney", "d1")))
        val source = PersonalSource(
            serverCollections = { error("niente rete per contare") },
            knownServerCollections = { listOf(server("Rock"), server("Anime")) },
        ) { lib }
        assertEquals("3 raccolte", source.sections().first { it.mediaId == "me/collections" }.subtitle)
    }

    @Test
    fun `la ricerca trova anche le raccolte del server`() = runBlocking {
        val lib = UserLibrary(playlists = listOf(collection("Disney", "d1")))
        val source = PersonalSource(serverCollections = { listOf(server("Disney"), server("Disney Pixar"), server("Rock")) }) { lib }
        assertEquals(listOf("Disney", "Disney Pixar"), source.search("disney").map { it.title })
        assertEquals("me/playlist/disney", source.search("disney").first().mediaId)
    }
}
