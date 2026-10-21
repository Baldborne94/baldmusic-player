package io.github.baldborne94.baldmusic.core

import io.github.baldborne94.baldmusic.core.library.QueueSnapshot
import io.github.baldborne94.baldmusic.core.library.RecentSearches
import kotlin.test.Test
import kotlin.test.assertEquals

/** Lavoro risparmiato: ricerche fatte una volta, code lunghe lette solo dove serve. */
class LightWorkTest {
    @Test
    fun `una ricerca si fa una volta sola e se ne tengono poche`() {
        val searches = RecentSearches<String>(max = 3)
        var started = 0
        val first = searches.getOrPut("queen") { started++; "risultati" }
        // L'app chiede prima di cercare e poi i risultati: la seconda volta non si cerca di nuovo.
        assertEquals(first, searches.getOrPut("queen") { started++; "di nuovo" })
        assertEquals(1, started)
        listOf("q", "qu", "que", "quee").forEach { searches.getOrPut(it) { it } }
        assertEquals(3, searches.size)
        // "queen" era la più vecchia: è uscita.
        assertEquals("di nuovo", searches.getOrPut("queen") { "di nuovo" })
        searches.clear()
        assertEquals(0, searches.size)
    }

    @Test
    fun `la coda salvata legge solo i brani che tiene`() {
        // Una coda di 20.000 brani letta dal player un brano alla volta.
        var read = 0
        val queue = object : AbstractList<String>() {
            override val size = 20_000
            override fun get(index: Int): String { read++; return "local/track/$index" }
        }
        val snap = QueueSnapshot.of(queue, 10_000, 0, false, 0)!!
        // Letti subito e una volta sola i 500 tenuti: è una copia, non dipende più dal player.
        assertEquals(500, read)
        assertEquals(500, snap.ids.size)
        assertEquals("local/track/10000", snap.currentId)
        assertEquals(500, read)
    }
}
