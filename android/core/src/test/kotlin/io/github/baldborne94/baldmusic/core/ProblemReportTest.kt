package io.github.baldborne94.baldmusic.core

import io.github.baldborne94.baldmusic.core.diagnostics.DeviceInfo
import io.github.baldborne94.baldmusic.core.diagnostics.ProblemReport
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProblemReportTest {
    private val rome = ZoneId.of("Europe/Rome")
    private val info = DeviceInfo("0.1.120", 120, "Google Pixel 8", "15")

    @Test
    fun `token, sale, password e chiave di AcoustID non escono mai`() {
        val url = "https://pc.tail.ts.net/rest/stream?id=al-1&u=marco&t=26719a1196d2a940705a59634eb18eab&s=c19b2d&v=1.16.1&c=baldmusic"
        val redacted = ProblemReport.redact("Errore aprendo $url e client=AbCdEf12 password=segreta")
        assertFalse("26719a1196" in redacted)
        assertFalse("c19b2d" in redacted)
        assertFalse("marco" in redacted)
        assertFalse("AbCdEf12" in redacted)
        assertFalse("segreta" in redacted)
        // Il resto resta leggibile: si capisce quale brano e quale server.
        assertTrue("stream?id=al-1" in redacted && "v=1.16.1" in redacted && "pc.tail.ts.net" in redacted)
    }

    @Test
    fun `una chiusura dice quando, dove e perche, con l'ora del telefono`() {
        // 20 ottobre 2026, 13:05:09 UTC = 15:05:09 a Roma (ora legale).
        val at = 1_792_501_509_000L
        val text = ProblemReport.crash(at, "main", IllegalStateException("coda vuota"), log = "riga del registro t=abc123", zone = rome)
        assertTrue(text.startsWith("Chiusura improvvisa del 20/10/2026 15:05:09 (thread «main»)"), text)
        assertTrue("IllegalStateException: coda vuota" in text)
        assertTrue("at io.github.baldborne94" in text)
        assertTrue("riga del registro" in text && "abc123" !in text)
    }

    @Test
    fun `si tengono le ultime 5 chiusure e le ultime annotazioni`() {
        val names = (1..8).map { "crash-$it.txt" }
        assertEquals(listOf("crash-3.txt", "crash-2.txt", "crash-1.txt"), ProblemReport.crashesToDrop(names))
        var notes = emptyList<String>()
        repeat(ProblemReport.KEEP_NOTES + 10) { notes = ProblemReport.addNote(notes, "n$it") }
        assertEquals(ProblemReport.KEEP_NOTES, notes.size)
        assertEquals("n${ProblemReport.KEEP_NOTES + 9}", notes.last())
    }

    @Test
    fun `il resoconto ha telefono e versione, le chiusure, le annotazioni e il registro, e non supera il limite`() {
        val report = ProblemReport.build(info, 1_792_501_509_000L, listOf("Chiusura A"), listOf("15:00 brano non parte", "15:01 altro"), log = "x".repeat(500_000) + "\nULTIMA RIGA", zone = rome)
        assertTrue(report.startsWith("Resoconto di BaldMusic del 20/10/2026 15:05:09"))
        assertTrue("App 0.1.120 (build 120) · Google Pixel 8 · Android 15" in report)
        assertTrue("Chiusura A" in report)
        // Le annotazioni dalla più recente.
        assertTrue(report.indexOf("15:01 altro") < report.indexOf("15:00 brano non parte"))
        // Il registro si accorcia tenendo la fine.
        assertTrue(report.length <= ProblemReport.MAX_CHARS)
        assertTrue(report.trimEnd().endsWith("ULTIMA RIGA"))
    }

    @Test
    fun `senza niente da dire lo dice`() {
        val report = ProblemReport.build(info, 0, emptyList(), emptyList(), log = null, zone = rome)
        assertTrue("Nessuna chiusura improvvisa salvata." in report)
    }
}
