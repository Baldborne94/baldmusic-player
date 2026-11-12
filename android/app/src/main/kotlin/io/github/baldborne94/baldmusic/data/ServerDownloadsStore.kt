package io.github.baldborne94.baldmusic.data

import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.baldborne94.baldmusic.BaldMusicApp
import io.github.baldborne94.baldmusic.app
import io.github.baldborne94.baldmusic.core.library.DownloadedSet
import io.github.baldborne94.baldmusic.core.library.MediaId
import io.github.baldborne94.baldmusic.core.library.RemoteSource
import io.github.baldborne94.baldmusic.core.library.ServerDownloads
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Request
import java.io.File

/**
 * Album e playlist del PC salvati sul telefono (regole in [ServerDownloads]): l'elenco in
 * `files/dalpc/scaricati.json`, i file originali in `files/dalpc/audio/<brano>.audio` (mai in
 * Download: sono dell'app), le copertine in `files/dalpc/cover/`. Scarica [ServerDownloadWorker]
 * con qualsiasi rete (li hai chiesti tu), riprendendo da dove era se Android lo ferma.
 */
class ServerDownloadsStore(private val app: BaldMusicApp) {
    private val dir = File(app.filesDir, "dalpc")
    private val audioDir = File(dir, "audio").apply { mkdirs() }
    private val coverDir = File(dir, "cover").apply { mkdirs() }
    private val index = File(dir, "scaricati.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()

    private val _state = MutableStateFlow(runCatching { json.decodeFromString(ServerDownloads.serializer(), index.readText()) }.getOrDefault(ServerDownloads()))
    val state: StateFlow<ServerDownloads> = _state.asStateFlow()

    /** I brani già sul telefono: in memoria, così gli elenchi non guardano il disco per ogni riga. */
    private val _present = MutableStateFlow(scan())
    val present: StateFlow<Set<String>> = _present.asStateFlow()

    private fun scan(): Set<String> = audioDir.listFiles().orEmpty().filter { it.name.endsWith(".audio") }.mapTo(HashSet()) { it.name.removeSuffix(".audio") }

    private fun audioFile(songId: String) = File(audioDir, "$songId.audio")

    /** Il file di un brano scaricato, per suonarlo senza rete; null se non c'è (ancora). */
    fun fileOf(songId: String): String? = if (songId in _present.value) audioFile(songId).toURI().toString() else null

    /** La copertina salvata sul telefono, per quando il server non risponde. */
    fun coverFile(coverArt: String): File? = File(coverDir, coverArt).takeIf { it.exists() && it.length() > 0 }

    /** «Scarica sul telefono» da un album o una playlist del server. */
    suspend fun add(container: String): Unit = withContext(Dispatchers.IO) {
        val id = MediaId.parse(container)?.takeIf { ServerDownloads.downloadable(container) } ?: return@withContext
        val client = app.subsonic() ?: return@withContext
        val set = when (id.kind) {
            RemoteSource.ALBUM -> client.album(id.id!!).let { DownloadedSet(container, it.name, it.artist, it.coverArt, it.song) }
            else -> client.playlist(id.id!!).let { DownloadedSet(container, it.name, null, it.coverArt, it.entry) }
        }
        change { it.with(set) }
        ServerDownloadWorker.now(app)
    }

    /** «Togli dal telefono»: i file si cancellano al giro dopo (subito, se c'è). */
    suspend fun remove(container: String): Unit = withContext(Dispatchers.IO) {
        change { it.without(container) }
        sync(downloadNew = false)
    }

    private fun change(edit: (ServerDownloads) -> ServerDownloads) {
        val next = edit(_state.value)
        if (next == _state.value) return
        _state.value = next
        dir.mkdirs()
        val tmp = File(dir, "${index.name}.tmp")
        tmp.writeText(json.encodeToString(ServerDownloads.serializer(), next))
        tmp.renameTo(index)
        // «Scaricati sul telefono» cambia: Android Auto e l'app rileggono la libreria.
        app.settings.touch()
    }

    /**
     * Cambio di server (dal PC al NAS): album e playlist scaricati passano allo stesso brano sul
     * server nuovo ([ServerDownloads.movedTo]); i file si rinominano, non si riscaricano.
     */
    suspend fun moveTo(match: io.github.baldborne94.baldmusic.core.library.ServerSync.Matcher, playlists: Map<String, String>) = mutex.withLock {
        val (moved, renames) = _state.value.movedTo(match, playlists)
        if (renames.isEmpty()) return@withLock
        for ((from, to) in renames) {
            val target = audioFile(to)
            if (!target.exists()) audioFile(from).renameTo(target)
        }
        _present.value = scan()
        change { moved }
    }

    /** Scarica quello che manca e cancella quello che non serve più; true se resta qualcosa da scaricare. */
    suspend fun sync(downloadNew: Boolean = true): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val (download, delete) = _state.value.plan(_present.value)
            delete.forEach { audioFile(it).delete() }
            val covers = _state.value.covers
            coverDir.listFiles().orEmpty().filter { it.name !in covers }.forEach { it.delete() }
            var left = download.size
            if (downloadNew) {
                val client = app.subsonic() ?: return@withContext download.isNotEmpty()
                for (cover in covers) if (coverFile(cover) == null) fetch(client.coverUrl(cover, 512), File(coverDir, cover))
                for (songId in download) {
                    if (fetch(client.streamUrl(songId), audioFile(songId))) left--
                    _present.value = scan()
                }
            }
            _present.value = scan()
            if (delete.isNotEmpty() || download.size != left) app.settings.touch()
            left > 0
        }
    }

    /** Un file intero o niente: si scrive accanto e si rinomina alla fine. */
    private fun fetch(url: String, out: File): Boolean {
        val tmp = File(out.parentFile, "${out.name}.part")
        return try {
            app.http.newCall(Request.Builder().url(url).build()).execute().use { res ->
                if (!res.isSuccessful) return false
                tmp.outputStream().use { res.body.byteStream().copyTo(it) }
            }
            tmp.length() > 0 && tmp.renameTo(out)
        } catch (e: Exception) {
            Log.w("ServerDownloads", "Scaricamento non riuscito", e)
            false
        } finally {
            tmp.delete()
        }
    }

    /** Lo spazio occupato, in byte. */
    fun bytes(): Long = audioDir.listFiles().orEmpty().sumOf { it.length() }
}

/** Scarica gli album e le playlist del PC chiesti, appena c'è la rete; riprende se Android lo ferma. */
class ServerDownloadWorker(context: android.content.Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = if (applicationContext.app.serverDownloads.sync()) Result.retry() else Result.success()

    companion object {
        private const val NAME = "scarica-dal-pc"

        fun now(context: android.content.Context) {
            val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            val request = OneTimeWorkRequestBuilder<ServerDownloadWorker>().setConstraints(constraints).build()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}
