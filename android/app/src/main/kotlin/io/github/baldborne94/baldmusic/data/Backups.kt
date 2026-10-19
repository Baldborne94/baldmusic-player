package io.github.baldborne94.baldmusic.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.edit
import io.github.baldborne94.baldmusic.core.library.Backup
import io.github.baldborne94.baldmusic.core.library.LibraryBackup
import io.github.baldborne94.baldmusic.core.library.RestorePlan
import io.github.baldborne94.baldmusic.core.library.UserLibrary
import io.github.baldborne94.baldmusic.library.LocalSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Backup della tua musica (preferiti, playlist, raccolte, ascolti). A mano in un file
 * scelto con la finestra di Android (Drive, Download, una chiavetta…), e in automatico
 * in Download/BaldMusic, fuori dall'app: disinstallandola resta lì, e da "Ripristina da
 * un backup" la ritrovi anche su un telefono nuovo. Le regole stanno in [LibraryBackup].
 */
class Backups(private val context: Context, private val userData: UserData, private val local: LocalSource) {

    /** Ultimo backup automatico riuscito (millis), o l'errore dell'ultimo tentativo. */
    data class AutoStatus(val lastAt: Long?, val error: String? = null)

    private val prefs = context.getSharedPreferences("backup", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _auto = MutableStateFlow(AutoStatus(prefs.getLong(KEY_LAST, 0).takeIf { it > 0 }))
    val auto: StateFlow<AutoStatus> = _auto.asStateFlow()

    private val _autoEnabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, true))
    val autoEnabled: StateFlow<Boolean> = _autoEnabled.asStateFlow()

    /** Il backup automatico si scrive da solo: serve Android 10 (cartella Download senza permessi). */
    val autoSupported = Build.VERSION.SDK_INT >= 29

    fun setAutoEnabled(on: Boolean) {
        prefs.edit { putBoolean(KEY_ENABLED, on) }
        _autoEnabled.value = on
        if (on) scope.launch { writeAuto() }
    }

    /** Dopo ogni modifica, quando le cose si calmano (gli ascolti arrivano a raffica). */
    @OptIn(FlowPreview::class)
    fun start() {
        if (!autoSupported) return
        scope.launch {
            userData.state.drop(1).debounce(AUTO_DELAY_MS).collect { if (_autoEnabled.value) writeAuto() }
        }
    }

    /** C'è qualcosa da salvare: non una libreria appena installata con le sole raccolte create da sole. */
    private fun worthSaving(lib: UserLibrary) =
        lib.liked.isNotEmpty() || lib.plays.isNotEmpty() || lib.playlists.any { !it.isCollection || it.removed.isNotEmpty() }

    private suspend fun snapshot(): String =
        LibraryBackup.encode(LibraryBackup.create(userData.value, local.allTracks(), System.currentTimeMillis()))

    /** "Salva un backup…": nel file scelto dall'utente. */
    suspend fun writeTo(uri: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val text = snapshot()
            context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(text.toByteArray()) }
        }
    }

    /** Legge un file di backup; null se non lo è. */
    suspend fun read(uri: Uri): Backup? = withContext(Dispatchers.IO) {
        runCatching { context.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() } }.getOrNull()?.let(LibraryBackup::decode)
    }

    /** Cosa farebbe il ripristino con i brani che ci sono adesso sul telefono. */
    suspend fun plan(backup: Backup): RestorePlan = withContext(Dispatchers.Default) { LibraryBackup.restore(backup, local.allTracks()) }

    /** Sostituisce la tua musica con quella del backup. */
    fun apply(plan: RestorePlan) = userData.replaceAll(plan.library)

    /** La copia in Download/BaldMusic, sovrascritta ogni volta. */
    private suspend fun writeAuto() {
        if (Build.VERSION.SDK_INT < 29 || !worthSaving(userData.value)) return
        val result = runCatching {
            val text = snapshot().toByteArray()
            val resolver = context.contentResolver
            // Il file scritto da questa installazione; dopo una reinstallazione se ne crea uno nuovo.
            val saved = prefs.getString(KEY_URI, null)?.let(Uri::parse)
            val written = saved?.let { uri -> runCatching { resolver.openOutputStream(uri, "wt")!!.use { it.write(text) } }.isSuccess } == true
            if (!written) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, FILE_NAME)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/BaldMusic")
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("Android non ha creato il file")
                resolver.openOutputStream(uri, "wt")!!.use { it.write(text) }
                prefs.edit { putString(KEY_URI, uri.toString()) }
            }
        }
        _auto.value = result.fold(
            onSuccess = {
                val now = System.currentTimeMillis()
                prefs.edit { putLong(KEY_LAST, now) }
                AutoStatus(now)
            },
            onFailure = { AutoStatus(_auto.value.lastAt, it.message ?: "errore sconosciuto") },
        )
    }

    companion object {
        const val FILE_NAME = "baldmusic-backup.json"
        const val AUTO_FOLDER = "Download/BaldMusic"
        private const val AUTO_DELAY_MS = 30_000L
        private const val KEY_URI = "auto_uri"
        private const val KEY_LAST = "auto_last"
        private const val KEY_ENABLED = "auto_enabled"
    }
}
