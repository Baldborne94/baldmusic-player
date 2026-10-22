package io.github.baldborne94.baldmusic.ui

import android.app.Application
import android.content.ComponentName
import android.os.Bundle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaBrowser
import androidx.media3.session.SessionToken
import io.github.baldborne94.baldmusic.BaldMusicApp
import io.github.baldborne94.baldmusic.app
import io.github.baldborne94.baldmusic.core.library.LocalCatalog
import io.github.baldborne94.baldmusic.core.library.RemoteSource
import io.github.baldborne94.baldmusic.core.library.UserLibrary
import io.github.baldborne94.baldmusic.core.podcast.PodcastSource
import io.github.baldborne94.baldmusic.playback.EXTRA_SHUFFLE
import io.github.baldborne94.baldmusic.playback.PlaybackService
import io.github.baldborne94.baldmusic.playback.toTrackRef
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch

/**
 * L'interfaccia parla col servizio di riproduzione esattamente come Android Auto:
 * con un MediaBrowser. Quello che si vede sul telefono è lo stesso albero che si
 * vede in macchina.
 */
class PlayerViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application.app

    var browser by mutableStateOf<MediaBrowser?>(null)
        private set

    var current by mutableStateOf<MediaItem?>(null)
        private set
    var isPlaying by mutableStateOf(false)
        private set
    var buffering by mutableStateOf(false)
        private set
    var shuffle by mutableStateOf(false)
        private set
    var repeat by mutableIntStateOf(Player.REPEAT_MODE_OFF)
        private set
    var position by mutableLongStateOf(0L)
        private set
    var duration by mutableLongStateOf(0L)
        private set
    var queue by mutableStateOf<List<MediaItem>>(emptyList())
        private set
    var queueIndex by mutableIntStateOf(-1)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    /** Da dove arriva quello che suona ("Brani che ti piacciono", il nome dell'album…). */
    var playingFrom by mutableStateOf<String?>(null)
        private set

    /** Il contenitore della coda in corso, per il tasto play/pausa delle pagine. */
    var playingContainer by mutableStateOf<String?>(null)
        private set

    /** Cresce a ogni cambio di libreria (server, permessi): le schermate si ricaricano. */
    var libraryVersion by mutableIntStateOf(0)
        private set

    val library: StateFlow<UserLibrary> = app.userData.state
    val sleepTimer: StateFlow<Long?> = app.sleepTimer
    var lyricsOnline: Boolean
        get() = app.settings.lyricsOnline
        set(value) {
            app.settings.lyricsOnline = value
        }

    private val listener = object : Player.Listener {
        // La coda si rilegge solo quando cambia: play, pausa e buffering non la toccano.
        override fun onEvents(player: Player, events: Player.Events) = sync(player, queueChanged = events.contains(Player.EVENT_TIMELINE_CHANGED))
    }

    init {
        viewModelScope.launch {
            val token = SessionToken(application, ComponentName(application, PlaybackService::class.java))
            val b = MediaBrowser.Builder(application, token).buildAsync().await()
            b.addListener(listener)
            browser = b
            sync(b)
        }
        viewModelScope.launch {
            while (true) {
                browser?.let { if (it.isPlaying) position = it.currentPosition }
                delay(250)
            }
        }
        viewModelScope.launch {
            app.settings.changes.collect { libraryVersion = it }
        }
        // C'è una versione nuova dell'app? (al massimo ogni qualche ora)
        checkUpdates()
    }

    val updater get() = app.updater

    fun checkUpdates(force: Boolean = false) {
        app.updater.scope.launch { app.updater.check(force) }
    }

    fun installUpdate(info: io.github.baldborne94.baldmusic.core.update.ReleaseInfo) {
        app.updater.scope.launch { app.updater.install(info) }
    }

    private fun sync(p: Player, queueChanged: Boolean = true) {
        current = p.currentMediaItem
        isPlaying = p.isPlaying
        buffering = p.playbackState == Player.STATE_BUFFERING
        shuffle = p.shuffleModeEnabled
        repeat = p.repeatMode
        position = p.currentPosition
        duration = p.duration.coerceAtLeast(0)
        if (queueChanged || queue.size != p.mediaItemCount) queue = (0 until p.mediaItemCount).map { p.getMediaItemAt(it) }
        queueIndex = p.currentMediaItemIndex
        error = p.playerError?.let { "Impossibile riprodurre: ${it.errorCodeName}" }
        if (current == null) playingContainer = null
    }

    override fun onCleared() {
        browser?.run {
            removeListener(listener)
            release()
        }
    }

    // --- libreria --------------------------------------------------------

    suspend fun children(mediaId: String): Result<List<MediaItem>> {
        val b = browser ?: return Result.failure(IllegalStateException("Servizio non pronto"))
        val r = b.getChildren(mediaId, 0, Int.MAX_VALUE, null).await()
        return if (r.resultCode == LibraryResult.RESULT_SUCCESS) Result.success(r.value.orEmpty())
        // Il server può essere spento; il telefono no: lì un errore è un problema dell'app.
        else Result.failure(IllegalStateException(
            if (mediaId.startsWith("${RemoteSource.KEY}/") || mediaId == RemoteSource.KEY) "Impossibile caricare. Il server è acceso? Tailscale è attivo?"
            else "Impossibile caricare questa sezione.",
        ))
    }

    suspend fun item(mediaId: String): MediaItem? {
        val b = browser ?: return null
        val r = b.getItem(mediaId).await()
        return if (r.resultCode == LibraryResult.RESULT_SUCCESS) r.value else null
    }

    suspend fun search(query: String): List<MediaItem> {
        val b = browser ?: return emptyList()
        b.search(query, null).await()
        val r = b.getSearchResult(query, 0, 200, null).await()
        return if (r.resultCode == LibraryResult.RESULT_SUCCESS) r.value.orEmpty() else emptyList()
    }

    // --- riproduzione ----------------------------------------------------

    /** Un brano: il servizio ricostruisce da solo l'album o la playlist da cui viene. */
    fun play(item: MediaItem, from: String? = null) {
        val b = browser ?: return
        playingFrom = from ?: item.mediaMetadata.albumTitle?.toString()
        playingContainer = io.github.baldborne94.baldmusic.core.library.MediaId.parse(item.mediaId)?.parent?.toString()
        b.shuffleModeEnabled = false
        b.setMediaItem(MediaItem.Builder().setMediaId(item.mediaId).build())
        b.prepare()
        b.play()
    }

    /** Un contenitore intero (album, artista, playlist), in ordine o in casuale. */
    fun playAll(containerId: String, shuffled: Boolean, from: String? = null) {
        val b = browser ?: return
        playingFrom = from
        playingContainer = containerId
        val request = MediaItem.RequestMetadata.Builder()
            .setExtras(Bundle().apply { putBoolean(EXTRA_SHUFFLE, shuffled) })
            .build()
        b.shuffleModeEnabled = shuffled
        b.setMediaItem(MediaItem.Builder().setMediaId(containerId).setRequestMetadata(request).build())
        b.prepare()
        b.play()
    }

    /** Il tasto play di una pagina: se quella pagina sta già suonando, è pausa/riprendi. */
    fun playOrToggle(containerId: String, from: String?) {
        if (playingContainer == containerId && current != null) toggle() else playAll(containerId, shuffled = shuffle, from = from)
    }

    fun playNext(item: MediaItem) {
        val b = browser ?: return
        if (b.mediaItemCount == 0) return play(item)
        b.addMediaItem(b.currentMediaItemIndex + 1, MediaItem.Builder().setMediaId(item.mediaId).build())
    }

    fun enqueue(item: MediaItem) {
        val b = browser ?: return
        if (b.mediaItemCount == 0) return play(item)
        b.addMediaItem(MediaItem.Builder().setMediaId(item.mediaId).build())
    }

    fun toggle() {
        val b = browser ?: return
        if (b.isPlaying) b.pause() else {
            if (b.playbackState == Player.STATE_IDLE) b.prepare()
            if (b.playbackState == Player.STATE_ENDED) b.seekToDefaultPosition(0)
            b.play()
        }
    }

    fun next() = browser?.seekToNext()
    fun previous() = browser?.seekToPrevious()

    fun seekTo(ms: Long) {
        browser?.seekTo(ms)
        position = ms
    }

    /** Nelle puntate: indietro di 15 s o avanti di 30 s, con la stessa regola della notifica e di Auto. */
    fun skipBack() = browser?.sendCustomCommand(androidx.media3.session.SessionCommand(PlaybackService.CMD_BACK, Bundle.EMPTY), Bundle.EMPTY)
    fun skipForward() = browser?.sendCustomCommand(androidx.media3.session.SessionCommand(PlaybackService.CMD_FORWARD, Bundle.EMPTY), Bundle.EMPTY)

    fun skipTo(index: Int) {
        browser?.run {
            seekToDefaultPosition(index)
            play()
        }
    }

    fun removeAt(index: Int) = browser?.removeMediaItem(index)
    fun move(from: Int, to: Int) = browser?.moveMediaItem(from, to)

    fun toggleShuffle() {
        browser?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled }
    }

    fun cycleRepeat() {
        browser?.let {
            it.repeatMode = when (it.repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
        }
    }

    fun clearUpcoming() = browser?.run {
        val from = currentMediaItemIndex + 1
        if (from < mediaItemCount) removeMediaItems(from, mediaItemCount)
    }

    // --- la tua musica ---------------------------------------------------

    fun isLiked(lib: UserLibrary, item: MediaItem?) = item != null && lib.isLiked(item.mediaId)
    fun toggleLike(item: MediaItem) = app.userData.toggleLike(item.toTrackRef())
    fun createPlaylist(name: String, first: List<MediaItem> = emptyList()) = app.userData.createPlaylist(name, first.map { it.toTrackRef() })
    fun addToPlaylist(playlistId: String, items: List<MediaItem>) = app.userData.addToPlaylist(playlistId, items.map { it.toTrackRef() })
    fun removeFromPlaylist(playlistId: String, item: MediaItem) = app.userData.removeFromPlaylist(playlistId, item.mediaId)
    fun removeFromPlaylist(playlistId: String, items: List<MediaItem>) = app.userData.removeFromPlaylist(playlistId, items.map { it.mediaId })
    fun reorderPlaylist(playlistId: String, items: List<MediaItem>) = app.userData.reorderPlaylist(playlistId, items.map { it.mediaId })
    fun sortPlaylist(playlistId: String, byArtist: Boolean) = app.userData.sortPlaylist(playlistId, byArtist)
    fun renamePlaylist(playlistId: String, name: String) = app.userData.renamePlaylist(playlistId, name)
    fun deletePlaylist(playlistId: String) = app.userData.deletePlaylist(playlistId)

    /** Raccolta eliminata insieme ai suoi file: se la cartella si riempie di nuovo, torna. */
    fun deleteCollectionWithFiles(playlistId: String) = app.userData.deletePlaylist(playlistId, ignoreFolder = false)

    /**
     * Brani ripescati dal cestino: si rilegge la libreria e ognuno torna al suo posto
     * nella raccolta della sua cartella, al posto della copia che lo aveva sostituito.
     */
    fun restoreTrashed(ids: Set<Long>) {
        viewModelScope.launch {
            app.local.invalidate()
            app.tree.invalidate()
            val all = app.local.allTracks()
            app.userData.restoreInto(all.filter { it.id in ids }, all)
            app.settings.touch()
        }
    }

    /** Doppioni buttati: preferiti, playlist e raccolte passano alla copia tenuta. */
    fun mergeDuplicates(groups: List<io.github.baldborne94.baldmusic.core.library.DuplicateGroup>) {
        val keep = groups.flatMap { g -> g.others.map { LocalCatalog.ref(it).id to LocalCatalog.ref(g.keep) } }.toMap()
        app.userData.mergeTracks(keep)
    }

    /** Dopo che i file sono finiti nel cestino: via dalla coda, dalla tua musica e dalla libreria. */
    fun forgetTrashed(items: List<MediaItem>) {
        val gone = items.mapTo(HashSet()) { UserLibrary.baseId(it.mediaId) }
        queue.indices.reversed().filter { UserLibrary.baseId(queue[it].mediaId) in gone }.forEach { removeAt(it) }
        // Prima si svuotano le cache (brani del telefono e elenchi già aperti), poi si
        // avvisa chi ricarica: altrimenti una pagina può rileggere l'elenco vecchio, col
        // brano buttato ancora dentro, e toccarlo farebbe partire quello dopo.
        app.local.invalidate()
        app.tree.invalidate()
        app.userData.forgetTracks(gone)
        app.settings.touch()
    }
    fun addSearch(query: String) = app.userData.addSearch(query)
    fun clearSearches() = app.userData.clearSearches()

    /** Timer di spegnimento: minuti, 0 = a fine brano, null = spento. */
    fun setSleepTimer(minutes: Int?) {
        app.sleepTimer.value = when (minutes) {
            null -> null
            0 -> BaldMusicApp.SLEEP_END_OF_TRACK
            else -> System.currentTimeMillis() + minutes * 60_000L
        }
    }

    /** Il testo del brano; le puntate dei podcast non ne hanno (e non si chiede a LRCLIB). */
    suspend fun lyricsFor(item: MediaItem) = if (item.mediaId.startsWith("${PodcastSource.KEY}/")) null else app.lyrics.get(
        item.mediaMetadata.artist?.toString(),
        item.mediaMetadata.title?.toString().orEmpty(),
        item.mediaMetadata.albumTitle?.toString(),
        item.mediaMetadata.durationMs ?: duration.takeIf { it > 0 },
        online = app.settings.lyricsOnline,
    )

    /** Radio da un brano del telefono: lui e poi musica simile. */
    fun startRadio(item: MediaItem) {
        val id = io.github.baldborne94.baldmusic.core.library.MediaId.parse(item.mediaId) ?: return
        if (id.source != io.github.baldborne94.baldmusic.core.library.LocalCatalog.KEY || id.id == null) return
        playAll("${id.source}/${io.github.baldborne94.baldmusic.core.library.LocalCatalog.RADIO}/${id.id}", shuffled = false, from = "Radio: ${item.title()}")
    }

    fun canStartRadio(item: MediaItem) =
        io.github.baldborne94.baldmusic.core.library.MediaId.parse(item.mediaId)?.source == io.github.baldborne94.baldmusic.core.library.LocalCatalog.KEY
}
