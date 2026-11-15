package io.github.baldborne94.baldmusic.core.diagnostics

import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Da dove viene il resoconto: versione dell'app e telefono. */
data class DeviceInfo(val appVersion: String, val build: Long, val device: String, val android: String)

/**
 * Il resoconto da mandare quando qualcosa non va ("Segnala un problema"): le chiusure
 * improvvise salvate dall'app, gli ultimi problemi annotati (es. un brano che non parte) e il
 * registro di Android dell'app. Mai credenziali: token e sale di Subsonic, password e chiave
 * di AcoustID si cancellano ([redact]) prima di salvare e prima di mandare.
 */
object ProblemReport {
    /** Quante chiusure improvvise si tengono (le più recenti). */
    const val KEEP_CRASHES = 5

    /** Quanto può essere lungo il resoconto: deve passare in un messaggio. */
    const val MAX_CHARS = 90_000

    /** Quante righe di annotazioni si tengono. */
    const val KEEP_NOTES = 60

    /** Quanto del registro di Android entra al massimo nel resoconto (la fine, la più recente). */
    const val MAX_LOG_CHARS = 30_000

    /**
     * Le righe che il sistema scrive di continuo mentre si disegna o si tocca lo schermo (su
     * Samsung migliaia al minuto: «setRequestedFrameRate», «ViewPostIme pointer»…) e le
     * statistiche del decoder audio («BufferPoolAccessor2.0», ogni 5 s): non dicono
     * niente su BaldMusic e prima riempivano tutto il registro del resoconto. Solo informazioni e
     * debug: avvisi ed errori di quei componenti restano.
     */
    private val noise = Regex(
        """^\d\d-\d\d \d\d:\d\d:\d\d\.\d{3} [VDI]/(View|VRI\[[^\]]*\]@[0-9a-f]+|ViewRootImpl[^\s(]*|InsetsController|InsetsSourceConsumer|ImeTracker|InputMethodManager[^\s(]*|BLASTBufferQueue[^\s(]*|SurfaceView[^\s(]*|SurfaceComposerClient|HWUI|Choreographer|InputTransport|NativeCustomFrequencyManager|BufferPoolAccessor[^\s(]*|ImeFocusController)\s*\(""",
    )

    /** Il registro di Android senza le righe che non servono ([noise]). */
    fun quiet(log: String): String = log.lineSequence().filterNot { noise.containsMatchIn(it) }.joinToString("\n")

    /** Quante righe tiene il registro del lettore ([addTrace]). */
    const val KEEP_TRACE = 150

    private val secrets = Regex("""([?&;\s](?:t|s|p|u|token|salt|password|client|apiKey|api_key|auth|token-hash|token-time|signature|x-amz-signature|sig)=)[^&\s"']+""", RegexOption.IGNORE_CASE)

    /** Toglie dal testo i parametri che valgono come credenziali (negli indirizzi dei brani, per esempio). */
    fun redact(text: String): String = secrets.replace(text) { it.groupValues[1] + "…" }

    /** L'ora come la legge una persona, nel fuso del telefono. */
    fun time(at: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss").withZone(zone).format(Instant.ofEpochMilli(at))

    /** Una chiusura improvvisa: quando, in quale parte dell'app, l'errore completo e il registro di quel momento. */
    fun crash(at: Long, thread: String, error: Throwable, log: String? = null, zone: ZoneId = ZoneId.systemDefault()): String {
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString().trimEnd()
        return redact(
            buildString {
                appendLine("Chiusura improvvisa del ${time(at, zone)} (thread «$thread»)")
                appendLine(trace)
                if (!log.isNullOrBlank()) {
                    appendLine("--- Registro di quel momento ---")
                    appendLine(log.trimEnd())
                }
            },
        )
    }

    /** Un'annotazione: un problema che non chiude l'app (un brano che non parte, una lettura fallita). */
    fun note(at: Long, text: String, zone: ZoneId = ZoneId.systemDefault()): String =
        redact("${time(at, zone)} ${text.replace('\n', ' ').take(500)}")

    /** Le annotazioni dopo averne aggiunta una: le ultime [KEEP_NOTES]. */
    fun addNote(notes: List<String>, note: String): List<String> = (notes + note).takeLast(KEEP_NOTES)

    /**
     * Il registro del lettore: comandi arrivati (dall'auto, dalle cuffie, dall'app) e cambi di
     * brano, gli ultimi [KEEP_TRACE], per capire cosa è successo quando un tasto non fa quello
     * che dovrebbe.
     */
    fun addTrace(lines: List<String>, line: String): List<String> = (lines + line).takeLast(KEEP_TRACE)

    /** I file di chiusura da cancellare: tutti tranne i [KEEP_CRASHES] più recenti (i nomi sono l'ora). */
    fun crashesToDrop(names: List<String>): List<String> = names.sortedDescending().drop(KEEP_CRASHES)

    /**
     * Il resoconto completo, al massimo [MAX_CHARS]: prima telefono e versione, poi le chiusure
     * dalla più recente, le annotazioni e infine il registro di adesso, che si accorcia per primo.
     */
    fun build(
        info: DeviceInfo,
        at: Long,
        crashes: List<String>,
        notes: List<String>,
        log: String?,
        zone: ZoneId = ZoneId.systemDefault(),
        trace: List<String> = emptyList(),
    ): String {
        val head = buildString {
            appendLine("Resoconto di BaldMusic del ${time(at, zone)}")
            appendLine("App ${info.appVersion} (build ${info.build}) · ${info.device} · Android ${info.android}")
            appendLine(
                if (crashes.isEmpty()) "Nessuna chiusura improvvisa salvata."
                else "Chiusure improvvise salvate: ${crashes.size} (dalla più recente).",
            )
        }
        val body = buildString {
            crashes.forEach { appendLine(); appendLine(it.trimEnd()) }
            if (notes.isNotEmpty()) {
                appendLine()
                appendLine("--- Ultimi problemi annotati ---")
                notes.asReversed().forEach(::appendLine)
            }
            if (trace.isNotEmpty()) {
                appendLine()
                // In ordine di tempo: si legge come è andata.
                appendLine("--- Lettore: comandi e cambi di brano ---")
                trace.forEach(::appendLine)
            }
        }
        val start = redact(head + body)
        val room = MAX_CHARS - start.length
        if (log.isNullOrBlank() || room <= 200) return start.take(MAX_CHARS)
        // Del registro conta la fine: le righe più recenti.
        val tail = redact(quiet(log).trimEnd()).takeLast(minOf(room - 100, MAX_LOG_CHARS))
        return start + "\n--- Registro di adesso ---\n" + tail
    }
}
