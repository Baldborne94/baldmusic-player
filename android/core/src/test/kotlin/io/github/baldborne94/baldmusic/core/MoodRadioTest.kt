package io.github.baldborne94.baldmusic.core

import io.github.baldborne94.baldmusic.core.library.LibraryNode
import io.github.baldborne94.baldmusic.core.library.LocalCatalog
import io.github.baldborne94.baldmusic.core.library.LocalTrack
import io.github.baldborne94.baldmusic.core.library.MediaId
import io.github.baldborne94.baldmusic.core.library.MoodRadio
import io.github.baldborne94.baldmusic.core.library.MoodRadio.Song
import io.github.baldborne94.baldmusic.core.library.QueueOrder
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MoodRadioTest {
    private var next = 1L
    private fun song(artist: String, raccolta: String?, genre: String? = null, gain: Float? = null, decade: Int? = null) =
        Song(next++, artist, raccolta, MoodRadio.genres(genre), decade, gain)

    @Test
    fun `dopo un brano metal vengono la sua raccolta e quelle vicine, non i cartoni`() {
        val seed = song("Slipknot", "Music/Metal")
        val metal = listOf(song("System Of A Down", "Music/Metal"), song("Rammstein", "Music/Metal"), song("Nightwish", "Music/Metal"))
        // Una raccolta che ha artisti in comune con Metal: è vicina.
        val workout = listOf(song("Slipknot", "Music/Workout"), song("Linkin Park", "Music/Workout"), song("Rammstein", "Music/Workout"))
        val disney = (1..8).map { song("Disney ${it % 3}", "Music/Disney") }
        val all = listOf(seed) + metal + workout + disney
        val radio = MoodRadio.pick(seed, all, Random(3))
        val pos = radio.withIndex().associate { (i, id) -> id to i }
        assertEquals(seed.id, radio.first())
        val nearby = (metal + workout.filter { it.artist != "Slipknot" }).map { pos.getValue(it.id) }
        val far = disney.mapNotNull { pos[it.id] }
        assertTrue(nearby.max() < (far.minOrNull() ?: Int.MAX_VALUE), "$radio")
    }

    @Test
    fun `il genere del file e l'energia contano quando le raccolte non bastano`() {
        val seed = song("A", null, genre = "Metal; Rock", gain = -10f)
        val metal = song("B", null, genre = "alternative metal; rock", gain = -9f)
        val pop = song("C", null, genre = "Pop", gain = -9f)
        val calm = song("D", null, genre = "Rock", gain = 3f)
        val affinity = MoodRadio.Affinity(listOf(seed, metal, pop, calm))
        assertTrue(MoodRadio.score(seed, metal, affinity) > 0)
        assertTrue(MoodRadio.score(seed, pop, affinity) < 0)
        // Stesso genere ma molto più tranquillo: meno simile di quello con la stessa energia.
        assertTrue(MoodRadio.score(seed, calm, affinity) < MoodRadio.score(seed, metal, affinity))
        assertEquals(setOf("metal", "rock"), MoodRadio.genres("Metal / Rock, Other"))
    }

    @Test
    fun `lo stesso artista al massimo uno ogni tre, e mai due di fila quando si puo`() {
        val seed = song("Queen", "Music/Rock")
        val queen = (1..10).map { song("Queen", "Music/Rock") }
        val others = (1..20).map { song("Artista ${it % 5}", "Music/Rock") }
        val all = listOf(seed) + queen + others
        val byId = all.associateBy { it.id }
        val radio = MoodRadio.pick(seed, all, Random(1)).map { byId.getValue(it).artist }
        assertTrue(radio.drop(1).take(12).count { it == "Queen" } <= 4, "$radio")
        assertEquals(0, radio.drop(1).zipWithNext().count { (a, b) -> a == b && a != "Queen" }, "$radio")
    }

    @Test
    fun `toccando un brano nell'elenco di tutti i brani parte la sua radio`() {
        assertEquals("local/radio/42", QueueOrder.radioInstead("local/track/42|local/songs"))
        assertNull(QueueOrder.radioInstead("local/track/42|me/playlist/x"))
        assertNull(QueueOrder.radioInstead("local/track/42"))
        assertNull(QueueOrder.radioInstead("remote/track/42|local/songs"))
    }

    private fun t(id: Long, title: String, artist: String, folder: String) =
        LocalTrack(id, title, artist, 1, "Album $id", id, 200_000, 0, 0, 0, 0, folder)

    @Test
    fun `la radio del telefono non pesca a caso tra tutte le cartelle di Music`() {
        // Prima «stessa cartella sopra» per Music/Metal era Music: andava bene qualunque brano.
        val metal = (1..6).map { t(it.toLong(), "Metal $it", "Band ${it % 3}", "Music/Metal") }
        val disney = (7..40).map { t(it.toLong(), "Cartone $it", "Disney ${it % 4}", "Music/Disney") }
        val all = metal + disney
        val catalog = LocalCatalog(streamUri = { "x" }, random = Random(5))
        val radio: List<LibraryNode> = catalog.children(MediaId(LocalCatalog.KEY, LocalCatalog.RADIO, "1"), all)
        assertEquals("Metal 1", radio.first().title)
        // Subito dopo il brano scelto, tutti gli altri della sua raccolta prima di un cartone.
        assertTrue(radio.drop(1).take(5).all { it.title.startsWith("Metal") }, radio.map { it.title }.toString())
    }
}
