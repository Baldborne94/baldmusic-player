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
}
