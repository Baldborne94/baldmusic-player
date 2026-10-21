package io.github.baldborne94.baldmusic.core

import io.github.baldborne94.baldmusic.core.library.FileNames
import io.github.baldborne94.baldmusic.core.library.LetterIndex
import io.github.baldborne94.baldmusic.core.library.LibraryHealth
import io.github.baldborne94.baldmusic.core.library.LocalCatalog
import io.github.baldborne94.baldmusic.core.library.LocalTrack
import io.github.baldborne94.baldmusic.core.library.MediaId
import io.github.baldborne94.baldmusic.core.library.TagProblem
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileNamesLettersTest {
    private fun n(file: String, title: String? = null, vararg artists: String) = FileNames.number(file, title, artists.toList())

    @Test
    fun `il numero di traccia si legge dai nomi numerati`() {
        assertEquals(FileNames.Number(3), n("03 - Titolo.mp3", "Titolo"))
        assertEquals(FileNames.Number(3), n("03. Titolo.mp3", "Titolo"))
        assertEquals(FileNames.Number(3), n("03 Titolo.mp3", "Titolo"))
        assertEquals(FileNames.Number(3), n("3 - Titolo.mp3", "Titolo"))
        assertEquals(FileNames.Number(3), n("(03) Titolo.flac", "Titolo"))
        assertEquals(FileNames.Number(7), n("7_Titolo.m4a", "Titolo"))
        assertEquals(FileNames.Number(12), n("12 - Artista - Titolo.mp3", "Titolo", "Artista"))
        // Un numero dentro il titolo: la traccia è quella davanti.
        assertEquals(FileNames.Number(2), n("02 - 99 Luftballons.mp3", "99 Luftballons"))
    }

    @Test
    fun `disco e traccia insieme`() {
        assertEquals(FileNames.Number(3, disc = 1), n("1-03 Titolo.flac", "Titolo"))
        assertEquals(FileNames.Number(5, disc = 2), n("2.05 - Titolo.mp3", "Titolo"))
        assertEquals(FileNames.Number(4, disc = 2), n("CD2 04 Titolo.mp3", "Titolo"))
        // "3 - 10 cose…" è la traccia 3, non il disco 3.
        assertEquals(FileNames.Number(3), n("3 - 10 cose che odio di te.mp3", "10 cose che odio di te"))
    }

    @Test
    fun `un numero del titolo o dell'artista non e un numero di traccia`() {
        // Senza tag Android usa il nome del file come titolo: "99 Luftballons" è il titolo.
        assertNull(n("99 Luftballons.mp3", "99 Luftballons"))
        assertNull(n("99 Luftballons (Remastered).mp3", "99 Luftballons"))
        assertNull(n("7 Rings.mp3", "7 rings"))
        assertNull(n("883 - Nord sud ovest est.mp3", "Nord sud ovest est", "883"))
        assertNull(n("50 Cent - In Da Club.mp3", "In Da Club", "50 Cent"))
        assertNull(n("3 Doors Down - Kryptonite.mp3", "Kryptonite", "3 Doors Down"))
        // Anni, nomi senza testo, indici enormi.
        assertNull(n("1979.mp3", null))
        assertNull(n("2005 - Titolo.mp3", "Titolo"))
        assertNull(n("22.mp3", "22"))
        assertNull(n("250 - Titolo.mp3", "Titolo"))
        assertNull(n("Artista - Titolo.mp3", "Titolo", "Artista"))
        assertNull(n("2Pac - Changes.mp3", "Changes"))
        assertNull(n("0 - Titolo.mp3", "Titolo"))
    }

    @Test
    fun `senza un titolo vero vale solo un numero sicuro`() {
        // Il titolo è il nome del file: "03 - Canzone" è numerato, "3 Canzone" potrebbe essere un titolo.
        assertEquals(FileNames.Number(3), n("03 - Canzone.mp3", "03 - Canzone"))
        assertEquals(FileNames.Number(3), n("03 Canzone.mp3", "03 Canzone"))
        assertNull(n("3 Canzone.mp3", "3 Canzone"))
        assertNull(n("3 Canzone.mp3", null))
    }

    private fun t(id: Long, title: String, file: String?, track: Int = 0, disc: Int = 0, album: String? = "Album") =
        LocalTrack(id, title, "Artista", 1, album, 1, 200_000, disc, track, 2001, 0, "Music/Album", fileName = file)

    @Test
    fun `un brano senza il tag prende il numero dal nome, i tag valgono di piu`() {
        val fromName = t(1, "Titolo", "04 - Titolo.mp3").withNumberFromName()
        assertEquals(4, fromName.track)
        assertTrue(fromName.numberFromName)
        assertTrue(fromName.details(tagsRead = true).any { it.first == "Traccia" && "nome del file" in it.second })
        val tagged = t(2, "Titolo", "04 - Titolo.mp3", track = 9).withNumberFromName()
        assertEquals(9, tagged.track)
        assertFalse(tagged.numberFromName)
        // Il disco dei tag resta, la traccia arriva dal nome.
        assertEquals(2 to 3, t(3, "Titolo", "1-03 Titolo.mp3", disc = 2).withNumberFromName().let { it.disc to it.track })
        assertEquals(0, t(4, "Titolo", null).withNumberFromName().track)
    }

    @Test
    fun `un album numerato solo nei nomi dei file va in ordine e non manca il numero`() {
        val tracks = listOf(
            t(1, "Alba", "03 - Alba.mp3"),
            t(2, "Zenit", "01 - Zenit.mp3"),
            t(3, "Meriggio", "02 - Meriggio.mp3"),
        ).map { it.withNumberFromName() }
        val catalog = LocalCatalog(streamUri = { "content://audio/$it" }, random = Random(1))
        val album = catalog.children(MediaId(LocalCatalog.KEY, LocalCatalog.ALBUMS, null), tracks).single()
        val inAlbum = catalog.children(MediaId.parse(album.mediaId)!!, tracks)
        assertEquals(listOf("Zenit", "Meriggio", "Alba"), inAlbum.map { it.title })
        assertEquals(listOf(1, 2, 3), inAlbum.map { it.trackNumber })
        assertEquals(0, LibraryHealth.check(tracks).counts[TagProblem.NO_TRACK_NUMBER] ?: 0)
    }

    @Test
    fun `le lettere a lato portano alla loro intestazione`() {
        // 2 elementi sopra l'elenco; A A B C C C: intestazioni A (2), B (5), C (7).
        val groups = listOf("A", "A", "B", "C", "C", "C")
        assertEquals(
            listOf(LetterIndex.Entry("A", 2), LetterIndex.Entry("B", 5), LetterIndex.Entry("C", 7)),
            LetterIndex.entries(groups, before = 2, minItems = 0),
        )
        // Una sezione non lettera ("I tuoi più ascoltati") ha la sua intestazione ma non sta nella colonna.
        val withTop = listOf("I tuoi più ascoltati", "#", "A", "B")
        assertEquals(listOf(LetterIndex.Entry("#", 2), LetterIndex.Entry("A", 4), LetterIndex.Entry("B", 6)), LetterIndex.entries(withTop, minItems = 0))
        assertEquals(4, LetterIndex.headers(withTop))
        assertEquals(3, LetterIndex.headers(groups))
        assertEquals(2, LetterIndex.headers(listOf(null, "A", "A", null, "B")))
        // Senza gruppo niente intestazione.
        assertEquals(listOf(LetterIndex.Entry("A", 1), LetterIndex.Entry("B", 3)), LetterIndex.entries(listOf(null, "A", "B"), minItems = 0))
    }

    @Test
    fun `niente lettere per elenchi corti o non divisi per lettera`() {
        assertTrue(LetterIndex.entries(List(10) { "A" }).isEmpty())
        assertTrue(LetterIndex.entries(List(100) { "Disco ${it / 50 + 1}" }).isEmpty())
        assertTrue(LetterIndex.entries(List(100) { "A" }).isEmpty())
        assertEquals(26, LetterIndex.entries(List(260) { "${'A' + it / 10}" }).size)
    }

    @Test
    fun `il dito sulla colonna sceglie la lettera`() {
        val entries = LetterIndex.entries(listOf("A", "B", "C", "D"), minItems = 0)
        assertEquals("A", LetterIndex.at(entries, 0f)?.letter)
        assertEquals("B", LetterIndex.at(entries, 0.3f)?.letter)
        assertEquals("D", LetterIndex.at(entries, 1f)?.letter)
        assertEquals("D", LetterIndex.at(entries, 1.5f)?.letter)
        assertEquals("A", LetterIndex.at(entries, -1f)?.letter)
        assertNull(LetterIndex.at(emptyList(), 0.5f))
    }
}
