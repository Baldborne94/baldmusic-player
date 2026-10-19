@file:OptIn(UnstableApi::class)

package io.github.baldborne94.baldmusic.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
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
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.MediaItemsWithStartPosition
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import io.github.baldborne94.baldmusic.BaldMusicApp
import io.github.baldborne94.baldmusic.app
import io.github.baldborne94.baldmusic.core.library.ContainerRef
import io.github.baldborne94.baldmusic.core.library.Crossfade
import io.github.baldborne94.baldmusic.core.library.PersonalSource
import io.github.baldborne94.baldmusic.core.library.LibraryNode
import io.github.baldborne94.baldmusic.core.library.Loudness
import io.github.baldborne94.baldmusic.core.library.MediaId
import io.github.baldborne94.baldmusic.core.library.QueueSnapshot
import io.github.baldborne94.baldmusic.core.library.VoiceQuery
import io.github.baldborne94.baldmusic.core.library.RemoteSource
import io.github.baldborne94.baldmusic.ui.MainActivity
import io.github.baldborne94.baldmusic.widget.PlayerWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
    private var listenJob: Job? = null
    private var sleepJob: Job? = null

    /** Il timer di spegnimento abbassa piano il volume: si moltiplica per quello del ReplayGain. */
    private var fade = 1f

    // Dissolvenza incrociata. Poco prima della fine, il brano in corso si prepara su un
    // secondo ExoPlayer fermo sul punto in cui partirà la sfumatura ([outgoing]); lì parte
    // lui e si abbassa ([fadingOut]), mentre il player vero (quello della sessione, della
    // notifica e di Android Auto) passa al brano dopo e sale ([crossIn]).
    private lateinit var mediaSources: DefaultMediaSourceFactory
    private val musicAttributes = AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build()
    private var outgoing: ExoPlayer? = null
    private var outgoingFor = C.INDEX_UNSET
    private var fadingOut: ExoPlayer? = null
    private var crossJob: Job? = null
    private var crossIn = 1f
    private var crossSeek = false

    override fun onCreate() {
        super.onCreate()
        // OkHttp anche per lo streaming: stessi timeout e stessa connessione del resto dell'app.
        val dataSources = DefaultDataSource.Factory(this, OkHttpDataSource.Factory(app.http))
        mediaSources = DefaultMediaSourceFactory(dataSources)
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSources)
            .setAudioAttributes(musicAttributes, /* handleAudioFocus = */ true)
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

        // Mi piace, playlist, cronologia: Android Auto rilegge "La tua musica" e il cuore si aggiorna.
        scope.launch {
            app.userData.state.drop(1).collect {
                listOf(PersonalSource.KEY, "${PersonalSource.KEY}/${PersonalSource.LIKED}", "${PersonalSource.KEY}/${PersonalSource.PLAYLISTS}",
                    "${PersonalSource.KEY}/${PersonalSource.COLLECTIONS}",
                    "${PersonalSource.KEY}/${PersonalSource.RECENT}", "${PersonalSource.KEY}/${PersonalSource.TOP}").forEach { id ->
                    session.notifyChildrenChanged(id, Int.MAX_VALUE, null)
                }
                updateLikeButton()
            }
        }
        scope.launch { app.sleepTimer.collect { scheduleSleep(it) } }
        scope.launch { app.settings.replayGain.collect { applyVolume() } }
        scope.launch {
            while (true) {
                delay(200)
                watchCrossfade()
            }
        }

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
        // Subito, non in sottofondo: un attimo dopo il servizio non c'è più.
        saveLast(now = true)
        endCrossfade()
        dropOutgoing()
        scope.cancel()
        session.release()
        player.release()
        super.onDestroy()
    }

    private val queueFile by lazy { java.io.File(filesDir, "coda.json") }
    private var queueSave: Job? = null

    /**
     * Salva l'ultimo brano e tutta la coda (ordine, punto, casuale, ripeti): alla
     * riaccensione dell'auto, o dalla notifica dopo un riavvio, riparte tutto uguale.
     */
    private fun saveLast(now: Boolean = false) {
        val item = player.currentMediaItem ?: return
        app.settings.saveLast(item.mediaId, player.currentPosition)
        val snapshot = QueueSnapshot.of(
            ids = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId },
            index = player.currentMediaItemIndex,
            positionMs = player.currentPosition,
            shuffle = player.shuffleModeEnabled,
            repeat = player.repeatMode,
        ) ?: return
        queueSave?.cancel()
        if (now) writeQueue(snapshot) else queueSave = scope.launch(Dispatchers.IO) { writeQueue(snapshot) }
    }

    private fun writeQueue(snapshot: QueueSnapshot) {
        runCatching {
            val tmp = java.io.File(queueFile.parentFile, "${queueFile.name}.tmp")
            tmp.writeText(snapshot.toJson())
            tmp.renameTo(queueFile)
        }
    }

    /** La coda salvata, coi brani che si ritrovano ancora; null se non c'è niente da riprendere. */
    private suspend fun savedQueue(): MediaItemsWithStartPosition? {
        val snapshot = QueueSnapshot.fromJson(kotlinx.coroutines.withContext(Dispatchers.IO) { runCatching { queueFile.readText() }.getOrNull() }) ?: return null
        // Pochi alla volta: i brani del server chiedono ognuno una chiamata.
        val found = snapshot.ids.chunked(20).flatMap { chunk ->
            coroutineScope { chunk.map { id -> async { runCatching { tree.resolve(id) }.getOrNull() } }.awaitAll() }
        }.filterNotNull()
        if (found.isEmpty()) return null
        val index = snapshot.indexIn(found.map { it.mediaId })
        val position = if (found[index].mediaId == snapshot.currentId) snapshot.positionMs else 0L
        player.shuffleModeEnabled = snapshot.shuffle
        player.repeatMode = snapshot.repeat
        return MediaItemsWithStartPosition(found.toItems(), index, position)
    }

    private inner class PlayerEvents : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // Il passaggio voluto dalla dissolvenza va avanti; qualunque altro la interrompe.
            if (crossSeek) {
                crossSeek = false
            } else {
                endCrossfade()
                dropOutgoing()
            }
            applyVolume()
            saveLast()
            startScrobble(mediaItem)
            startListen(mediaItem)
            updateLikeButton()
            // Timer "a fine brano": il brano è finito da solo, ci si ferma qui.
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && app.sleepTimer.value == BaldMusicApp.SLEEP_END_OF_TRACK) {
                player.pause()
                app.sleepTimer.value = null
            }
        }

        override fun onEvents(player: Player, events: Player.Events) {
            if (events.containsAny(Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_IS_PLAYING_CHANGED, Player.EVENT_MEDIA_METADATA_CHANGED)) {
                updateWidget()
            }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            // Pausa durante la sfumatura: si ferma anche il brano che stava finendo.
            if (!playWhenReady) {
                endCrossfade()
                dropOutgoing()
            }
        }

        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            // Si è spostato il punto del brano: il brano preparato per sfumare non vale più.
            if (reason == Player.DISCONTINUITY_REASON_SEEK && !crossSeek) dropOutgoing()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isPlaying) saveLast()
        }

        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
            // Brani aggiunti, tolti o spostati nella coda.
            if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) saveLast()
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            updateLikeButton()
            saveLast()
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            updateLikeButton()
            saveLast()
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

    /**
     * Un ascolto conta dopo 20 secondi: chi salta un brano non lo vuole tra i
     * "più ascoltati". Il contenitore (album, playlist…) va negli "ascoltati di recente".
     */
    private fun startListen(item: MediaItem?) {
        listenJob?.cancel()
        if (item == null) return
        val id = MediaId.parse(item.mediaId) ?: return
        if (!id.isTrack) return
        listenJob = scope.launch {
            var played = 0L
            while (played < 20_000) {
                delay(1000)
                if (player.currentMediaItem?.mediaId != item.mediaId) return@launch
                if (player.isPlaying) played += 1000
            }
            app.userData.recordPlay(item.toTrackRef(), containerOf(item, id.parent))
        }
    }

    private suspend fun containerOf(item: MediaItem, parent: MediaId?): ContainerRef? {
        parent ?: return null
        if (parent.source == PersonalSource.KEY && parent.kind == PersonalSource.LIKED) {
            return ContainerRef(parent.toString(), "Brani che ti piacciono", "Playlist", null, LibraryNode.Kind.PLAYLIST.name)
        }
        val node = runCatching { tree.node(parent.toString()) }.getOrNull()
        if (node != null && node.title.isNotBlank() && node.kind != LibraryNode.Kind.SECTION) {
            return ContainerRef(parent.toString(), node.title, node.subtitle, node.artwork, node.kind.name)
        }
        // Un album non ancora elencato: titolo e copertina li ha già il brano.
        if (parent.kind == "album") {
            val md = item.mediaMetadata
            val title = md.albumTitle?.toString() ?: return null
            return ContainerRef(parent.toString(), title, md.artist?.toString(), item.toTrackRef().artwork, LibraryNode.Kind.ALBUM.name)
        }
        return null
    }

    /**
     * Volume del brano in corso: ReplayGain (si abbassa e basta, mai oltre il picco) per
     * la sfumatura del timer. Il volume del telefono resta all'utente.
     */
    private fun applyVolume() {
        val rg = Loudness.volume(player.currentMediaItem?.replayGain, app.settings.replayGain.value)
        player.volume = (rg * fade * crossIn).coerceIn(0f, 1f)
    }

    /** Ogni 200 ms: è ora di preparare il brano che finisce, o di cominciare a sfumare? */
    private fun watchCrossfade() {
        if (crossJob != null) return
        val seconds = app.settings.crossfade.value
        val current = player.currentMediaItem
        val duration = player.duration
        val next = player.nextMediaItemIndex
        val ok = seconds > 0 && player.playWhenReady && current != null && duration != C.TIME_UNSET && next != C.INDEX_UNSET &&
            player.repeatMode != Player.REPEAT_MODE_ONE && app.sleepTimer.value != BaldMusicApp.SLEEP_END_OF_TRACK &&
            Crossfade.applies(seconds, duration, sameAlbum = current.albumId != null && current.albumId == player.getMediaItemAt(next).albumId)
        if (!ok) {
            dropOutgoing()
            return
        }
        val start = duration - seconds * 1000L
        val position = player.currentPosition
        val index = player.currentMediaItemIndex
        when {
            position in (start - 3_000) until start && outgoingFor != index -> prepareOutgoing(current!!, start, index)
            position >= start && outgoingFor == index -> startCrossfade(duration - position)
        }
    }

    /** Il brano in corso, su un secondo player fermo sul punto in cui comincerà a sfumare. */
    private fun prepareOutgoing(item: MediaItem, startMs: Long, index: Int) {
        dropOutgoing()
        outgoing = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSources)
            // L'audio focus resta al player vero: questo suona solo per pochi secondi.
            .setAudioAttributes(musicAttributes, /* handleAudioFocus = */ false)
            .build()
            .apply {
                setMediaItem(item, startMs)
                volume = player.volume
                prepare()
            }
        outgoingFor = index
    }

    private fun dropOutgoing() {
        outgoing?.release()
        outgoing = null
        outgoingFor = C.INDEX_UNSET
    }

    /** Il brano che finisce continua sul secondo player e si abbassa; il player vero passa al brano dopo e sale. */
    private fun startCrossfade(lengthMs: Long) {
        val out = outgoing ?: return
        outgoing = null
        outgoingFor = C.INDEX_UNSET
        if (out.playbackState != Player.STATE_READY) {
            // Non è pronto in tempo: passaggio normale, senza buchi.
            out.release()
            return
        }
        val outVolume = player.volume
        out.volume = outVolume
        out.play()
        fadingOut = out
        crossIn = 0f
        crossSeek = true
        player.seekToNextMediaItem()
        applyVolume()
        val total = lengthMs.coerceAtLeast(500L).toFloat()
        crossJob = scope.launch {
            val t0 = SystemClock.elapsedRealtime()
            while (true) {
                val t = (SystemClock.elapsedRealtime() - t0) / total
                if (t >= 1f) break
                val (o, i) = Crossfade.gains(t)
                out.volume = outVolume * o
                crossIn = i
                applyVolume()
                delay(50)
            }
            endCrossfade()
        }
    }

    /** Fine (o interruzione) della sfumatura: il brano vecchio si ferma, quello nuovo a volume pieno. */
    private fun endCrossfade() {
        val job = crossJob
        crossJob = null
        fadingOut?.release()
        fadingOut = null
        crossIn = 1f
        applyVolume()
        // Chiamata anche dalla sfumatura stessa, alla fine: annullarla lì non fa danni.
        job?.cancel()
    }

    /** Timer di spegnimento: abbassa piano il volume nell'ultimo tratto, poi pausa. */
    private fun scheduleSleep(end: Long?) {
        sleepJob?.cancel()
        fade = 1f
        applyVolume()
        if (end == null || end == BaldMusicApp.SLEEP_END_OF_TRACK) return
        sleepJob = scope.launch {
            val fadeMs = 10_000L
            delay((end - System.currentTimeMillis() - fadeMs).coerceAtLeast(0))
            while (true) {
                val left = end - System.currentTimeMillis()
                if (left <= 0) break
                fade = (left.toFloat() / fadeMs).coerceIn(0f, 1f)
                applyVolume()
                delay(250)
            }
            player.pause()
            fade = 1f
            applyVolume()
            app.sleepTimer.value = null
        }
    }

    private var widgetJob: Job? = null
    private var widgetArt: Pair<android.net.Uri?, android.graphics.Bitmap?> = null to null

    /** Il widget segue il brano: la copertina si carica una volta per brano. */
    private fun updateWidget() {
        val item = player.currentMediaItem
        val md = item?.mediaMetadata
        val playing = player.isPlaying
        widgetJob?.cancel()
        widgetJob = scope.launch {
            val uri = md?.artworkUri
            if (widgetArt.first != uri) {
                widgetArt = uri to kotlinx.coroutines.withContext(Dispatchers.IO) { PlayerWidget.loadArt(this@PlaybackService, uri) }
            }
            PlayerWidget.update(
                this@PlaybackService,
                PlayerWidget.State(md?.title?.toString(), md?.artist?.toString(), playing, widgetArt.second),
            )
        }
    }

    /** Cuore, casuale e ripeti accanto ai comandi: nella notifica e in Android Auto. */
    private fun updateLikeButton() {
        val item = player.currentMediaItem
        val liked = item != null && app.userData.isLiked(item.mediaId)
        val heart = CommandButton.Builder(if (liked) CommandButton.ICON_HEART_FILLED else CommandButton.ICON_HEART_UNFILLED)
            .setDisplayName(if (liked) "Togli da Mi piace" else "Mi piace")
            .setSessionCommand(SessionCommand(CMD_TOGGLE_LIKE, Bundle.EMPTY))
            .build()
        val shuffle = CommandButton.Builder(if (player.shuffleModeEnabled) CommandButton.ICON_SHUFFLE_ON else CommandButton.ICON_SHUFFLE_OFF)
            .setDisplayName(if (player.shuffleModeEnabled) "Casuale attivo" else "Casuale")
            .setSessionCommand(SessionCommand(CMD_SHUFFLE, Bundle.EMPTY))
            .build()
        val repeat = CommandButton.Builder(
            when (player.repeatMode) {
                Player.REPEAT_MODE_ONE -> CommandButton.ICON_REPEAT_ONE
                Player.REPEAT_MODE_ALL -> CommandButton.ICON_REPEAT_ALL
                else -> CommandButton.ICON_REPEAT_OFF
            },
        )
            .setDisplayName(
                when (player.repeatMode) {
                    Player.REPEAT_MODE_ONE -> "Ripeti il brano"
                    Player.REPEAT_MODE_ALL -> "Ripeti tutto"
                    else -> "Ripeti"
                },
            )
            .setSessionCommand(SessionCommand(CMD_REPEAT, Bundle.EMPTY))
            .build()
        session.setCustomLayout(if (item == null) ImmutableList.of() else ImmutableList.of(heart, shuffle, repeat))
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

        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
                .add(SessionCommand(CMD_TOGGLE_LIKE, Bundle.EMPTY))
                .add(SessionCommand(CMD_SHUFFLE, Bundle.EMPTY))
                .add(SessionCommand(CMD_REPEAT, Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction == CMD_TOGGLE_LIKE) {
                player.currentMediaItem?.let { app.userData.toggleLike(it.toTrackRef()) }
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            if (customCommand.customAction == CMD_SHUFFLE) {
                player.shuffleModeEnabled = !player.shuffleModeEnabled
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            if (customCommand.customAction == CMD_REPEAT) {
                // Spento → tutta la coda → il brano → spento.
                player.repeatMode = when (player.repeatMode) {
                    Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                    Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                    else -> Player.REPEAT_MODE_OFF
                }
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            return super.onCustomCommand(session, controller, customCommand, args)
        }

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

        /**
         * Ripresa dal sistema o da Android Auto: la coda salvata com'era; se non c'è più,
         * l'ultimo brano nel suo album o raccolta, dove si era fermato.
         */
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaItemsWithStartPosition> = scope.future {
            savedQueue()?.let { return@future it }
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
        val (words, _) = VoiceQuery.parse(query)
        val results = tree.search(words)
        // Una raccolta, un artista o un album detti per nome si suonano per intero.
        VoiceQuery.pick(query, results)?.let { return tree.playableIn(it.mediaId).toItems() }
        val tracks = results.filter { it.playable }
        if (tracks.isNotEmpty()) return tracks.mapNotNull { tree.resolve(it) }.toItems()
        val container = results.firstOrNull { it.kind == LibraryNode.Kind.PLAYLIST || it.kind == LibraryNode.Kind.ARTIST || it.kind == LibraryNode.Kind.ALBUM }
        return container?.let { tree.playableIn(it.mediaId).toItems() }.orEmpty()
    }

    companion object {
        private const val RECENT_ROOT = "recent"
        const val CMD_TOGGLE_LIKE = "io.github.baldborne94.baldmusic.TOGGLE_LIKE"
        const val CMD_SHUFFLE = "io.github.baldborne94.baldmusic.SHUFFLE"
        const val CMD_REPEAT = "io.github.baldborne94.baldmusic.REPEAT"
    }
}
