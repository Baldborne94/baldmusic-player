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

    private fun play(t: TrackRef, count: Int, at: Long = 100) = io.github.baldborne94.baldmusic.core.library.PlayCount(t, count, at)

    @Test
    fun `la prima volta si mandano gli ascolti dei file, non quelli gia sentiti dal server`() {
        val remote = TrackRef("remote/track/s3", "Bohemian Rhapsody", "Queen", durationMs = 355_000)
        val lib = UserLibrary(plays = mapOf(numb.id to play(numb, 2, at = 500), remote.id to play(remote, 4), onlyPhone.id to play(onlyPhone, 9)))
        val songs = server.map { if (it.id == "s3") it.copy(playCount = 6) else it }
        val p = ServerSync.planPlays(lib, songs, pushedBefore = null, match = match)
        // Numb (file) 2 volte; Bohemian Rhapsody il server l'aveva già; il brano solo del telefono non c'è.
        assertEquals(listOf("s1" to 500L, "s1" to 500L), p.scrobbles)
        assertEquals(mapOf("s1" to 2, "s3" to 4), p.pushed)
        // Sul server 6 ascolti di Bohemian Rhapsody, 4 erano del telefono: 2 fatti dal PC.
        assertEquals(mapOf("remote/track/s3" to 2), p.others.mapValues { it.value.count })
    }

    @Test
    fun `poi si manda solo quello che manca, e gli ascolti dal PC vanno sul file del telefono`() {
        val lib = UserLibrary(plays = mapOf(numb.id to play(numb, 5)))
        val songs = server.map { if (it.id == "s1") it.copy(playCount = 10, played = "2026-11-01T20:00:00Z") else it }
        val p = ServerSync.planPlays(lib, songs, pushedBefore = mapOf("s1" to 3), match = match)
        assertEquals(2, p.scrobbles.size)
        assertEquals(5, p.pushed["s1"])
        // 10 sul server, 3 erano del telefono: 7 dal PC, sul file del telefono.
        val other = p.others.getValue("local/track/1")
        assertEquals(7, other.count)
        assertEquals(1_793_563_200_000L, other.lastPlayed)
    }

    @Test
    fun `classifiche e mix contano anche gli ascolti fatti dal PC`() {
        val lib = UserLibrary(
            plays = mapOf(numb.id to play(numb, 2), end.id to play(end, 3)),
            otherPlays = mapOf(numb.id to play(numb, 4, at = 900)),
        )
        assertEquals(listOf("local/track/1", "local/track/2"), lib.topTracks().map { it.id })
        assertEquals(6, lib.allPlays.getValue(numb.id).count)
        assertEquals(900L, lib.allPlays.getValue(numb.id).lastPlayed)
        assertEquals(9, io.github.baldborne94.baldmusic.core.library.ListeningStats.from(lib).totalPlays)
        // Gli ascolti del telefono restano i suoi (il backup e l'invio al server guardano solo quelli).
        assertEquals(2, lib.plays.getValue(numb.id).count)
    }
}
