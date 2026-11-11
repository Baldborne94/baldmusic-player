package io.github.baldborne94.baldmusic.core

import io.github.baldborne94.baldmusic.core.library.PlaylistLink
import io.github.baldborne94.baldmusic.core.library.ServerSync
import io.github.baldborne94.baldmusic.core.library.ServerSync.ServerList
import io.github.baldborne94.baldmusic.core.library.ServerSync.Write
import io.github.baldborne94.baldmusic.core.library.SyncState
import io.github.baldborne94.baldmusic.core.library.TrackRef
import io.github.baldborne94.baldmusic.core.library.UserLibrary
import io.github.baldborne94.baldmusic.core.library.UserPlaylist
import io.github.baldborne94.baldmusic.core.subsonic.Song
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServerSyncTest {
    // Sul server: s1..s4. Sul telefono: i file di s1 (Numb) e s2 (In the End), e uno che il server non ha.
    private val server = listOf(
        Song("s1", "Numb", artist = "Linkin Park", duration = 185),
        Song("s2", "In the End", artist = "Linkin Park", duration = 216),
        Song("s3", "Bohemian Rhapsody", artist = "Queen", duration = 355),
        Song("s4", "Break Stuff", artist = "Limp Bizkit", duration = 166),
    )
    private val numb = TrackRef("local/track/1", "Numb", "Linkin Park", durationMs = 186_000)
    private val end = TrackRef("local/track/2", "in the end", "Linkin Park feat. Nessuno", durationMs = 215_000)
    private val onlyPhone = TrackRef("local/track/9", "Canzone del telefono", "Io", durationMs = 100_000)
    private val match = ServerSync.Matcher(server, listOf(numb, end, onlyPhone))

    private var next = 0
    private fun plan(lib: UserLibrary, starred: Set<String>, lists: List<ServerList> = emptyList(), state: SyncState = SyncState()) =
        ServerSync.plan(lib, starred, lists, state, match, { "n${next++}" }, now = 1)

    @Test
    fun `lo stesso brano si riconosce anche come file diverso, con ospiti e maiuscole`() {
        assertEquals("s1", match.toServer(numb))
        assertEquals("s2", match.toServer(end))
        assertNull(match.toServer(onlyPhone))
        assertEquals("s3", match.toServer(TrackRef("remote/track/s3", "x")))
        // Durata troppo diversa: un'altra versione.
        assertNull(match.toServer(numb.copy(durationMs = 240_000)))
        // Dal server al telefono: il file se c'è, se no il brano del server.
        assertEquals("local/track/1", match.toPhone("s1")?.id)
        assertEquals("remote/track/s3", match.toPhone("s3")?.id)
    }

    @Test
    fun `la prima volta si uniscono i mi piace, senza togliere niente`() {
        val p = plan(UserLibrary(liked = listOf(numb, onlyPhone)), starred = setOf("s3"))
        assertEquals(setOf("s1"), p.star)
        assertEquals(emptySet(), p.unstar)
        assertEquals(listOf("remote/track/s3", "local/track/1", "local/track/9"), p.library.liked.map { it.id })
        assertEquals(setOf("s1", "s3"), p.state.liked)
    }

    @Test
    fun `tolto dal telefono si toglie sul server, tolto sul server si toglie dal telefono`() {
        val state = SyncState(liked = setOf("s1", "s2", "s3"))
        // Sul telefono hai tolto In the End; sul PC hai tolto Bohemian Rhapsody e messo Break Stuff.
        val lib = UserLibrary(liked = listOf(numb, TrackRef("remote/track/s3", "Bohemian Rhapsody")))
        val p = plan(lib, starred = setOf("s1", "s2", "s4"), state = state)
        assertEquals(setOf("s2"), p.unstar)
        assertEquals(emptySet(), p.star)
        assertEquals(listOf("remote/track/s4", "local/track/1"), p.library.liked.map { it.id })
        assertEquals(setOf("s1", "s4"), p.state.liked)
    }

    @Test
    fun `niente di cambiato, niente da fare`() {
        val lib = UserLibrary(liked = listOf(numb))
        val p = plan(lib, starred = setOf("s1"), state = SyncState(liked = setOf("s1")))
        assertTrue(p.star.isEmpty() && p.unstar.isEmpty() && p.writes.isEmpty())
        assertTrue(p.library === lib)
    }

    @Test
    fun `una playlist solo sul telefono si crea sul server, una solo sul server sul telefono`() {
        val lib = UserLibrary(playlists = listOf(UserPlaylist("p1", "Corsa", listOf(numb, onlyPhone, end))))
        val p = plan(lib, emptySet(), lists = listOf(ServerList("x", "Dal PC", listOf("s3", "s1"))))
        assertEquals(listOf<Write>(Write.Create("p1", "Corsa", listOf("s1", "s2"))), p.writes)
        val fromPc = p.library.playlists.single { it.name == "Dal PC" }
        assertEquals(listOf("remote/track/s3", "local/track/1"), fromPc.tracks.map { it.id })
        assertEquals(listOf(PlaylistLink(fromPc.id, "x", "Dal PC", listOf("s3", "s1"))), p.state.playlists)
        // Creata sul server, l'app aggiunge il collegamento.
        assertEquals(2, p.state.created("p1", "y", "Corsa", listOf("s1", "s2")).playlists.size)
    }

    @Test
    fun `le raccolte non si allineano`() {
        val lib = UserLibrary(playlists = listOf(UserPlaylist("c", "Disney", listOf(numb), folder = "Music/Disney")))
        assertEquals(emptyList(), plan(lib, emptySet()).writes)
    }

    @Test
    fun `la prima volta due playlist con lo stesso nome diventano una`() {
        val lib = UserLibrary(playlists = listOf(UserPlaylist("p1", "Corsa", listOf(numb))))
        val p = plan(lib, emptySet(), lists = listOf(ServerList("x", "corsa", listOf("s3"))))
        assertEquals(listOf<Write>(Write.Replace("x", null, listOf("s1", "s3"))), p.writes)
        assertEquals(listOf("local/track/1", "remote/track/s3"), p.library.playlists.single().tracks.map { it.id })
        assertEquals(1, p.library.playlists.size)
    }

    @Test
    fun `cambiata da tutte e due le parti si uniscono aggiunte e tolte, e i brani solo del telefono restano al loro posto`() {
        val link = PlaylistLink("p1", "x", "Corsa", listOf("s1", "s2", "s3"))
        // Telefono: tolto s3, aggiunto un brano che il server non ha. PC: tolto s1, aggiunto s4.
        val lib = UserLibrary(playlists = listOf(UserPlaylist("p1", "Corsa", listOf(onlyPhone, numb, end))))
        val p = plan(lib, emptySet(), lists = listOf(ServerList("x", "Corsa", listOf("s2", "s3", "s4"))), state = SyncState(liked = emptySet(), playlists = listOf(link)))
        assertEquals(listOf<Write>(Write.Replace("x", null, listOf("s2", "s4"))), p.writes)
        assertEquals(listOf("local/track/9", "local/track/2", "remote/track/s4"), p.library.playlists.single().tracks.map { it.id })
    }

    @Test
    fun `rinominata sul telefono si rinomina sul server, eliminata da una parte sparisce dall'altra`() {
        val links = listOf(PlaylistLink("p1", "x", "Corsa", listOf("s1")), PlaylistLink("p2", "y", "Vecchia", listOf("s2")), PlaylistLink("p3", "z", "Del PC", listOf("s3")))
        val lib = UserLibrary(playlists = listOf(UserPlaylist("p1", "Corsa veloce", listOf(numb)), UserPlaylist("p3", "Del PC", emptyList())))
        val p = plan(
            lib, emptySet(),
            lists = listOf(ServerList("x", "Corsa", listOf("s1")), ServerList("y", "Vecchia", listOf("s2"))),
            state = SyncState(liked = emptySet(), playlists = links),
        )
        assertEquals(listOf<Write>(Write.Replace("x", "Corsa veloce", listOf("s1")), Write.Delete("y")), p.writes)
        // «Del PC» è stata eliminata dal PC: sparisce anche dal telefono.
        assertEquals(listOf("Corsa veloce"), p.library.playlists.map { it.name })
        assertEquals(listOf(PlaylistLink("p1", "x", "Corsa veloce", listOf("s1"))), p.state.playlists)
    }
}
