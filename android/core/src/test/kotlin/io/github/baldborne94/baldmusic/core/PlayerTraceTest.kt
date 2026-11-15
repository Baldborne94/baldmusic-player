package io.github.baldborne94.baldmusic.core

import io.github.baldborne94.baldmusic.core.diagnostics.DeviceInfo
import io.github.baldborne94.baldmusic.core.diagnostics.PlayerTrace
import io.github.baldborne94.baldmusic.core.diagnostics.ProblemReport
import io.github.baldborne94.baldmusic.core.library.VoiceQuery
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlayerTraceTest {
    @Test
    fun `chi manda il comando si legge per nome`() {
        assertEquals("Android Auto", PlayerTrace.who("com.google.android.projection.gearhead", own = "io.github.baldborne94.baldmusic"))
        assertEquals("app", PlayerTrace.who("io.github.baldborne94.baldmusic", own = "io.github.baldborne94.baldmusic"))
        assertEquals("com.example.cuffie", PlayerTrace.who("com.example.cuffie", own = "x"))
        assertEquals("?", PlayerTrace.who(null, own = "x"))
    }

    @Test
    fun `lo stato dice brano, punto, casuale e ripeti`() {
        assertEquals(
            "brano 3 di 40 a 1:05 su 3:30, casuale sì, ripeti tutto",
            PlayerTrace.state(index = 2, count = 40, positionMs = 65_400, durationMs = 210_000, shuffle = true, repeat = 2),
        )
        assertEquals("brano 1 di 1 a 0:00, casuale no, ripeti no", PlayerTrace.state(0, 1, -5, null, false, 0))
        assertEquals("avanti", PlayerTrace.key(87))
        assertEquals("comando", PlayerTrace.discontinuity(1))
        assertEquals("ripetuto", PlayerTrace.transition(0))
    }

    @Test
    fun `il registro del lettore tiene le ultime righe e va nel resoconto in ordine di tempo`() {
        var lines = emptyList<String>()
        repeat(ProblemReport.KEEP_TRACE + 5) { lines = ProblemReport.addTrace(lines, "riga $it") }
        assertEquals(ProblemReport.KEEP_TRACE, lines.size)
        assertEquals("riga 5", lines.first())
        val report = ProblemReport.build(
            DeviceInfo("1", 1, "Pixel", "15"), 0, emptyList(), emptyList(), log = null,
            zone = ZoneId.of("Europe/Rome"), trace = listOf("10:00 avanti da Android Auto", "10:00 brano 2 di 40"),
        )
        val section = report.substringAfter("--- Lettore: comandi e cambi di brano ---")
        assertTrue(section.indexOf("avanti da Android Auto") < section.indexOf("brano 2 di 40"))
    }

    @Test
    fun `una richiesta a voce senza parole fa partire la musica invece di niente`() {
        // L'Assistente puo' mandare «metti musica su BaldMusic» senza parole: prima non suonava niente.
        assertEquals("", VoiceQuery.searchOf("", null))
        assertEquals("vasco rossi", VoiceQuery.searchOf("", "  vasco rossi "))
        assertNull(VoiceQuery.searchOf("me/playlist/x", null))
        assertNull(VoiceQuery.searchOf("me/playlist/x", "vasco"))
    }
}
