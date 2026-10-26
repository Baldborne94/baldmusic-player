package io.github.baldborne94.baldmusic.core

import io.github.baldborne94.baldmusic.core.library.Autoplay
import io.github.baldborne94.baldmusic.core.library.ContainerRef
import io.github.baldborne94.baldmusic.core.library.ListeningSession
import io.github.baldborne94.baldmusic.core.library.QueueOrder
import io.github.baldborne94.baldmusic.core.library.QueueSnapshot
import io.github.baldborne94.baldmusic.core.library.UserLibrary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QueueOrderTest {
    @Test
    fun `partendo dall'ultimo brano di una raccolta suonano anche tutti quelli prima`() {
        assertEquals(listOf(4, 0, 1, 2, 3), QueueOrder.fromHere(5, 4))
        assertEquals(listOf(2, 3, 4, 0, 1), QueueOrder.fromHere(5, 2))
        // Dal primo (o senza un brano scelto) resta com'è.
        assertEquals(listOf(0, 1, 2), QueueOrder.fromHere(3, 0))
        assertEquals(listOf(0, 1, 2), QueueOrder.fromHere(3, -1))
        assertEquals(emptyList(), QueueOrder.fromHere(0, 0))
    }

    @Test
    fun `ricominciano da capo raccolte, playlist, preferiti e mix, non album e artisti`() {
        assertTrue(QueueOrder.wrapsAround("me/playlist/abc"))
        assertTrue(QueueOrder.wrapsAround("me/liked"))
        assertTrue(QueueOrder.wrapsAround("me/top"))
        assertTrue(QueueOrder.wrapsAround("me/mix/drive"))
        assertFalse(QueueOrder.wrapsAround("local/album/x"))
        assertFalse(QueueOrder.wrapsAround("local/artist/y"))
        assertFalse(QueueOrder.wrapsAround("podcast/show/z"))
        assertFalse(QueueOrder.wrapsAround(null))
    }
}

class QueueFixesTest {
    private val raccolta = "me/playlist/metal"
    private fun id(n: Int) = "local/track/$n|$raccolta"

    @Test
    fun `riproduci dopo col casuale va subito dopo quello in corso, aggiungi in coda in fondo`() {
        val old = listOf("a", "b", "c", "d")
        // «Riproduci dopo» con in corso «b» (indice 1): il brano nuovo entra all'indice 2.
        val next = listOf("a", "b", "x", "c", "d")
        val inserted = QueueOrder.insertion(old, next)
        assertEquals(2..2, inserted)
        // Il lettore l'aveva messo in fondo all'ordine casuale (3, 1, 0, 4, 2): ora è subito dopo «b».
        assertEquals(listOf(3, 1, 2, 0, 4), QueueOrder.shuffleAfterInsert(listOf(3, 1, 0, 4, 2), inserted!!, current = 1))
        // «Aggiungi in coda»: in fondo all'ordine, anche se il lettore l'aveva messo prima.
        val end = listOf("a", "b", "c", "d", "y", "z")
        assertEquals(4..5, QueueOrder.insertion(old, end))
        assertEquals(listOf(3, 1, 0, 2, 4, 5), QueueOrder.shuffleAfterInsert(listOf(3, 4, 1, 0, 5, 2), 4..5, current = 1))
        // Coda nuova, brani tolti o spostati: non è un'aggiunta.
        assertEquals(null, QueueOrder.insertion(old, listOf("x", "y", "z", "w", "v")))
        assertEquals(null, QueueOrder.insertion(old, listOf("a", "c")))
        assertEquals(null, QueueOrder.insertion(old, listOf("b", "a", "c", "d", "e")))
        assertEquals(null, QueueOrder.insertion(emptyList(), listOf("a")))
    }

    @Test
    fun `continua ad ascoltare non riaggiunge un brano saltato`() {
        var s = ListeningSession().startingOn(raccolta, 0)
        s = s.left(id(2), 10_000, 240_000, forward = true, ended = false, now = 1)
        // Il saltato è uscito dalla coda: la coda da sola non basta a tenerlo fuori.
        val queue = listOf(id(1), id(3))
        val radio = listOf("local/track/2|local/radio/3", "local/track/7|local/radio/3", "local/track/1|local/radio/3")
        assertEquals(listOf(0, 1), Autoplay.pick(radio, queue))
        assertEquals(listOf(1), Autoplay.pick(radio, s.notAgain(queue)))
    }

    @Test
    fun `un brano saltato non conta come ascoltato, ma la raccolta va negli ascoltati di recente`() {
        // 25 s di un brano di 4 minuti: prima contava (dopo 20 s), ora è un salto.
        assertFalse(ListeningSession.countsAsPlay(25_000, 240_000))
        assertTrue(ListeningSession.countsAsPlay(180_000, 240_000))
        assertTrue(ListeningSession.countsAsPlay(30_000, null))
        val c = ContainerRef(raccolta, "Metal", kind = "PLAYLIST")
        val lib = UserLibrary().touchRecent(c)
        assertEquals(listOf(raccolta), lib.recent.map { it.id })
        assertTrue(lib.plays.isEmpty())
        assertTrue(lib.touchRecent(c) === lib)
    }

    @Test
    fun `riaprendo l'app l'ordine del casuale resta lo stesso`() {
        val ids = listOf("a", "b", "c", "d", "e")
        val snap = QueueSnapshot.of(ids, index = 2, positionMs = 1_000, shuffle = true, repeat = 0, order = listOf(4, 2, 0, 3, 1))!!
        val back = QueueSnapshot.fromJson(snap.toJson())!!
        assertEquals(listOf(4, 2, 0, 3, 1), back.orderIn(ids))
        // Sparito «d» (indice 3): esce dall'ordine, gli altri tengono il loro posto.
        assertEquals(listOf(3, 2, 0, 1), back.orderIn(listOf("a", "b", "c", "e")))
        // Senza casuale non si salva un ordine.
        assertEquals(null, QueueSnapshot.of(ids, 2, 0, shuffle = false, repeat = 0, order = listOf(0, 1, 2, 3, 4))!!.order)
        // Coda lunga accorciata a 500: l'ordine resta tra i brani tenuti.
        val many = (0 until 600).map { "t$it" }
        val trimmed = QueueSnapshot.of(many, index = 550, positionMs = 0, shuffle = true, repeat = 0, order = (599 downTo 0).toList())!!
        assertEquals(500, trimmed.order!!.size)
        assertEquals(trimmed.ids.indices.reversed().toList(), trimmed.order)
    }
}
