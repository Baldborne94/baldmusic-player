package io.github.baldborne94.baldmusic.core

import io.github.baldborne94.baldmusic.core.library.LibraryNode
import io.github.baldborne94.baldmusic.core.library.LibraryNode.Kind
import io.github.baldborne94.baldmusic.core.library.VoiceQuery
import io.github.baldborne94.baldmusic.core.library.VoiceQuery.SongAsk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** «Ok Google, metti Numb dei Linkin Park su BaldMusic»: un brano solo, per titolo e artista. */
class VoiceSongTest {
    private fun song(id: String, title: String, artist: String) =
        LibraryNode("local/track/$id", title, Kind.TRACK, subtitle = artist, artist = artist)

    // Quello che la ricerca del telefono trova cercando «Numb»: anche altri brani con «numb» nel titolo.
    private val found = listOf(
        song("1", "Numb Little Bug", "Em Beihold"),
        song("2", "Numb (Live)", "Linkin Park"),
        song("3", "Numb", "Linkin Park"),
        song("4", "Numb", "Linkin Park feat. Jay-Z"),
    )

    @Test
    fun `titolo e artista divisi da di, dei, by o un trattino`() {
        // Prima la ricerca intera non trovava niente: nessun titolo contiene «Numb dei Linkin Park».
        assertEquals(SongAsk("Numb", "Linkin Park"), VoiceQuery.songAsks("Numb dei Linkin Park").first())
        assertEquals(SongAsk("Albachiara", "Vasco Rossi"), VoiceQuery.songAsks("Albachiara di Vasco Rossi").first())
        assertEquals(SongAsk("Numb", "Linkin Park"), VoiceQuery.songAsks("Numb by Linkin Park").first())
        assertEquals(SongAsk("Numb", "Linkin Park"), VoiceQuery.songAsks("Numb - Linkin Park").first())
        assertEquals(SongAsk("Numb", "Linkin Park"), VoiceQuery.songAsks("la canzone Numb dei Linkin Park").first())
    }

    @Test
    fun `senza parole in mezzo si prova con l'artista nelle ultime parole`() {
        val asks = VoiceQuery.songAsks("Numb Linkin Park")
        assertTrue(SongAsk("Numb", "Linkin Park") in asks)
        assertTrue(asks.size <= VoiceQuery.MAX_SONG_ASKS)
    }

    @Test
    fun `titolo e artista mandati dall'Assistente vengono prima`() {
        assertEquals(SongAsk("Numb", "Linkin Park"), VoiceQuery.songAsks("numb linkin park", title = "Numb", artist = "Linkin Park").first())
    }

    @Test
    fun `il brano giusto per primo, dell'artista chiesto`() {
        val picked = VoiceQuery.songsFor(SongAsk("Numb", "Linkin Park"), found)
        assertEquals(listOf("3", "4", "2"), picked.map { it.mediaId.substringAfterLast('/') })
        // «dei Linkin Park», «i Queen»: l'articolo davanti all'artista non conta.
        assertEquals("3", VoiceQuery.songsFor(SongAsk("Numb", "i Linkin Park"), found).first().mediaId.substringAfterLast('/'))
        // Un altro artista: niente (si prova la divisione dopo, invece di suonare il brano sbagliato).
        assertTrue(VoiceQuery.songsFor(SongAsk("Numb", "Vasco Rossi"), found).isEmpty())
    }

    @Test
    fun `solo il titolo, quello uguale prima di quelli che lo contengono`() {
        assertEquals("3", VoiceQuery.rankSongs("Numb", found).first().mediaId.substringAfterLast('/'))
    }
}
