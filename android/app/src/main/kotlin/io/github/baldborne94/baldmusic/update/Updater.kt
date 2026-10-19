package io.github.baldborne94.baldmusic.update

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.IntentCompat
import androidx.core.content.edit
import androidx.core.content.pm.PackageInfoCompat
import io.github.baldborne94.baldmusic.core.update.AppUpdate
import io.github.baldborne94.baldmusic.core.update.ReleaseInfo
import io.github.baldborne94.baldmusic.core.update.ReleaseNotes
import io.github.baldborne94.baldmusic.core.update.UpdateClient
import io.github.baldborne94.baldmusic.ui.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/**
 * Aggiornamenti dall'app: controlla la release "apk-latest" di GitHub, scarica l'APK e lo
 * passa all'installatore di Android (PackageInstaller), che chiede conferma. Stessa chiave
 * di firma a ogni build, quindi si aggiorna sopra senza disinstallare e senza perdere niente.
 */
class Updater(private val context: Context, private val http: OkHttpClient) {

    sealed interface State {
        data object Idle : State
        data object Checking : State
        /** [info] null: non si è riusciti a sapere (niente rete). */
        data class UpToDate(val info: ReleaseInfo?) : State
        data class Available(val info: ReleaseInfo) : State
        data class Downloading(val info: ReleaseInfo, val progress: Float?) : State
        data class Installing(val info: ReleaseInfo) : State
        data class Failed(val info: ReleaseInfo?, val message: String) : State
    }

    private val prefs = context.getSharedPreferences("update", Context.MODE_PRIVATE)
    private val client = UpdateClient(http)
    private val pkg = context.packageName

    /** La versione installata (il numero di build di GitHub Actions). */
    val installed: Long = PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(pkg, 0))
    val installedName: String = context.packageManager.getPackageInfo(pkg, 0).versionName.orEmpty()

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _skipped = MutableStateFlow(prefs.getLong(KEY_SKIPPED, 0))
    /** La versione per cui si è detto "Più tardi": niente avviso in Home finché non ne esce un'altra. */
    val skipped: StateFlow<Long> = _skipped.asStateFlow()

    private val _whatsNew = MutableStateFlow<List<ReleaseNotes>>(emptyList())
    /** Le novità da mostrare una volta dopo un aggiornamento. */
    val whatsNew: StateFlow<List<ReleaseNotes>> = _whatsNew.asStateFlow()

    init {
        // Prima installazione (o versione che ancora non lo ricordava): niente "novità" da mostrare.
        if (prefs.getLong(KEY_SEEN, 0) == 0L) prefs.edit { putLong(KEY_SEEN, installed) }
        cached()?.let { show(it) }
    }

    private fun cached(): ReleaseInfo? = prefs.getString(KEY_JSON, null)?.let(AppUpdate::parse)

    private fun show(info: ReleaseInfo) {
        if (_state.value is State.Downloading || _state.value is State.Installing) return
        _state.value = if (info.isNewerThan(installed)) State.Available(info) else State.UpToDate(info)
        val seen = prefs.getLong(KEY_SEEN, installed)
        if (seen < installed) _whatsNew.value = info.changesBetween(seen, installed)
    }

    /** Controlla se c'è una versione nuova; da solo al massimo ogni qualche ora, [force] dal tasto. */
    suspend fun check(force: Boolean = false) {
        val busy = _state.value
        if (busy is State.Checking || busy is State.Downloading || busy is State.Installing) return
        if (!force && System.currentTimeMillis() - prefs.getLong(KEY_CHECKED, 0) < CHECK_EVERY_MS) return
        if (force) _state.value = State.Checking
        val info = client.latest()
        if (info == null) {
            if (force) _state.value = State.UpToDate(null)
            return
        }
        prefs.edit {
            putString(KEY_JSON, kotlinx.serialization.json.Json.encodeToString(ReleaseInfo.serializer(), info))
            putLong(KEY_CHECKED, System.currentTimeMillis())
        }
        show(info)
    }

    fun later(info: ReleaseInfo) {
        prefs.edit { putLong(KEY_SKIPPED, info.versionCode.toLong()) }
        _skipped.value = info.versionCode.toLong()
    }

    fun dismissWhatsNew() {
        prefs.edit { putLong(KEY_SEEN, installed) }
        _whatsNew.value = emptyList()
    }

    /** Android chiede una volta di consentire a BaldMusic di installare app. */
    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    fun permissionIntent(): Intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$pkg"))

    /** Scarica l'APK nuovo e lo passa all'installatore; la conferma arriva in [onInstallStatus]. */
    suspend fun install(info: ReleaseInfo) {
        _state.value = State.Downloading(info, 0f)
        val file = runCatching { download(info) }.getOrElse {
            _state.value = State.Failed(info, "Download non riuscito: controlla la connessione e riprova.")
            return
        }
        // Mai installare qualcosa che non sia una versione più nuova di BaldMusic.
        val archive = context.packageManager.getPackageArchiveInfo(file.path, 0)
        if (archive == null || archive.packageName != pkg || PackageInfoCompat.getLongVersionCode(archive) <= installed) {
            file.delete()
            _state.value = State.Failed(info, "Il file scaricato non è un aggiornamento di BaldMusic. Riprova tra qualche minuto.")
            return
        }
        _state.value = State.Installing(info)
        runCatching { commit(file) }.onFailure {
            _state.value = State.Failed(info, "L'installazione non è partita: ${it.message ?: "errore sconosciuto"}")
        }
    }

    private suspend fun download(info: ReleaseInfo): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "aggiornamento").apply { mkdirs() }
        val file = File(dir, "baldmusic.apk")
        val request = Request.Builder().url(AppUpdate.apkUrl(info)).build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            val body = response.body
            val total = body.contentLength().takeIf { it > 0 } ?: info.size
            body.byteStream().use { input ->
                file.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    var last = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        done += n
                        if (total != null && done - last > 256 * 1024) {
                            last = done
                            _state.value = State.Downloading(info, (done.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
            }
        }
        file
    }

    private suspend fun commit(file: File) = withContext(Dispatchers.IO) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(pkg)
            // Da Android 12, se l'ultima volta l'ha installata BaldMusic stessa, niente conferma.
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("baldmusic.apk", 0, file.length()).use { out ->
                file.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val status = Intent(context, MainActivity::class.java).setAction(ACTION_INSTALL_STATUS).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            session.commit(PendingIntent.getActivity(context, id, status, flags).intentSender)
        }
    }

    /** Risposta dell'installatore (MainActivity la riceve e la passa qui). */
    fun onInstallStatus(activity: Activity, intent: Intent) {
        if (intent.action != ACTION_INSTALL_STATUS) return
        val info = (state.value as? State.Installing)?.info ?: cached()
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // La finestra di Android "Vuoi aggiornare questa app?".
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)?.let { runCatching { activity.startActivity(it) } }
            }
            PackageInstaller.STATUS_SUCCESS -> _state.value = State.Idle
            PackageInstaller.STATUS_FAILURE_ABORTED -> _state.value = info?.let { State.Available(it) } ?: State.Idle
            else -> _state.value = State.Failed(info, failure(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)))
        }
    }

    private fun failure(status: Int, detail: String?): String = when (status) {
        PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
            "Android non accetta l'aggiornamento (firma diversa da quella installata). Scarica l'APK dalla pagina della release."
        PackageInstaller.STATUS_FAILURE_STORAGE -> "Spazio insufficiente sul telefono per l'aggiornamento."
        PackageInstaller.STATUS_FAILURE_BLOCKED -> "Android ha bloccato l'installazione."
        else -> "Aggiornamento non riuscito" + (detail?.let { ": $it" } ?: ".")
    }

    companion object {
        const val ACTION_INSTALL_STATUS = "io.github.baldborne94.baldmusic.INSTALL_STATUS"
        const val RELEASE_PAGE = "https://github.com/Baldborne94/baldmusic-player/releases/tag/apk-latest"
        private const val CHECK_EVERY_MS = 6 * 60 * 60 * 1000L
        private const val KEY_JSON = "latest"
        private const val KEY_CHECKED = "checked_at"
        private const val KEY_SKIPPED = "skipped"
        private const val KEY_SEEN = "seen"
    }
}
