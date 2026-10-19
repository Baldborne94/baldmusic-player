package io.github.baldborne94.baldmusic.core

import io.github.baldborne94.baldmusic.core.library.LibraryNode
import io.github.baldborne94.baldmusic.core.library.LibraryNode.Kind
import io.github.baldborne94.baldmusic.core.library.QueueSnapshot
import io.github.baldborne94.baldmusic.core.library.VoiceQuery
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VoiceQueueTest {

    // --- Richieste vocali ---

    @Test
    fun `le parole sul tipo si tolgono e dicono cosa cercare`() {
        assertEquals("Disney Italia" to Kind.PLAYLIST, VoiceQuery.parse("la raccolta Disney Italia"))
        assertEquals("Disney Italia" to Kind.PLAYLIST, VoiceQuery.parse("  La Raccolta   Disney Italia "))
        assertEquals("Movie Soundtracks" to Kind.PLAYLIST, VoiceQuery.parse("raccolta Movie Soundtracks"))
        assertEquals("Viaggio" to Kind.PLAYLIST, VoiceQuery.parse("la playlist Viaggio"))
        assertEquals("Meteora" to Kind.ALBUM, VoiceQuery.parse("l'album Meteora"))
        assertEquals("Meteora" to Kind.ALBUM, VoiceQuery.parse("l’album Meteora"))
        assertEquals("Meteora" to Kind.ALBUM, VoiceQuery.parse("il disco Meteora"))
        assertEquals("Linkin Park" to Kind.ARTIST, VoiceQuery.parse("l'artista Linkin Park"))
        assertEquals("Linkin Park" to Kind.ARTIST, VoiceQuery.parse("L'Artista Linkin Park"))
    }

    @Test
    fun `un nome che comincia con un articolo non e un tipo`() {
        assertEquals("Il Volo" to null, VoiceQuery.parse("Il Volo"))
        assertEquals("La Playlist" to null, VoiceQuery.parse("La Playlist"))
        assertEquals("Albume" to null, VoiceQuery.parse("Albume"))
    }

    private fun node(title: String, kind: Kind) = LibraryNode("x/$kind/$title", title, kind)

    @Test
    fun `maiuscole, accenti e apostrofi diversi non contano`() {
        val results = listOf(node("Canzoni D’Amore", Kind.PLAYLIST), node("Città", Kind.ALBUM))
        assertEquals("Canzoni D’Amore", VoiceQuery.pick("la raccolta canzoni d'amore", results)?.title)
        assertEquals("Canzoni D’Amore", VoiceQuery.pick("CANZONI D'AMORE", results)?.title)
        assertEquals("Città", VoiceQuery.pick("l'album citta", results)?.title)
    }

    @Test
    fun `a nome uguale vince la raccolta, poi l'artista, poi l'album`() {
        val results = listOf(node("Queen", Kind.ALBUM), node("Queen", Kind.ARTIST), node("Queen", Kind.TRACK))
        assertEquals(Kind.ARTIST, VoiceQuery.pick("queen", results)?.kind)
        assertEquals(Kind.PLAYLIST, VoiceQuery.pick("queen", results + node("Queen", Kind.PLAYLIST))?.kind)
        assertEquals(Kind.ALBUM, VoiceQuery.pick("l'album queen", results)?.kind)
    }

    @Test
    fun `detto il tipo senza niente di quel tipo si suonano i brani trovati`() {
        val results = listOf(node("Linkin Park", Kind.ARTIST), node("Numb", Kind.TRACK))
        assertNull(VoiceQuery.pick("la raccolta linkin park", results))
        assertNull(VoiceQuery.pick("la raccolta", results))
        assertNull(VoiceQuery.pick("   ", results))
    }

    // --- Coda salvata ---

    private fun ids(n: Int) = (1..n).map { "local/track/$it|local/album/1" }

    @Test
    fun `una coda lunga col brano in fondo tiene comunque 500 brani`() {
        val snap = QueueSnapshot.of(ids(2000), 1990, 5_000, shuffle = false, repeat = 2)!!
        assertEquals(QueueSnapshot.MAX, snap.ids.size)
        assertEquals("local/track/1991|local/album/1", snap.currentId)
        assertEquals("local/track/2000|local/album/1", snap.ids.last())
        assertEquals(5_000, snap.positionMs)
    }

    @Test
    fun `una coda lunga col brano all'inizio parte dal primo`() {
        val snap = QueueSnapshot.of(ids(800), 10, 0, shuffle = true, repeat = 0)!!
        assertEquals(QueueSnapshot.MAX, snap.ids.size)
        assertEquals(10, snap.index)
        assertEquals("local/track/11|local/album/1", snap.currentId)
        assertEquals(true, snap.shuffle)
    }

    @Test
    fun `una coda di 500 esatti resta intera`() {
        val snap = QueueSnapshot.of(ids(500), 499, 0, false, 0)!!
        assertEquals(ids(500), snap.ids)
        assertEquals(499, snap.index)
    }

    @Test
    fun `un indice fuori dalla coda non si salva e non si rilegge`() {
        assertNull(QueueSnapshot.of(ids(3), 3, 0, false, 0))
        assertNull(QueueSnapshot.of(ids(3), -1, 0, false, 0))
        assertNull(QueueSnapshot.fromJson("""{"ids":["a"],"index":4,"positionMs":0}"""))
        assertNull(QueueSnapshot.fromJson(null))
        // Campi sconosciuti (di una versione più nuova) non rompono la lettura.
        assertEquals("a", QueueSnapshot.fromJson("""{"ids":["a"],"index":0,"positionMs":7,"nuovo":true}""")?.currentId)
    }

    @Test
    fun `lo stesso brano due volte in coda riparte da quello in corso`() {
        // Lo stesso brano aggiunto in coda una seconda volta: si stava ascoltando il secondo.
        val queue = listOf("local/track/1|local/album/1", "local/track/2|local/album/1", "local/track/1|local/album/1", "local/track/3|local/album/1")
        val snap = QueueSnapshot.of(queue, 2, 60_000, false, 0)!!
        assertEquals(2, snap.indexIn(queue))
        // Sparito il brano 2, il secondo "1" ora è al posto 1.
        assertEquals(1, snap.indexIn(queue - "local/track/2|local/album/1"))
    }

    @Test
    fun `spariti il brano in corso e i seguenti si riparte dall'inizio`() {
        val snap = QueueSnapshot.of(ids(5), 3, 0, false, 0)!!
        assertEquals(0, snap.indexIn(ids(3)))
        assertEquals(1, snap.indexIn(listOf(ids(5)[0], ids(5)[4])))
    }
}
