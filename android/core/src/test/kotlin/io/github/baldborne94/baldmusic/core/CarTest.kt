package io.github.baldborne94.baldmusic.core

import io.github.baldborne94.baldmusic.core.library.LibraryNode
import io.github.baldborne94.baldmusic.core.library.LibraryNode.Kind
import io.github.baldborne94.baldmusic.core.library.QueueSnapshot
import io.github.baldborne94.baldmusic.core.library.VoiceQuery
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CarTest {
    private fun ids(n: Int) = (1..n).map { "local/track/$it|me/playlist/p" }

    @Test
    fun `la coda si salva e si rilegge uguale`() {
        val snap = QueueSnapshot.of(ids(10), 3, 42_000, shuffle = true, repeat = 2)!!
        assertEquals(snap, QueueSnapshot.fromJson(snap.toJson()))
        assertEquals("local/track/4|me/playlist/p", snap.currentId)
        assertNull(QueueSnapshot.fromJson("{rotto"))
        assertNull(QueueSnapshot.of(emptyList(), 0, 0, false, 0))
    }

    @Test
    fun `una coda lunghissima tiene i brani vicini a quello in corso`() {
        val snap = QueueSnapshot.of(ids(2000), 1500, 0, false, 0)!!
        assertEquals(QueueSnapshot.MAX, snap.ids.size)
        assertEquals("local/track/1501|me/playlist/p", snap.currentId)
        assertEquals(50, snap.index)
    }

    @Test
    fun `se il brano in corso non c'e piu si riparte dal successivo`() {
        val snap = QueueSnapshot.of(ids(5), 2, 0, false, 0)!!
        val found = ids(5).filterNot { it.startsWith("local/track/3|") }
        assertEquals(2, snap.indexIn(found))
        assertEquals(1, snap.indexIn(listOf("local/track/1|me/playlist/p", "local/track/3|me/playlist/p")))
    }

    private fun node(title: String, kind: Kind) = LibraryNode("x/$title", title, kind)

    @Test
    fun `una raccolta detta per nome si suona per intera`() {
        val results = listOf(node("Movie Soundtracks", Kind.PLAYLIST), node("Movie Soundtracks", Kind.ALBUM), node("Brano", Kind.TRACK))
        assertEquals(Kind.PLAYLIST, VoiceQuery.pick("movie soundtracks", results)?.kind)
        assertEquals(Kind.ALBUM, VoiceQuery.pick("l'album movie soundtracks", results)?.kind)
    }

    @Test
    fun `detto il tipo basta una parte del nome`() {
        val results = listOf(node("Disney Italia _ Tutte Le Migliori Canzoni", Kind.PLAYLIST), node("Disney Hits", Kind.ALBUM))
        assertEquals("Disney Italia _ Tutte Le Migliori Canzoni", VoiceQuery.pick("la raccolta disney", results)?.title)
        assertNull(VoiceQuery.pick("disney", results))
    }

    @Test
    fun `un artista detto per nome esatto vince sui brani`() {
        val results = listOf(node("Linkin Park", Kind.ARTIST), node("In the End", Kind.TRACK))
        assertEquals(Kind.ARTIST, VoiceQuery.pick("Linkin Park", results)?.kind)
        assertNull(VoiceQuery.pick("in the end", results))
    }
}
