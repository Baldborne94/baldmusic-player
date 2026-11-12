package io.github.baldborne94.baldmusic.core

import io.github.baldborne94.baldmusic.core.library.DownloadedSet
import io.github.baldborne94.baldmusic.core.library.PlayCount
import io.github.baldborne94.baldmusic.core.library.ServerDownloads
import io.github.baldborne94.baldmusic.core.library.ServerSync
import io.github.baldborne94.baldmusic.core.library.SyncState
import io.github.baldborne94.baldmusic.core.library.TrackRef
import io.github.baldborne94.baldmusic.core.library.UserLibrary
import io.github.baldborne94.baldmusic.core.library.UserPlaylist
import io.github.baldborne94.baldmusic.core.library.moved
import io.github.baldborne94.baldmusic.core.subsonic.Song
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Dal PC al NAS: lo stesso brano ha un altro ID sul server nuovo. */
class NewServerTest {
    // Sul NAS (server nuovo): gli stessi brani del PC, con ID nuovi.
    private val nas = listOf(
        Song("n1", "Numb", artist = "Linkin Park", album = "Meteora", albumId = "al-n", duration = 185, coverArt = "al-n"),
        Song("n2", "In the End", artist = "Linkin Park", album = "Hybrid Theory", albumId = "al-h", duration = 216),
    )
    private val match = ServerSync.Matcher(nas, emptyList())
    // Sul telefono: brani del PC (ID vecchi), uno che sul NAS non c'è.
    private val numbPc = TrackRef("remote/track/p1", "Numb", "Linkin Park", durationMs = 185_000)
    private val endPc = TrackRef("remote/track/p2", "In the End", "Linkin Park", durationMs = 216_000)
    private val gone = TrackRef("remote/track/p9", "Solo sul PC", "Nessuno", durationMs = 100_000)

    @Test
    fun `un brano del server di prima si ritrova sul nuovo per artista, titolo e durata`() {
        assertEquals("n1", match.toServer(numbPc))
        assertNull(match.toServer(gone))
        assertTrue(match.has("n1"))
    }

    @Test
    fun `preferiti, playlist e ascolti passano ai brani del server nuovo`() {
        val lib = UserLibrary(
            liked = listOf(numbPc, gone),
            playlists = listOf(UserPlaylist("p", "Corsa", listOf(endPc, numbPc))),
            plays = mapOf(numbPc.id to PlayCount(numbPc, 7, 1)),
        )
        val moves = match.moved(lib)
        assertEquals(setOf("remote/track/p1", "remote/track/p2"), moves.keys)
        val after = lib.mergeTracks(moves)
        assertEquals(listOf("remote/track/n1", "remote/track/p9"), after.liked.map { it.id })
        assertEquals(listOf("remote/track/n2", "remote/track/n1"), after.playlists.single().tracks.map { it.id })
        assertEquals(7, after.plays.getValue("remote/track/n1").count)
        // Dopo, l'allineamento non chiede più al server brani che non ha.
        val plan = ServerSync.plan(after, emptySet(), emptyList(), SyncState(), match, { "x" }, 0)
        assertEquals(setOf("n1"), plan.star)
    }

    @Test
    fun `gli album scaricati passano al server nuovo senza riscaricare i file`() {
        val old = ServerDownloads().with(
            DownloadedSet(
                "remote/album/al-pc", "Meteora", "Linkin Park", "al-pc",
                listOf(Song("p1", "Numb", artist = "Linkin Park", duration = 185), Song("p9", "Solo sul PC", artist = "Nessuno", duration = 100)),
            ),
        ).with(DownloadedSet("remote/playlist/pl-pc", "Corsa", songs = listOf(Song("p2", "In the End", artist = "Linkin Park", duration = 216))))
        val (moved, renames) = old.movedTo(match, playlists = mapOf("corsa" to "pl-nas"))
        assertEquals(mapOf("p1" to "n1", "p2" to "n2"), renames)
        assertEquals(listOf("remote/album/al-n", "remote/playlist/pl-nas"), moved.sets.map { it.container })
        // Quello che il NAS non ha resta e si ascolta dal file.
        assertEquals(listOf("n1", "p9"), moved.sets[0].songs.map { it.id })
        assertEquals("al-n", moved.sets[0].coverArt)
        // Niente di cambiato se il server è lo stesso.
        assertEquals(emptyMap(), moved.movedTo(match, emptyMap()).second)
    }
}
