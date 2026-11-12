package io.github.baldborne94.baldmusic.core

import io.github.baldborne94.baldmusic.core.library.Greeting
import kotlin.test.Test
import kotlin.test.assertEquals

class GreetingTest {
    @Test
    fun `dice l'ora del giorno e il nome dell'utente collegato`() {
        assertEquals("Buongiorno, Alessandro", Greeting.of(9, "alessandro"))
        assertEquals("Buon pomeriggio, Alessandro", Greeting.of(15, "alessandro"))
        assertEquals("Buonasera, Alessandro", Greeting.of(22, "alessandro"))
        assertEquals("Buonasera, Alessandro", Greeting.of(3, "alessandro"))
    }

    @Test
    fun `senza utente resta il saluto di prima`() {
        assertEquals("Buongiorno", Greeting.of(9, null))
        assertEquals("Buongiorno", Greeting.of(9, "  "))
    }

    @Test
    fun `rende leggibile il nome con cui ci si collega`() {
        assertEquals("Mario Rossi", Greeting.displayName("mario.rossi"))
        assertEquals("Mario Rossi 2", Greeting.displayName("mario_rossi-2"))
        assertEquals("Mario", Greeting.displayName("mario@example.com"))
        assertEquals("DJBald", Greeting.displayName("DJBald"))
        assertEquals("Élodie", Greeting.displayName("élodie"))
    }
}
