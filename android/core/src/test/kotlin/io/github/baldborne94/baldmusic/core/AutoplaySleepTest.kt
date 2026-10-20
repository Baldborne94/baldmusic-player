package io.github.baldborne94.baldmusic.core

import io.github.baldborne94.baldmusic.core.library.Autoplay
import io.github.baldborne94.baldmusic.core.library.MediaId
import io.github.baldborne94.baldmusic.core.library.SleepTimer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AutoplayTest {
    @Test
    fun `si continua solo all'ultimo brano, senza ripeti e senza timer a fine brano`() {
        assertTrue(Autoplay.needed(enabled = true, hasNext = false, repeatOff = true, endOfTrackTimer = false))
        assertFalse(Autoplay.needed(enabled = false, hasNext = false, repeatOff = true, endOfTrackTimer = false))
        assertFalse(Autoplay.needed(enabled = true, hasNext = true, repeatOff = true, endOfTrackTimer = false))
        assertFalse(Autoplay.needed(enabled = true, hasNext = false, repeatOff = false, endOfTrackTimer = false))
        assertFalse(Autoplay.needed(enabled = true, hasNext = false, repeatOff = true, endOfTrackTimer = true))
    }

    @Test
    fun `dal telefono la radio del brano, dal server brani a caso`() {
        assertEquals(MediaId("local", "radio", "42"), Autoplay.source("local/track/42|me/collection/c1"))
        assertEquals(MediaId("remote", "random"), Autoplay.source("remote/track/tr-9|remote/album/al-1"))
        assertNull(Autoplay.source("local/album/7"))
        assertNull(Autoplay.source(""))
    }

    @Test
    fun `non si aggiunge cio che e gia in coda ne due volte lo stesso brano`() {
        val queue = listOf("local/track/1|me/collection/c1", "local/track/2|me/collection/c1")
        val candidates = listOf("local/track/2|local/radio/2", "local/track/5|local/radio/2", "local/track/5|local/radio/2", "local/track/6|local/radio/2", "local/track/7|local/radio/2")
        assertEquals(listOf(1, 3), Autoplay.pick(candidates, queue, max = 2))
        assertEquals(listOf(1, 3, 4), Autoplay.pick(candidates, queue))
    }
}

class SleepTimerTest {
    private val min = 60_000L
    private val now = 1_000_000_000L

    @Test
    fun `un tocco per passo - spento, 15, 30, 60, fine brano, spento`() {
        var end: Long? = null
        val seen = mutableListOf<String>()
        repeat(5) {
            end = SleepTimer.next(end, now)
            seen += SleepTimer.label(end, now)
        }
        assertEquals(listOf("Si ferma tra 15 min", "Si ferma tra 30 min", "Si ferma tra 60 min", "Si ferma a fine brano", "Timer di spegnimento"), seen)
    }

    @Test
    fun `toccato dopo un po' va al passo dopo quello in cui si e`() {
        // 15 minuti messi 4 minuti fa: ne restano 11, il tocco porta a 30.
        assertEquals(now + 30 * min, SleepTimer.next(now + 11 * min, now))
        // Un timer di 45 minuti messo dal telefono: dopo i 60 c'è "a fine brano".
        assertEquals(now + 60 * min, SleepTimer.next(now + 25 * min, now))
        assertEquals(SleepTimer.END_OF_TRACK, SleepTimer.next(now + 45 * min, now))
        assertEquals(SleepTimer.END_OF_TRACK, SleepTimer.next(now + 90 * min, now))
    }

    @Test
    fun `i minuti che restano si arrotondano in su`() {
        assertEquals("Si ferma tra 1 min", SleepTimer.label(now + 10_000, now))
        assertEquals("Si ferma tra 15 min", SleepTimer.label(now + 14 * min + 1, now))
        assertEquals(0, SleepTimer.minutesLeft(now - 5_000, now))
    }
}
