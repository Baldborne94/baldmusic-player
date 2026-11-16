package io.github.baldborne94.baldmusic.core

import io.github.baldborne94.baldmusic.core.library.AppTheme
import io.github.baldborne94.baldmusic.core.library.AppTheme.Mode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppThemeTest {
    @Test
    fun `automatico e pop di giorno e scuro di sera, come il telefono`() {
        assertTrue(AppTheme.isPop(Mode.AUTO, systemDark = false))
        assertFalse(AppTheme.isPop(Mode.AUTO, systemDark = true))
    }

    @Test
    fun `scelto a mano resta quello`() {
        assertTrue(AppTheme.isPop(Mode.POP, systemDark = true))
        assertFalse(AppTheme.isPop(Mode.DARK, systemDark = false))
    }

    @Test
    fun `di serie e automatico, anche con un valore sconosciuto`() {
        assertEquals(Mode.AUTO, AppTheme.parse(null))
        assertEquals(Mode.AUTO, AppTheme.parse("ARCOBALENO"))
        assertEquals(Mode.POP, AppTheme.parse("POP"))
    }

    @Test
    fun `testo del brano su copertina nera e bianco, non nero su nero`() {
        // Lo sfondo del testo con la copertina di «All the Right Reasons»: quasi nero.
        assertTrue(AppTheme.lightLyrics(pop = false, backgroundLuminance = 0.02f))
        // Copertina chiara (giallo acceso): testo nero.
        assertFalse(AppTheme.lightLyrics(pop = false, backgroundLuminance = 0.7f))
    }

    @Test
    fun `nel pop il testo sta sul riquadro bianco ed e nero`() {
        assertFalse(AppTheme.lightLyrics(pop = true, backgroundLuminance = 0.02f))
        assertFalse(AppTheme.lightLyrics(pop = true, backgroundLuminance = 0.9f))
    }
}
