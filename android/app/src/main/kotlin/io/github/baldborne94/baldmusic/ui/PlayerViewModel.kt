package io.github.baldborne94.baldmusic.ui

import android.app.Application
import android.content.ComponentName
import android.os.Bundle
import androidx.compose.runtime.getValue
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
import io.github.baldborne94.baldmusic.app
import io.github.baldborne94.baldmusic.playback.EXTRA_SHUFFLE
import io.github.baldborne94.baldmusic.playback.PlaybackService
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch

/**
 * L'interfaccia parla col servizio di riproduzione esattamente come Android Auto:
 * con un MediaBrowser. Quello che si vede sul telefono è lo stesso albero che si
 * vede in macchina.
 */
class PlayerViewModel(application: Application) : AndroidViewModel(application) {

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
    var repeat by mutableStateOf(Player.REPEAT_MODE_OFF)
        private set
    var position by mutableLongStateOf(0L)
        private set
    var duration by mutableLongStateOf(0L)
        private set
    var queue by mutableStateOf<List<MediaItem>>(emptyList())
        private set
    var queueIndex by mutableStateOf(-1)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    /** Cresce a ogni cambio di libreria (server, permessi): le schermate si ricaricano. */
    var libraryVersion by mutableStateOf(0)
        private set

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
                delay(500)
            }
        }
        viewModelScope.launch {
            application.app.settings.changes.collect { libraryVersion = it }
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
    }

    override fun onCleared() {
        browser?.run {
            removeListener(listener)
            release()
        }
    }

    suspend fun children(mediaId: String): Result<List<MediaItem>> {
        val b = browser ?: return Result.failure(IllegalStateException("Servizio non pronto"))
        val r = b.getChildren(mediaId, 0, Int.MAX_VALUE, null).await()
        return if (r.resultCode == LibraryResult.RESULT_SUCCESS) Result.success(r.value.orEmpty())
        else Result.failure(IllegalStateException("Impossibile caricare. Il server è acceso? Tailscale è attivo?"))
    }

    suspend fun search(query: String): List<MediaItem> {
        val b = browser ?: return emptyList()
        b.search(query, null).await()
        val r = b.getSearchResult(query, 0, 200, null).await()
        return if (r.resultCode == LibraryResult.RESULT_SUCCESS) r.value.orEmpty() else emptyList()
    }

    /** Un brano: il servizio ricostruisce da solo l'album o la playlist da cui viene. */
    fun play(item: MediaItem) {
        val b = browser ?: return
        b.shuffleModeEnabled = false
        b.setMediaItem(MediaItem.Builder().setMediaId(item.mediaId).build())
        b.prepare()
        b.play()
    }

    /** Un contenitore intero (album, artista, playlist), in ordine o in casuale. */
    fun playAll(containerId: String, shuffled: Boolean) {
        val b = browser ?: return
        val request = MediaItem.RequestMetadata.Builder()
            .setExtras(Bundle().apply { putBoolean(EXTRA_SHUFFLE, shuffled) })
            .build()
        b.shuffleModeEnabled = shuffled
        b.setMediaItem(MediaItem.Builder().setMediaId(containerId).setRequestMetadata(request).build())
        b.prepare()
        b.play()
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

    fun clearQueue() = browser?.run {
        stop()
        clearMediaItems()
    }
}
