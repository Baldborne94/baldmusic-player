package io.github.baldborne94.baldmusic.core

import io.github.baldborne94.baldmusic.core.library.PlayCount
import io.github.baldborne94.baldmusic.core.library.ServerSync
import io.github.baldborne94.baldmusic.core.library.TrackRef
import io.github.baldborne94.baldmusic.core.library.UserLibrary
import io.github.baldborne94.baldmusic.core.library.UserPlaylist
import io.github.baldborne94.baldmusic.core.library.gone
import io.github.baldborne94.baldmusic.core.subsonic.Song
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Musica cancellata dal telefono dopo il passaggio al NAS (resoconto dell'utente): playlist, cuori
 * e più ascoltati puntavano ancora ai file del telefono e toccandoli non partiva niente.
 */
class GonePhoneFilesTest {
    private val numb = TrackRef("local/track/1", "Numb", "Linkin Park", durationMs = 185_000)
    private val faint = TrackRef("local/track/2", "Faint", "Linkin Park", durationMs = 162_000)
    private val rare = TrackRef("local/track/3", "Demo di casa", "Nessuno", durationMs = 100_000)
    private val nas = listOf(
        Song(id = "n1", title = "Numb", artist = "Linkin Park", duration = 186),
        Song(id = "n2", title = "Faint", artist = "Linkin Park", duration = 162),
    )
    private val lib = UserLibrary(
        liked = listOf(numb),
        playlists = listOf(UserPlaylist("p1", "Macchina", listOf(numb, faint, rare))),
        plays = mapOf(numb.id to PlayCount(numb, 12, 1L)),
    )

    @Test
    fun `un file sparito dal telefono passa allo stesso brano del server`() {
        // Faint c'è ancora sul telefono, Numb no.
        val moves = ServerSync.Matcher(nas, listOf(faint)).gone(lib, phoneIds = setOf(faint.id))
        assertEquals(setOf("local/track/1"), moves.keys)
        assertEquals("remote/track/n1", moves.getValue("local/track/1").id)
    }

    @Test
    fun `senza lo stesso brano sul server resta com'e`() {
        val moves = ServerSync.Matcher(nas, emptyList()).gone(lib, phoneIds = emptySet())
        assertTrue("local/track/3" !in moves)
        // Tutti cancellati: anche Faint passa al server.
        assertEquals("remote/track/n2", moves.getValue("local/track/2").id)
    }

    @Test
    fun `i file ancora sul telefono non si toccano`() {
        val moves = ServerSync.Matcher(nas, listOf(numb, faint)).gone(lib, phoneIds = setOf(numb.id, faint.id))
        assertTrue(moves.isEmpty())
    }
}
