package io.github.baldborne94.baldmusic.core

import io.github.baldborne94.baldmusic.core.library.CarShortcuts
import io.github.baldborne94.baldmusic.core.library.LibraryNode
import io.github.baldborne94.baldmusic.core.library.LibraryNode.Kind
import io.github.baldborne94.baldmusic.core.library.MediaId
import io.github.baldborne94.baldmusic.core.library.PersonalSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CarShortcutsTest {
    private fun track(n: Int, parent: String) = LibraryNode("local/track/$n|$parent", "Brano $n", Kind.TRACK, artwork = "local/$n")
    private val collection = MediaId(PersonalSource.KEY, PersonalSource.PLAYLIST, "p1").toString()

    @Test
    fun `in una raccolta la prima voce e Riproduci in casuale`() {
        val children = (1..3).map { track(it, collection) }
        val car = CarShortcuts.forCar(collection, children)
        assertEquals(4, car.size)
        val shuffle = car.first()
        assertEquals("Riproduci in casuale", shuffle.title)
        assertEquals("3 brani", shuffle.subtitle)
        assertTrue(shuffle.playable)
        assertEquals(collection to true, CarShortcuts.parse(shuffle.mediaId))
        assertEquals(children, car.drop(1))
    }

    @Test
    fun `anche nelle playlist del server, mai negli album o con un brano solo`() {
        assertTrue(CarShortcuts.shufflable("remote/playlist/pl-9"))
        assertFalse(CarShortcuts.shufflable("local/album/x"))
        assertFalse(CarShortcuts.shufflable(MediaId(PersonalSource.KEY, PersonalSource.PLAYLISTS).toString()))
        val one = listOf(track(1, collection))
        assertEquals(one, CarShortcuts.forCar(collection, one))
        val album = (1..3).map { track(it, "local/album/x") }
        assertEquals(album, CarShortcuts.forCar("local/album/x", album))
    }

    @Test
    fun `nella prima scheda prima Per la macchina e Riscopri, che partono con un tocco`() {
        val drive = LibraryNode("me/mix/drive", "Per la macchina", Kind.PLAYLIST, subtitle = "I tuoi preferiti di sempre")
        val rediscover = LibraryNode("me/mix/rediscover", "Riscopri", Kind.PLAYLIST)
        val random = LibraryNode("local/shuffle", "Mix casuale", Kind.PLAYLIST)
        val sections = listOf(LibraryNode("me/collections", "Raccolte", Kind.SECTION))
        val car = CarShortcuts.forCar(PersonalSource.KEY, sections, mixes = listOf(rediscover, random, drive))
        assertEquals(listOf("Per la macchina", "Riscopri", "Raccolte"), car.map { it.title })
        assertTrue(car[0].playable && car[1].playable)
        assertEquals("me/mix/drive" to false, CarShortcuts.parse(car[0].mediaId))
        // Senza mix (libreria nuova) resta com'era.
        assertEquals(sections, CarShortcuts.forCar(PersonalSource.KEY, sections, mixes = listOf(random)))
    }

    @Test
    fun `un ID normale non e una scorciatoia`() {
        assertNull(CarShortcuts.parse("local/track/1|me/playlist/p1"))
        assertNull(CarShortcuts.parse("casuale:"))
    }
}
