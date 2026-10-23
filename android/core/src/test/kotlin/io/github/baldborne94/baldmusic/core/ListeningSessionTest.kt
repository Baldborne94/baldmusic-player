package io.github.baldborne94.baldmusic.core

import io.github.baldborne94.baldmusic.core.library.ListeningSession
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ListeningSessionTest {
    private val raccolta = "me/playlist/metal"
    private fun id(n: Int) = "local/track/$n|$raccolta"
    private val ids = (1..6).map(::id)
    private val min = 60_000L

    private fun session() = ListeningSession().startingOn(raccolta, 0)

    @Test
    fun `saltato se lasciato presto andando avanti, sentito se finito o quasi`() {
        var s = session()
        s = s.left(id(1), 20_000, 4 * min, forward = true, ended = false, now = 1)
        assertTrue(s.wasSkipped(id(1)))
        // Finito da solo, o lasciato negli ultimi 30 s (anche con la dissolvenza): sentito.
        s = s.left(id(2), 4 * min, 4 * min, forward = false, ended = true, now = 2)
        s = s.left(id(3), 4 * min - 10_000, 4 * min, forward = true, ended = false, now = 3)
        assertEquals(setOf("local/track/2", "local/track/3"), s.heard)
        // «Precedente» presto non è un salto.
        s = s.left(id(4), 5_000, 4 * min, forward = false, ended = false, now = 4)
        assertFalse(s.wasSkipped(id(4)))
        // Lo stesso ID da un altro contenitore è lo stesso brano.
        assertTrue(s.wasSkipped("local/track/1|me/liked"))
        // Un brano di un altro contenitore (aggiunto da «Continua ad ascoltare», una puntata) non conta.
        assertFalse(s.left("local/track/9|local/radio/1", 5_000, 4 * min, forward = true, ended = false, now = 5).wasSkipped("local/track/9"))
        assertFalse(s.left("podcast/track/a~b", 5_000, 4 * min, forward = true, ended = false, now = 5).wasSkipped("podcast/track/a~b"))
        assertTrue(ListeningSession.isSkip(10_000, null))
        assertFalse(ListeningSession.isSkip(40_000, null))
    }

    @Test
    fun `toccando un altro brano della stessa raccolta saltati e sentiti non tornano`() {
        var s = session()
        s = s.left(id(1), 10_000, 4 * min, forward = true, ended = false, now = 1)
        s = s.left(id(2), 4 * min, 4 * min, forward = false, ended = true, now = 2)
        s = s.startingOn(raccolta, 3)
        // Scelto il 4: restano il 3, il 4 (punto di partenza), il 5 e il 6.
        assertEquals(listOf(2, 3, 4, 5) to 1, s.filter(ids, 3))
        // Scelto proprio uno saltato: suona lo stesso, l'hai chiesto tu.
        assertEquals(listOf(0, 2, 3, 4, 5) to 0, s.filter(ids, 0))
        // «Riproduci» o casuale sulla raccolta (nessun brano scelto).
        assertEquals(listOf(2, 3, 4, 5) to -1, s.filter(ids, -1))
    }

    @Test
    fun `un altro contenitore o due ore dopo si ricomincia`() {
        var s = session().left(id(1), 10_000, 4 * min, forward = true, ended = false, now = 1_000)
        assertTrue(s.startingOn(raccolta, 2_000).wasSkipped(id(1)))
        assertFalse(s.startingOn("me/playlist/altra", 2_000).wasSkipped(id(1)))
        assertFalse(s.startingOn(raccolta, 1_000 + ListeningSession.GAP_MS + 1).wasSkipped(id(1)))
        // Raccolta sentita tutta: si riascolta intera.
        s = ids.fold(session()) { acc, t -> acc.left(t, 4 * min, 4 * min, forward = false, ended = true, now = 5) }
        assertEquals(ids.indices.toList() to -1, s.filter(ids, -1))
        // Una puntata o un brano sciolto (senza contenitore): niente da ricordare.
        assertEquals(ListeningSession(), ListeningSession().startingOn(null, 0).left(id(1), 1, 4 * min, true, false, 1).copy(lastAt = 0))
    }

    @Test
    fun `casuale rifatto a meta - i gia sentiti restano dietro, gli altri dopo quello in corso`() {
        var s = session()
        s = s.left(id(1), 4 * min, 4 * min, forward = false, ended = true, now = 1)
        s = s.left(id(5), 4 * min, 4 * min, forward = false, ended = true, now = 2)
        val order = s.shuffleOrder(ids, current = 2, random = Random(7)).toList()
        assertEquals(setOf(0, 4), order.take(2).toSet())
        assertEquals(2, order[2])
        assertEquals(setOf(1, 3, 5), order.drop(3).toSet())
    }

    @Test
    fun `spento il casuale i gia sentiti dopo quello in corso vanno prima`() {
        var s = session()
        s = s.left(id(4), 4 * min, 4 * min, forward = false, ended = true, now = 1)
        s = s.left(id(6), 4 * min, 4 * min, forward = false, ended = true, now = 2)
        val moves = s.linearMoves(ids, current = 1)
        // Si rifanno le mosse come il player: 4 e 6 finiscono prima del 2, che resta il brano in corso.
        val list = ids.toMutableList()
        var current = 1
        moves.forEach { (from, to) -> list.add(to, list.removeAt(from)); if (to <= current) current++ }
        assertEquals(listOf(id(1), id(4), id(6), id(2), id(3), id(5)), list)
        assertEquals(id(2), list[current])
    }
}
