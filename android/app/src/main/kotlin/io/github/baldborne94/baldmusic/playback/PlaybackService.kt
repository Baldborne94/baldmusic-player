@file:OptIn(UnstableApi::class)

package io.github.baldborne94.baldmusic.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.MediaItemsWithStartPosition
import androidx.media3.session.SessionError
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import io.github.baldborne94.baldmusic.app
import io.github.baldborne94.baldmusic.core.library.LibraryNode
import io.github.baldborne94.baldmusic.core.library.MediaId
import io.github.baldborne94.baldmusic.core.library.RemoteSource
import io.github.baldborne94.baldmusic.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Riproduce la musica ed espone la libreria. La stessa sessione serve:
 * - l'app sul telefono (che si collega con un MediaBrowser),
 * - la notifica e la schermata di blocco,
 * - Android Auto, che sfoglia l'albero e manda i comandi play/pausa/avanti.
 */
class PlaybackService : MediaLibraryService() {

    private lateinit var player: ExoPlayer
    private lateinit var session: MediaLibrarySession
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val tree get() = app.tree
    private val searches = ConcurrentHashMap<String, List<LibraryNode>>()
    private var scrobbleJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        // OkHttp anche per lo streaming: stessi timeout e stessa connessione del resto dell'app.
        val dataSources = DefaultDataSource.Factory(this, OkHttpDataSource.Factory(app.http))
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSources))
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                /* handleAudioFocus = */ true,
            )
            // Si mette in pausa se si staccano le cuffie o il Bluetooth dell'auto.
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        player.addListener(PlayerEvents())

        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaLibrarySession.Builder(this, player, LibraryCallback())
            .setSessionActivity(openApp)
            .build()

        // Server collegato o scollegato, permesso concesso: Android Auto rilegge l'albero.
        scope.launch {
            app.settings.changes.drop(1).collect {
                app.local.invalidate()
                tree.invalidate()
                session.notifyChildrenChanged(MediaId.ROOT, Int.MAX_VALUE, null)
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Chiudendo l'app dalle recenti: se la musica è ferma, il servizio se ne va.
        if (!player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        saveLast()
        scope.cancel()
        session.release()
        player.release()
        super.onDestroy()
    }

    private fun saveLast() {
        val item = player.currentMediaItem ?: return
        app.settings.saveLast(item.mediaId, player.currentPosition)
    }

    private inner class PlayerEvents : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            saveLast()
            startScrobble(mediaItem)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isPlaying) saveLast()
        }
    }

    /**
     * Segnala a Navidrome cosa si sta ascoltando e, dopo metà brano (o 4 minuti),
     * registra l'ascolto: così "più ascoltati" e "ascoltati di recente" restano veri
     * anche per quello che si ascolta in macchina.
     */
    private fun startScrobble(item: MediaItem?) {
        scrobbleJob?.cancel()
        if (item == null) return
        val id = MediaId.parse(item.mediaId) ?: return
        if (id.source != RemoteSource.KEY || !id.isTrack) return
        val songId = id.id ?: return
        val client = app.subsonic() ?: return
        scrobbleJob = scope.launch {
            runCatching { client.scrobble(songId, submission = false) }
            while (isActive) {
                delay(10_000)
                val duration = player.duration
                if (player.currentMediaItem?.mediaId != item.mediaId) return@launch
                if (duration > 0 && player.currentPosition >= minOf(duration / 2, 240_000L)) {
                    runCatching { client.scrobble(songId, submission = true) }
                    return@launch
                }
            }
        }
    }

    private fun rootItem(id: String) = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle("BaldMusic")
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                .build(),
        )
        .build()

    private fun List<LibraryNode>.toItems() = map { it.toMediaItem(this@PlaybackService) }

    private fun <T> List<T>.page(page: Int, pageSize: Int): List<T> {
        if (pageSize <= 0 || page < 0) return this
        val from = page.toLong() * pageSize
        if (from >= size) return emptyList()
        return subList(from.toInt(), minOf(size, (from + pageSize).toInt().coerceAtLeast(0)))
    }

    /**
     * Un brano arrivato da un controller (app o Auto) non ha l'indirizzo da
     * riprodurre: Media3 lo toglie per sicurezza. Qui lo si ricostruisce.
     */
    private suspend fun resolve(item: MediaItem): List<MediaItem> {
        val id = MediaId.parse(item.mediaId) ?: return emptyList()
        if (!id.isTrack) return tree.playableIn(item.mediaId).toItems()
        return listOfNotNull(tree.resolve(item.mediaId)?.toMediaItem(this))
    }

    private inner class LibraryCallback : MediaLibrarySession.Callback {

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val extras = Bundle().apply {
                // Android Auto: abilita la lente di ricerca.
                putBoolean("android.media.browse.SEARCH_SUPPORTED", true)
            }
            val rootId = if (params?.isRecent == true) RECENT_ROOT else MediaId.ROOT
            return Futures.immediateFuture(LibraryResult.ofItem(rootItem(rootId), LibraryParams.Builder().setExtras(extras).build()))
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
            try {
                val nodes = if (parentId == RECENT_ROOT) {
                    // La "ripresa" del sistema: l'ultimo brano ascoltato.
                    listOfNotNull(app.settings.last()?.first?.let { tree.resolve(it) })
                } else {
                    tree.children(parentId)
                }
                LibraryResult.ofItemList(nodes.page(page, pageSize).toItems(), params)
            } catch (e: Exception) {
                LibraryResult.ofError(SessionError.ERROR_IO)
            }
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> = scope.future {
            val node = runCatching { tree.node(mediaId) }.getOrNull()
            if (node == null) LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
            else LibraryResult.ofItem(node.toMediaItem(this@PlaybackService), null)
        }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> {
            scope.launch {
                val results = runCatching { tree.search(query) }.getOrDefault(emptyList())
                searches[query] = results
                session.notifySearchResultChanged(browser, query, results.size, params)
            }
            return Futures.immediateFuture(LibraryResult.ofVoid())
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
            val results = searches[query] ?: runCatching { tree.search(query) }.getOrDefault(emptyList())
            LibraryResult.ofItemList(results.page(page, pageSize).toItems(), params)
        }

        /**
         * Cosa suonare quando si sceglie qualcosa:
         * - un brano → il suo album/playlist da quel brano in poi;
         * - un album, un artista, una playlist → tutti i brani;
         * - una richiesta vocale ("Ok Google, metti Lucio Dalla su BaldMusic") → la ricerca.
         */
        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaItemsWithStartPosition> = scope.future {
            val single = mediaItems.singleOrNull()
            if (single == null) {
                // Una lista intera (es. la coda ripristinata): ogni brano col suo indirizzo.
                val resolved = mediaItems.flatMap { resolve(it) }
                return@future MediaItemsWithStartPosition(resolved, startIndex.coerceIn(C.INDEX_UNSET, resolved.size - 1), startPositionMs)
            }

            val query = single.requestMetadata.searchQuery
            if (single.mediaId.isEmpty() && query != null) {
                return@future MediaItemsWithStartPosition(voiceSearch(query), 0, C.TIME_UNSET)
            }

            val (queue, index) = tree.queueFor(single.mediaId)
            val shuffle = single.requestMetadata.extras?.getBoolean(EXTRA_SHUFFLE) == true
            val start = if (shuffle && queue.isNotEmpty()) queue.indices.random() else index
            MediaItemsWithStartPosition(queue.toItems(), start, startPositionMs)
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = scope.future {
            mediaItems.flatMap { resolve(it) }.toMutableList()
        }

        /** Ripresa dal sistema o da Android Auto: l'ultimo brano, nel suo album, dove si era fermato. */
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaItemsWithStartPosition> = scope.future {
            val (mediaId, position) = app.settings.last() ?: throw UnsupportedOperationException("Niente da riprendere")
            val (queue, index) = tree.queueFor(mediaId)
            if (queue.isEmpty()) throw UnsupportedOperationException("Brano non più disponibile")
            MediaItemsWithStartPosition(queue.toItems(), index, position)
        }
    }

    private suspend fun voiceSearch(query: String): List<MediaItem> {
        if (query.isBlank()) {
            // "Metti un po' di musica": mix casuale dalla prima sorgente disponibile.
            val first = tree.rootNodes().firstOrNull() ?: return emptyList()
            val mix = tree.children(first.mediaId).firstOrNull { it.title.startsWith("Mix") } ?: return emptyList()
            return tree.playableIn(mix.mediaId).toItems()
        }
        val results = tree.search(query)
        val tracks = results.filter { it.playable }
        // Se la richiesta corrisponde a un artista o un album, si suona quello per intero.
        val container = results.firstOrNull { it.kind == LibraryNode.Kind.ARTIST || it.kind == LibraryNode.Kind.ALBUM }
        if (container != null && container.title.equals(query.trim(), ignoreCase = true)) {
            return tree.playableIn(container.mediaId).toItems()
        }
        if (tracks.isNotEmpty()) return tracks.mapNotNull { tree.resolve(it) }.toItems()
        return container?.let { tree.playableIn(it.mediaId).toItems() }.orEmpty()
    }

    companion object {
        private const val RECENT_ROOT = "recent"
    }
}
