package io.github.baldborne94.baldmusic.core

import io.github.baldborne94.baldmusic.core.library.BackupSettings
import io.github.baldborne94.baldmusic.core.library.LibraryBackup
import io.github.baldborne94.baldmusic.core.library.UserLibrary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class BackupSettingsTest {
    private val settings = BackupSettings(
        serverUrl = " https://pc.tail.ts.net ", serverUser = "marco", acoustIdKey = "AbC123xyz",
        lyricsOnline = false, replayGain = "TRACK", crossfadeSeconds = 9, continueListening = false, autoBackup = true,
    )

    @Test
    fun `le impostazioni viaggiano col backup e tornano al ripristino`() {
        val text = LibraryBackup.encode(LibraryBackup.create(UserLibrary(), emptyList(), 1, settings))
        val plan = LibraryBackup.restore(LibraryBackup.decode(text)!!, emptyList())
        assertEquals(settings.copy(serverUrl = "https://pc.tail.ts.net"), plan.settings)
    }

    @Test
    fun `nel file niente token, sale o password del server`() {
        val text = LibraryBackup.encode(LibraryBackup.create(UserLibrary(), emptyList(), 1, settings))
        listOf("token", "salt", "password").forEach { assertFalse(it in text.lowercase(), it) }
    }

    @Test
    fun `un backup di prima non ha impostazioni e si ripristina lo stesso`() {
        val old = """{"app":"BaldMusic","format":1,"createdAt":5,"library":{"liked":[]},"files":{}}"""
        val backup = assertNotNull(LibraryBackup.decode(old))
        assertNull(LibraryBackup.restore(backup, emptyList()).settings)
    }

    @Test
    fun `valori strani si ignorano invece di rompere le impostazioni`() {
        val odd = BackupSettings(serverUrl = "pc.local", serverUser = "  ", acoustIdKey = "con spazi dentro", replayGain = "FORTE", crossfadeSeconds = 7, lyricsOnline = true)
        assertEquals(BackupSettings(lyricsOnline = true), odd.cleaned())
        // Campi di una versione futura: si leggono senza errori.
        val future = """{"app":"BaldMusic","format":1,"settings":{"crossfadeSeconds":3,"tema":"chiaro"}}"""
        assertEquals(3, LibraryBackup.restore(LibraryBackup.decode(future)!!, emptyList()).settings?.crossfadeSeconds)
    }
}
