package io.github.baldborne94.baldmusic.core

import io.github.baldborne94.baldmusic.core.library.ContainerRef
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
}
