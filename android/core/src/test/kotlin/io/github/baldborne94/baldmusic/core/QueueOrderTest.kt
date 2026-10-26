package io.github.baldborne94.baldmusic.core

import io.github.baldborne94.baldmusic.core.library.QueueOrder
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
