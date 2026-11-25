package io.github.baldborne94.baldmusic.core

import io.github.baldborne94.baldmusic.core.library.PlayGuard
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Toccando un brano che non si trova più sul telefono ripartiva la coda di prima (un podcast):
 * l'app manda «coda nuova» e subito dopo «play», e la coda nuova rifiutata lasciava la vecchia.
 */
class PlayGuardTest {
    @Test
    fun `il play subito dopo un brano non trovato non fa ripartire la coda di prima`() {
        assertTrue(PlayGuard.ignorePlay(refusedAtMs = 10_000, nowMs = 10_050))
        assertTrue(PlayGuard.ignorePlay(refusedAtMs = 10_000, nowMs = 10_000 + PlayGuard.WINDOW_MS))
    }

    @Test
    fun `un play piu tardi, o senza richieste rifiutate, suona`() {
        assertFalse(PlayGuard.ignorePlay(refusedAtMs = null, nowMs = 10_050))
        assertFalse(PlayGuard.ignorePlay(refusedAtMs = 10_000, nowMs = 10_000 + PlayGuard.WINDOW_MS + 1))
        assertFalse(PlayGuard.ignorePlay(refusedAtMs = 10_000, nowMs = 9_000))
    }
}
