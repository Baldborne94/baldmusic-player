package io.github.baldborne94.baldmusic.core

import io.github.baldborne94.baldmusic.core.subsonic.Playlist
import io.github.baldborne94.baldmusic.core.subsonic.ServerCollections
import io.github.baldborne94.baldmusic.core.subsonic.ServerCollections.State
import io.github.baldborne94.baldmusic.core.subsonic.Song
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Le stesse regole di `web/src/lib/collections.ts`, ora anche sul telefono. */
class ServerCollectionsTest {
    private fun song(id: String, path: String?, title: String = id, duration: Int? = 200) = Song(id, title, path = path, duration = duration)
    private fun raccolta(id: String, folder: String, name: String = folder) = Playlist(id, name, comment = ServerCollections.comment(folder))

    @Test
    fun `il commento e lo stesso del sito e se ne ricava la cartella`() {
        assertEquals("Raccolta della cartella «Disney Italia» · BaldMusic", ServerCollections.comment("Disney Italia"))
        assertEquals("Disney Italia", ServerCollections.folderOf("Raccolta della cartella «Disney Italia» · BaldMusic"))
        assertNull(ServerCollections.folderOf("Una playlist qualunque"))
        assertNull(ServerCollections.folderOf(null))
    }

    @Test
    fun `le cartelle sono la prima sotto quella della musica, coi brani in ordine di percorso`() {
        val songs = listOf(
            song("a10", "/music/Anime e cartoni - Anime/10 - Dieci.mp3"),
            song("a2", "/music/Anime e cartoni - Anime/2 - Due.mp3"),
            song("d1", "/music/Disney/Sotto/1 - Uno.mp3"),
            // Sciolto nella cartella principale: di nessuna raccolta.
            song("x", "/music/Sciolto.mp3"),
        )
        val folders = ServerCollections.folders(songs)
        assertEquals(listOf("Anime e cartoni - Anime", "Disney"), folders.keys.toList())
        // In ordine «umano»: 2 prima di 10.
        assertEquals(listOf("a2", "a10"), folders["Anime e cartoni - Anime"])
        assertEquals(listOf("d1"), folders["Disney"])
    }

    @Test
    fun `con una cartella sola la cartella della musica e quella, come nel sito`() {
        assertTrue(ServerCollections.folders(listOf(song("a", "/music/Disney/a.mp3"), song("b", "/music/Disney/b.mp3"))).isEmpty())
    }

    @Test
    fun `con i percorsi finti di Navidrome nessuna cartella`() {
        // Senza «Report Real Path» Navidrome manda «Artista/Album/brano».
        assertTrue(ServerCollections.folders(listOf(song("a", "Avril/Let Go/01 - Losing Grip.mp3"), song("b", null))).isEmpty())
        assertTrue(ServerCollections.isRealPath("""X:\Musica\Disney\a.mp3"""))
        assertTrue(ServerCollections.isRealPath("/music/Disney/a.mp3"))
    }

    @Test
    fun `cartella nuova diventa raccolta, brani nuovi entrano, eliminate no`() {
        val folders = linkedMapOf("Disney" to listOf("d1", "d2", "d3"), "Rock" to listOf("r1"), "Vecchia" to listOf("v1"), "Nuova" to listOf("n1"))
        val playlists = listOf(raccolta("p1", "Disney"), raccolta("p2", "Rock"))
        val saved = State(mapOf("Disney" to listOf("d1", "d2"), "Rock" to listOf("r1"), "Vecchia" to listOf("v1")))
        val plan = ServerCollections.plan(folders, playlists, saved, deleted = emptySet())
        assertEquals(listOf(ServerCollections.Add("p1", listOf("d3"))), plan.add)
        assertEquals(listOf(ServerCollections.Create("Nuova", listOf("n1"))), plan.create)
        // «Vecchia» aveva una raccolta e non c'è più: eliminata dal sito, non si ricrea e va nell'elenco.
        assertEquals(listOf("Vecchia"), plan.newlyDeleted)
        assertEquals(setOf("Disney", "Rock", "Nuova"), plan.state.known.keys)
    }

    @Test
    fun `la prima volta sul telefono si creano solo le cartelle senza raccolta e non eliminate`() {
        val folders = linkedMapOf("Disney" to listOf("d1"), "Rock" to listOf("r1"), "Tolta" to listOf("t1"))
        val plan = ServerCollections.plan(folders, listOf(raccolta("p1", "Disney")), saved = null, deleted = setOf("Tolta"))
        assertEquals(listOf("Rock"), plan.create.map { it.folder })
        // Niente da aggiungere alla prima volta: la raccolta del sito resta com'è (brani tolti a mano compresi).
        assertTrue(plan.add.isEmpty())
        assertEquals(listOf("Tolta"), plan.deleted)
        assertTrue(plan.newlyDeleted.isEmpty())
    }

    @Test
    fun `l'elenco delle raccolte eliminate si legge e si scrive come nel sito`() {
        val comment = ServerCollections.deletedStoreComment(listOf("Rock", "Anime", "Rock"))
        assertEquals("""BaldMusic: raccolte eliminate ["Anime","Rock"]""", comment)
        assertEquals(listOf("Anime", "Rock"), ServerCollections.deletedInStore(comment))
        assertNull(ServerCollections.deletedInStore(ServerCollections.comment("Rock")))
        assertEquals(emptyList(), ServerCollections.deletedInStore("BaldMusic: raccolte eliminate rotto"))
    }

    @Test
    fun `cartella rinominata - la raccolta la segue, coi brani con ID nuovi`() {
        val folders = mapOf(
            "Disney Italia" to listOf(song("n1", null, "Hakuna Matata", 200), song("n2", null, "Il cerchio della vita", 300)),
            "Altro" to listOf(song("z", null, "Zeta")),
        )
        val orphan = ServerCollections.Orphan("p1", "Disney", "Disney", listOf(song("o1", null, "Hakuna matata", 201), song("o2", null, "Il cerchio della vita", 300)))
        val follow = ServerCollections.follow(folders, listOf(orphan), taken = setOf("Disney"), deleted = emptySet()).single()
        assertEquals("Disney Italia", follow.folder)
        // Aveva ancora il nome della cartella: prende quello nuovo.
        assertEquals("Disney Italia", follow.rename)
        assertEquals(listOf("n1", "n2"), follow.songIds)
        assertEquals(mapOf("o1" to "n1", "o2" to "n2"), follow.newIds)

        // Rinominata a mano: il nome resta.
        val named = orphan.copy(name = "I miei Disney")
        assertNull(ServerCollections.follow(folders, listOf(named), setOf("Disney"), emptySet()).single().rename)
        // Meno di metà dei brani: non è la stessa cartella.
        val half = orphan.copy(songs = orphan.songs + listOf(song("o3", null, "Altro 1"), song("o4", null, "Altro 2"), song("o5", null, "Altro 3")))
        assertTrue(ServerCollections.follow(folders, listOf(half), setOf("Disney"), emptySet()).isEmpty())
    }
}
