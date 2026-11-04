package io.github.baldborne94.baldmusic.core

import io.github.baldborne94.baldmusic.core.library.GoBack
import io.github.baldborne94.baldmusic.core.library.GoBack.Step
import io.github.baldborne94.baldmusic.core.library.QueueOrder
import kotlin.test.Test
import kotlin.test.assertEquals

class GoBackTest {
    private val queue = listOf("a", "b", "c", "d")

    @Test
    fun `oltre 3 secondi ricomincia, prima torna al brano sentito prima`() {
        assertEquals(Step.Restart, GoBack.step(20_000, queue, 2, history = listOf("a"), previousIndex = 1))
        // Col casuale il lettore non ha un brano prima: si torna a quello sentito davvero (prima ricominciava solo).
        assertEquals(Step.Seek(0), GoBack.step(1_000, queue, 2, history = listOf("d", "a"), previousIndex = null))
    }

    @Test
    fun `un brano saltato, uscito dalla coda, si rimette prima di quello in corso`() {
        assertEquals(Step.Reinsert("x"), GoBack.step(500, queue, 1, history = listOf("x"), previousIndex = 0))
    }

    @Test
    fun `senza cronologia vale l'ordine del lettore, se no si ricomincia`() {
        assertEquals(Step.Seek(1), GoBack.step(0, queue, 2, history = emptyList(), previousIndex = 1))
        assertEquals(Step.Restart, GoBack.step(0, queue, 0, history = emptyList(), previousIndex = null))
    }

    @Test
    fun `il brano rimesso in coda suona subito prima di quello da cui eri partito`() {
        // Ordine del casuale 3, 0, 4 (in corso), 1, 2; il brano rimesso è l'indice 2.
        assertEquals(listOf(3, 0, 2, 4, 1), QueueOrder.justBefore(listOf(3, 0, 4, 1, 2), item = 2, before = 4))
        assertEquals(listOf(0, 1, 5), QueueOrder.justBefore(listOf(0, 1), item = 5, before = 9))
    }
}
