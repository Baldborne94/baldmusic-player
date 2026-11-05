package io.github.baldborne94.baldmusic.core

import io.github.baldborne94.baldmusic.core.library.CoverScreen
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoverScreenTest {
    @Test
    fun `resta acceso mentre suona e poco dopo, poi si lascia spegnere`() {
        assertTrue(CoverScreen.keepAwake(playing = true, stoppedAtMs = null, nowMs = 1_000_000))
        // Tra un brano e l'altro (o una pausa breve) resta acceso.
        assertTrue(CoverScreen.keepAwake(playing = false, stoppedAtMs = 1_000_000, nowMs = 1_000_000 + 3_000))
        assertFalse(CoverScreen.keepAwake(playing = false, stoppedAtMs = 1_000_000, nowMs = 1_000_000 + CoverScreen.GRACE_MS))
        assertFalse(CoverScreen.keepAwake(playing = false, stoppedAtMs = null, nowMs = 5))
    }

    @Test
    fun `il momento in cui si e fermato non si sposta finche non riparte`() {
        val first = CoverScreen.stoppedAt(playing = false, before = null, nowMs = 100)
        assertEquals(100L, first)
        assertEquals(100L, CoverScreen.stoppedAt(playing = false, before = first, nowMs = 9_000))
        assertNull(CoverScreen.stoppedAt(playing = true, before = first, nowMs = 9_500))
    }

    @Test
    fun `si arma quando parte la musica o una coda nuova`() {
        assertTrue(CoverScreen.arms(wasPlaying = false, playing = true, newQueue = false))
        assertTrue(CoverScreen.arms(wasPlaying = true, playing = true, newQueue = true))
        // Suona gia (passa al brano dopo): non si riarma, cosi chiuso a mano non torna.
        assertFalse(CoverScreen.arms(wasPlaying = true, playing = true, newQueue = false))
        assertFalse(CoverScreen.arms(wasPlaying = true, playing = false, newQueue = true))
    }

    @Test
    fun `si apre da solo dopo qualche secondo senza toccare il telefono`() {
        val idle = CoverScreen.IDLE_MS
        assertTrue(CoverScreen.autoOpen(enabled = true, armed = true, playing = true, reading = false, idleMs = idle))
        assertFalse(CoverScreen.autoOpen(enabled = true, armed = true, playing = true, reading = false, idleMs = idle - 1))
        assertFalse(CoverScreen.autoOpen(enabled = false, armed = true, playing = true, reading = false, idleMs = idle))
        assertFalse(CoverScreen.autoOpen(enabled = true, armed = false, playing = true, reading = false, idleMs = idle))
        assertFalse(CoverScreen.autoOpen(enabled = true, armed = true, playing = false, reading = false, idleMs = idle))
        // Col testo aperto lo stai leggendo.
        assertFalse(CoverScreen.autoOpen(enabled = true, armed = true, playing = true, reading = true, idleMs = idle * 10))
    }

    @Test
    fun `la copertina si sposta di poco e ogni volta altrove`() {
        val steps = (0L until 18L).map(CoverScreen::shift)
        steps.forEach { (x, y) ->
            assertTrue(kotlin.math.abs(x) <= CoverScreen.SHIFT_DP && kotlin.math.abs(y) <= CoverScreen.SHIFT_DP)
        }
        steps.zipWithNext().forEach { (a, b) -> assertNotEquals(a, b) }
        // Il giro passa da tutti i lati, così nessun punto dello schermo resta sempre uguale.
        assertEquals(setOf(-CoverScreen.SHIFT_DP, 0, CoverScreen.SHIFT_DP), steps.map { it.first }.toSet())
        assertEquals(setOf(-CoverScreen.SHIFT_DP, 0, CoverScreen.SHIFT_DP), steps.map { it.second }.toSet())
        assertEquals(CoverScreen.shift(0), CoverScreen.shift(9))
        assertEquals(CoverScreen.shift(-1), CoverScreen.shift(8))
    }
}
