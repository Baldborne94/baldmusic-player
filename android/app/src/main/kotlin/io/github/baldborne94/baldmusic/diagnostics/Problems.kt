package io.github.baldborne94.baldmusic.diagnostics

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import io.github.baldborne94.baldmusic.core.diagnostics.DeviceInfo
import io.github.baldborne94.baldmusic.core.diagnostics.ProblemReport
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * "Segnala un problema": l'app salva le chiusure improvvise (con il registro di quel
 * momento) e annota i problemi che non la chiudono, così chi la usa può mandare un resoconto
 * a chi la sviluppa. Tutto resta sul telefono finché non lo condividi tu. Le regole (cosa si
 * tiene, cosa si cancella, mai credenziali) sono in [ProblemReport].
 */
class Problems(private val context: Context) {

    private val dir = File(context.filesDir, "problemi").apply { mkdirs() }
    private val notesFile = File(dir, "annotazioni.txt")

    /** Da chiamare per primo all'avvio: le chiusure improvvise si salvano prima di chiudere. */
    fun install() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { saveCrash(thread.name, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun saveCrash(thread: String, error: Throwable) {
        val at = System.currentTimeMillis()
        File(dir, "crash-%013d.txt".format(at)).writeText(ProblemReport.crash(at, thread, error, log = logcat(CRASH_LOG_LINES)))
        ProblemReport.crashesToDrop(crashFiles().map { it.name }).forEach { File(dir, it).delete() }
    }

    /** Annotazioni una alla volta e fuori dal thread principale (arrivano anche dal lettore). */
    private val writer = java.util.concurrent.Executors.newSingleThreadExecutor()

    /** Un problema che non chiude l'app (es. un brano che non parte): finisce nel resoconto. */
    fun note(text: String) {
        val note = ProblemReport.note(System.currentTimeMillis(), text)
        writer.execute {
            runCatching {
                val notes = if (notesFile.exists()) notesFile.readLines().filter { it.isNotBlank() } else emptyList()
                notesFile.writeText(ProblemReport.addNote(notes, note).joinToString("\n"))
            }
        }
    }

    private fun crashFiles(): List<File> =
        dir.listFiles { f -> f.name.startsWith("crash-") }?.sortedByDescending { it.name }.orEmpty()

    /** Quando l'app si è chiusa da sola l'ultima volta; null se non è mai successo (o è stato cancellato). */
    fun lastCrashAt(): Long? = crashFiles().firstOrNull()?.name?.removePrefix("crash-")?.removeSuffix(".txt")?.toLongOrNull()

    /** Quante chiusure improvvise sono salvate. */
    fun crashCount(): Int = crashFiles().size

    /** Il resoconto da mandare: lento (legge il registro di Android), da chiamare fuori dal thread principale. */
    fun report(): String {
        val notes = runCatching { notesFile.readLines().filter { it.isNotBlank() } }.getOrDefault(emptyList())
        return ProblemReport.build(deviceInfo(), System.currentTimeMillis(), crashFiles().map { it.readText() }, notes, logcat(REPORT_LOG_LINES))
    }

    /** Apre la scelta di Android (mail, WhatsApp, Telegram, Drive…) col resoconto [text]. */
    fun share(text: String) {
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "BaldMusic: resoconto di un problema")
            .putExtra(Intent.EXTRA_TEXT, text)
        context.startActivity(Intent.createChooser(send, "Manda il resoconto").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun deviceInfo(): DeviceInfo {
        val info = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
        return DeviceInfo(
            appVersion = info?.versionName ?: "?",
            build = info?.let(PackageInfoCompat::getLongVersionCode) ?: 0,
            device = "${Build.MANUFACTURER} ${Build.MODEL}",
            android = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        )
    }

    /**
     * Le ultime righe del registro di Android di questa app (un'app legge sempre il suo). Con
     * un limite di tempo: alla chiusura improvvisa non si può aspettare.
     */
    private fun logcat(lines: Int): String? = runCatching {
        val process = ProcessBuilder("logcat", "-d", "-v", "time", "-t", "$lines", "--pid", "${android.os.Process.myPid()}")
            .redirectErrorStream(true)
            .start()
        var text: String? = null
        val reader = Thread { text = runCatching { process.inputStream.bufferedReader().readText() }.getOrNull() }.apply { start() }
        reader.join(LOG_TIMEOUT_MS)
        if (!process.waitFor(100, TimeUnit.MILLISECONDS)) process.destroy()
        text
    }.getOrNull()

    private companion object {
        const val CRASH_LOG_LINES = 200
        const val REPORT_LOG_LINES = 600
        const val LOG_TIMEOUT_MS = 2_000L
    }
}
