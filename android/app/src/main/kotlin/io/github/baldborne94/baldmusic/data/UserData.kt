package io.github.baldborne94.baldmusic.data

import android.content.Context
import io.github.baldborne94.baldmusic.core.library.ContainerRef
import io.github.baldborne94.baldmusic.core.library.FolderCollection
import io.github.baldborne94.baldmusic.core.library.Preload
import io.github.baldborne94.baldmusic.core.library.TrackRef
import io.github.baldborne94.baldmusic.core.library.UserLibrary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * Brani che ti piacciono, playlist e cronologia, salvati in un file JSON
 * nell'app. Le regole stanno in [UserLibrary] (modulo core, testato); qui solo
 * stato osservabile e salvataggio.
 */
class UserData(context: Context) {

    private val file = File(context.filesDir, "libreria.json")
    private val json = Json { ignoreUnknownKeys = true }
    // Un salvataggio alla volta: update() arriva sia dall'interfaccia sia dai thread che leggono
    // MediaStore, e due scritture insieme dello stesso .tmp rovinerebbero il file.
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val lock = Any()
    private var pendingSave: Job? = null

    // Si legge in sottofondo appena l'app parte (anche da Android Auto o dal widget): l'avvio non
    // resta fermo sul file, e chi chiede la tua musica per primo aspetta solo quello che manca.
    private val loaded = Preload(Dispatchers.IO.asExecutor(), ::load)
    private val _state by lazy { MutableStateFlow(loaded.value) }
    val state: StateFlow<UserLibrary> by lazy { _state.asStateFlow() }

    val value: UserLibrary get() = _state.value

    private fun load(): UserLibrary {
        if (!file.exists()) return UserLibrary()
        return runCatching { json.decodeFromString(UserLibrary.serializer(), file.readText()) }.getOrElse {
            // File rovinato: si mette da parte invece di scriverci sopra una libreria vuota, così si
            // può ancora recuperare (e c'è il backup in Download/BaldMusic).
            runCatching { file.copyTo(File(file.parentFile, "libreria-rovinata-${System.currentTimeMillis()}.json")) }
            UserLibrary()
        }
    }

    private fun update(change: (UserLibrary) -> UserLibrary) {
        val before = _state.value
        _state.update(change)
        if (_state.value === before) return
        // Gli ascolti arrivano a raffica (salti di brano): si salva una volta sola.
        synchronized(lock) {
            pendingSave?.cancel()
            pendingSave = scope.launch {
                delay(800)
                val tmp = File(file.parentFile, "${file.name}.tmp")
                tmp.writeText(json.encodeToString(UserLibrary.serializer(), _state.value))
                tmp.renameTo(file)
            }
        }
    }

    fun isLiked(trackId: String) = value.isLiked(trackId)
    fun toggleLike(track: TrackRef) = update { it.toggleLike(track) }
    fun recordPlay(track: TrackRef, container: ContainerRef?) = update { it.recordPlay(track, container, System.currentTimeMillis()) }

    fun touchRecent(container: ContainerRef?) = update { it.touchRecent(container) }

    /** Crea una playlist e ne restituisce l'ID. */
    fun createPlaylist(name: String, first: List<TrackRef> = emptyList()): String {
        val id = UUID.randomUUID().toString().take(8)
        update { it.createPlaylist(id, name, System.currentTimeMillis(), first) }
        return id
    }

    fun renamePlaylist(id: String, name: String) = update { it.renamePlaylist(id, name) }
    fun deletePlaylist(id: String, ignoreFolder: Boolean = true) = update { it.deletePlaylist(id, ignoreFolder) }
    fun forgetTracks(trackIds: Collection<String>) = update { it.forgetTracks(trackIds) }
    fun mergeTracks(replacements: Map<String, TrackRef>) = update { it.mergeTracks(replacements) }
    fun restoreInto(restored: List<io.github.baldborne94.baldmusic.core.library.LocalTrack>, all: List<io.github.baldborne94.baldmusic.core.library.LocalTrack>) =
        update { io.github.baldborne94.baldmusic.core.library.LibraryCleanup.restoreInto(it, restored, all) }
    fun addToPlaylist(id: String, tracks: List<TrackRef>) = update { it.addToPlaylist(id, tracks) }
    fun removeFromPlaylist(id: String, trackId: String) = update { it.removeFromPlaylist(id, trackId) }
    fun removeFromPlaylist(id: String, trackIds: Collection<String>) = update { lib -> trackIds.fold(lib) { l, t -> l.removeFromPlaylist(id, t) } }
    fun reorderPlaylist(id: String, orderedIds: List<String>) = update { it.reorderPlaylist(id, orderedIds) }
    fun sortPlaylist(id: String, byArtist: Boolean) = update { it.sortPlaylist(id, byArtist) }
    fun syncFolders(folders: List<FolderCollection>, present: Set<String>) =
        update { it.syncFolders(folders, present, System.currentTimeMillis()) { UUID.randomUUID().toString().take(8) } }

    /** Ripristino da un backup: tutta la tua musica cambia. */
    fun replaceAll(library: UserLibrary) = update { library }

    fun addSearch(query: String) = update { it.addSearch(query) }
    fun clearSearches() = update { it.clearSearches() }
}
