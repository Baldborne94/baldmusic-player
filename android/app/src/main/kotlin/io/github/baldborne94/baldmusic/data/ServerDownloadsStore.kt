package io.github.baldborne94.baldmusic.data

import android.net.Uri
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
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import okhttp3.Request
import java.io.File

/**
 * Album e playlist del server salvati sul telefono (regole in [ServerDownloads]): l'elenco in
 * `files/dalpc/scaricati.json`, le copertine in `files/dalpc/cover/`. I file originali vanno nella
 * cartella scelta nelle Impostazioni ([DownloadFolder]: una sottocartella per album o raccolta,
 * brani normali del telefono; dove sta ogni brano in `files/dalpc/posizioni.json`), o senza
 * cartella scelta dentro l'app (`files/dalpc/audio/<brano>.audio`). Scarica [ServerDownloadWorker]
 * con qualsiasi rete (li hai chiesti tu), riprendendo da dove era se Android lo ferma.
 */
class ServerDownloadsStore(private val app: BaldMusicApp) {
    private val dir = File(app.filesDir, "dalpc")
    private val audioDir = File(dir, "audio").apply { mkdirs() }
    private val coverDir = File(dir, "cover").apply { mkdirs() }
    private val index = File(dir, "scaricati.json")
    private val placesFile = File(dir, "posizioni.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()

    private val _state = MutableStateFlow(runCatching { json.decodeFromString(ServerDownloads.serializer(), index.readText()) }.getOrDefault(ServerDownloads()))
    val state: StateFlow<ServerDownloads> = _state.asStateFlow()

    /** Brano → il suo file nella cartella scelta (`content://…`). */
    private val placesSerializer = MapSerializer(String.serializer(), String.serializer())
    @Volatile private var places: Map<String, String> = runCatching { json.decodeFromString(placesSerializer, placesFile.readText()) }.getOrDefault(emptyMap())

    private fun savePlaces(next: Map<String, String>) {
        places = next
        dir.mkdirs()
        val tmp = File(dir, "${placesFile.name}.tmp")
        tmp.writeText(json.encodeToString(placesSerializer, next))
        tmp.renameTo(placesFile)
    }

    /** I brani già sul telefono: in memoria, così gli elenchi non guardano il disco per ogni riga. */
    // All'avvio senza guardare la cartella (sarebbero centinaia di domande ad Android): lo fa [sync].
    private val _present = MutableStateFlow(scan(verify = false))
    val present: StateFlow<Set<String>> = _present.asStateFlow()

    /**
     * Dentro l'app e nella cartella scelta. Con [verify] un file della cartella spostato o
     * cancellato a mano si dimentica: se lo vuoi ancora si riscarica.
     */
    private fun scan(verify: Boolean = true): Set<String> {
        val inside = audioDir.listFiles().orEmpty().filter { it.name.endsWith(".audio") }.mapTo(HashSet()) { it.name.removeSuffix(".audio") }
        if (!verify) return inside + places.keys
        val kept = places.filter { (_, doc) -> DownloadFolder.exists(app, Uri.parse(doc)) }
        if (kept.size != places.size) savePlaces(kept)
        return inside + kept.keys
    }

    private fun audioFile(songId: String) = File(audioDir, "$songId.audio")

    /** Il file di un brano scaricato, per suonarlo senza rete; null se non c'è (ancora). */
    fun fileOf(songId: String): String? = when {
        songId !in _present.value -> null
        else -> places[songId] ?: audioFile(songId).toURI().toString()
    }

    /** La cartella scelta, se c'è e si può ancora scrivere; se no si scarica dentro l'app. */
    fun folder(): DownloadFolder? = app.settings.downloadFolder
        ?.let { runCatching { DownloadFolder(app, Uri.parse(it)) }.getOrNull() }
        ?.takeIf { it.writable() }

    /** Un brano scaricato nella cartella, da mettere nel cestino quando togli quello che lo voleva. */
    data class InFolder(val song: io.github.baldborne94.baldmusic.core.subsonic.Song, val media: Uri)

    /**
     * I file nella cartella che nessun altro album o playlist scaricato vuole più, togliendo
     * [containers]: vanno nel cestino di Android (scelta dell'utente), con la sua finestra.
     * Solo quelli che Android ha già visto come musica (`MediaStore.getMediaUri`).
     */
    suspend fun trashable(containers: Set<String>): List<InFolder> = withContext(Dispatchers.IO) {
        if (android.os.Build.VERSION.SDK_INT < 29) return@withContext emptyList()
        val state = _state.value
        val gone = containers.fold(state) { acc, c -> acc.without(c) }
        val stillWanted = gone.songIds
        state.sets.filter { it.container in containers }.flatMap { it.songs }.distinctBy { it.id }
            .filter { it.id !in stillWanted }
            .mapNotNull { song ->
                val doc = places[song.id] ?: return@mapNotNull null
                val media = if (android.os.Build.VERSION.SDK_INT >= 29) {
                    runCatching { android.provider.MediaStore.getMediaUri(app, Uri.parse(doc)) }.getOrNull()
                } else null
                if (media == null) return@mapNotNull null
                InFolder(song, media)
            }
    }

    /**
     * Dopo il cestino: cuori, playlist e ascolti di quei file (l'allineamento col server li aveva
     * passati al file del telefono) tornano al brano del server, poi si tolgono gli album e le
     * playlist e si dimenticano i file.
     */
    suspend fun removeAfterTrash(containers: Set<String>, trashed: List<InFolder>): Unit = withContext(Dispatchers.IO) {
        val back = trashed.associate { f ->
            io.github.baldborne94.baldmusic.core.library.MediaId.track(io.github.baldborne94.baldmusic.core.library.LocalCatalog.KEY, android.content.ContentUris.parseId(f.media).toString(), null).toString() to
                io.github.baldborne94.baldmusic.core.library.ServerSync.Matcher.remoteRef(f.song)
        }
        if (back.isNotEmpty()) app.userData.mergeTracks(back)
        mutex.withLock { savePlaces(places - trashed.map { it.song.id }.toSet()) }
        change { s -> containers.fold(s) { acc, c -> acc.without(c) } }
        sync(downloadNew = false)
        app.local.invalidate()
        app.settings.touch()
    }

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
        var placed = places
        for ((from, to) in renames) {
            // Nella cartella scelta il file è lo stesso: cambia solo il brano del server a cui appartiene.
            val doc = placed[from]
            if (doc != null) {
                placed = placed - from + (to to doc)
            } else {
                val target = audioFile(to)
                if (!target.exists()) audioFile(from).renameTo(target)
            }
        }
        if (placed != places) savePlaces(placed)
        _present.value = scan()
        change { moved }
    }

    /** Scarica quello che manca e cancella quello che non serve più; true se resta qualcosa da scaricare. */
    suspend fun sync(downloadNew: Boolean = true): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val (download, delete) = _state.value.plan(_present.value)
            // Dentro l'app si cancella; nella cartella scelta il file è musica tua: si dimentica
            // e basta (va nel cestino solo quando togli tu l'album o la playlist, [trashable]).
            val forget = delete.filter { it in places }
            if (forget.isNotEmpty()) savePlaces(places - forget.toSet())
            delete.filter { it !in forget }.forEach { audioFile(it).delete() }
            val covers = _state.value.covers
            coverDir.listFiles().orEmpty().filter { it.name !in covers }.forEach { it.delete() }
            var left = download.size
            if (downloadNew) {
                val client = app.subsonic() ?: return@withContext download.isNotEmpty()
                for (cover in covers) if (coverFile(cover) == null) fetch(client.coverUrl(cover, 512), File(coverDir, cover))
                val folder = folder()
                val owner = HashMap<String, DownloadedSet>()
                _state.value.sets.forEach { set -> set.songs.forEach { owner.putIfAbsent(it.id, set) } }
                val dirs = HashMap<String, Pair<Uri, MutableMap<String, Uri>>?>()
                for (songId in download) {
                    val set = owner[songId]
                    val song = set?.songs?.firstOrNull { it.id == songId }
                    val ok = if (folder != null && set != null && song != null) {
                        val place = dirs.getOrPut(set.container) {
                            runCatching { folder.subfolder(ServerDownloads.folderName(set))?.let { it to folder.children(it).toMutableMap() } }.getOrNull()
                        }
                        if (place != null) fetchToFolder(folder, place, client.streamUrl(songId), songId, ServerDownloads.fileName(song, set))
                        else fetch(client.streamUrl(songId), audioFile(songId))
                    } else {
                        fetch(client.streamUrl(songId), audioFile(songId))
                    }
                    if (ok) {
                        left--
                        _present.value = _present.value + songId
                    }
                }
            }
            _present.value = scan()
            if (delete.isNotEmpty() || download.size != left) app.settings.touch()
            left > 0
        }
    }

    /**
     * Nella cartella scelta: un file col nome leggibile nella sottocartella dell'album o della
     * raccolta. Se c'è già un file con quel nome (riscaricato, o tuo) si tiene quello, mai
     * sovrascritto; uno scaricato a metà si cancella.
     */
    private fun fetchToFolder(folder: DownloadFolder, place: Pair<Uri, MutableMap<String, Uri>>, url: String, songId: String, name: String): Boolean {
        val (dir, files) = place
        files[name]?.let { existing ->
            savePlaces(places + (songId to existing.toString()))
            return true
        }
        val doc = runCatching { folder.create(dir, name) }.getOrNull() ?: return false
        val ok = try {
            app.http.newCall(Request.Builder().url(url).build()).execute().use { res ->
                if (!res.isSuccessful) return@use false
                app.contentResolver.openOutputStream(doc, "w")?.use { out -> res.body.byteStream().copyTo(out) } != null
            }
        } catch (e: Exception) {
            Log.w("ServerDownloads", "Scaricamento nella cartella non riuscito", e)
            false
        }
        if (!ok) {
            folder.deletePartial(doc)
            return false
        }
        files[name] = doc
        savePlaces(places + (songId to doc.toString()))
        return true
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
    fun bytes(): Long = audioDir.listFiles().orEmpty().sumOf { it.length() } + places.values.sumOf { DownloadFolder.size(app, Uri.parse(it)) }
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
