package io.github.baldborne94.baldmusic.core

import io.github.baldborne94.baldmusic.core.library.ServerSync
import io.github.baldborne94.baldmusic.core.library.SyncState
import io.github.baldborne94.baldmusic.core.library.UserLibrary
import io.github.baldborne94.baldmusic.core.subsonic.Song
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * «Ricomincia dal NAS» (scelta dell'utente: «voglio che prenda dal NAS e rifaccia l'ambiente
 * pulito»): la tua musica sul telefono si svuota e si dimentica l'ultimo allineamento. Il giro
 * dopo deve solo riportare dal server, mai togliere niente sul server.
 */
class StartOverTest {
    private val nas = listOf(
        Song(id = "n1", title = "Numb", artist = "Linkin Park", duration = 185),
        Song(id = "n2", title = "Faint", artist = "Linkin Park", duration = 162),
    )
    private val lists = listOf(ServerSync.ServerList("s1", "Macchina", listOf("n1", "n2")))

    @Test
    fun `dopo aver ricominciato il server non perde niente e il telefono si riempie dal server`() {
        val plan = ServerSync.plan(UserLibrary(), starred = setOf("n1"), lists = lists, state = SyncState(), match = ServerSync.Matcher(nas, emptyList()), newId = { "p" }, now = 1L)
        // Sul server: nessun cuore tolto, nessuna playlist cancellata o riscritta.
        assertTrue(plan.unstar.isEmpty())
        assertTrue(plan.writes.none { it is ServerSync.Write.Delete || it is ServerSync.Write.Replace }, plan.writes.toString())
        // Sul telefono: i «mi piace» e le playlist del server, coi brani del server.
        assertEquals(listOf("remote/track/n1"), plan.library.liked.map { it.id })
        val macchina = plan.library.playlists.single { it.name == "Macchina" }
        assertEquals(listOf("remote/track/n1", "remote/track/n2"), macchina.tracks.map { it.id })
    }
}
