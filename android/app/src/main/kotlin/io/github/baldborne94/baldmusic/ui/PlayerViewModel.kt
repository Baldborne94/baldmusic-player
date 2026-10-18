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
import io.github.baldborne94.baldmusic.core.library.UserLibrary
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
        override fun onEvents(player: Player, events: Player.Events) = sync(player)
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
    }

    private fun sync(p: Player) {
        current = p.currentMediaItem
        isPlaying = p.isPlaying
        buffering = p.playbackState == Player.STATE_BUFFERING
        shuffle = p.shuffleModeEnabled
        repeat = p.repeatMode
        position = p.currentPosition
        duration = p.duration.coerceAtLeast(0)
        queue = (0 until p.mediaItemCount).map { p.getMediaItemAt(it) }
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
        else Result.failure(IllegalStateException("Impossibile caricare. Il server è acceso? Tailscale è attivo?"))
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
    fun renamePlaylist(playlistId: String, name: String) = app.userData.renamePlaylist(playlistId, name)
    fun deletePlaylist(playlistId: String) = app.userData.deletePlaylist(playlistId)
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

    suspend fun lyricsFor(item: MediaItem) = app.lyrics.get(
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
