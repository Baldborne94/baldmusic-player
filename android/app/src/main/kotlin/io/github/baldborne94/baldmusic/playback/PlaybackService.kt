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
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import io.github.baldborne94.baldmusic.BaldMusicApp
import io.github.baldborne94.baldmusic.app
import io.github.baldborne94.baldmusic.core.library.Autoplay
import io.github.baldborne94.baldmusic.core.library.ContainerRef
import io.github.baldborne94.baldmusic.core.library.PersonalSource
import io.github.baldborne94.baldmusic.core.library.LibraryNode
import io.github.baldborne94.baldmusic.core.library.ListeningSession
import io.github.baldborne94.baldmusic.core.library.QueueOrder
import io.github.baldborne94.baldmusic.core.library.Loudness
import io.github.baldborne94.baldmusic.core.library.MediaId
import io.github.baldborne94.baldmusic.core.library.QueueSnapshot
import io.github.baldborne94.baldmusic.core.library.RecentSearches
import io.github.baldborne94.baldmusic.core.library.RemoteSource
import io.github.baldborne94.baldmusic.core.library.SessionControls
import io.github.baldborne94.baldmusic.core.library.SleepTimer
import io.github.baldborne94.baldmusic.core.library.UserLibrary
import io.github.baldborne94.baldmusic.core.podcast.PodcastSource
import io.github.baldborne94.baldmusic.ui.MainActivity
import io.github.baldborne94.baldmusic.widget.PlayerWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
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
    /** Ogni ricerca dell'app o di Auto chiede di cercare e poi i risultati: si cerca una volta. */
    private val searches = RecentSearches<Deferred<List<LibraryNode>>>()
    private var scrobbleJob: Job? = null
    private var listenJob: Job? = null
    private var sleepJob: Job? = null

    /** Il timer di spegnimento abbassa piano il volume: si moltiplica per quello del ReplayGain. */
    private var fade = 1f

    private val musicAttributes = AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build()
    /** Dissolvenza incrociata tra i brani ([CrossfadeController]). */
    private lateinit var crossfade: CrossfadeController
    /** Le richieste a voce e le puntate chieste per intero. */
    private val voice by lazy { VoiceRequests(this) }

    /** Coda appena impostata: col casuale deve partire dal brano scelto e passare da tutti gli altri. */
    private var shuffleFromCurrent = false
    /** L'ordine del casuale della coda ripresa ([QueueSnapshot.orderIn]), da rimettere appena arriva. */
    private var restoredOrder: List<Int>? = null
    /** La coda di prima, per riconoscere i brani appena aggiunti ([QueueOrder.insertion]). */
    private var knownQueue: List<String> = emptyList()
    /** Cosa hai già sentito o saltato nella raccolta che stai ascoltando ([ListeningSession]). */
    private var listening = ListeningSession()

    override fun onCreate() {
        super.onCreate()
        // OkHttp anche per lo streaming: stessi timeout e stessa connessione del resto dell'app.
        val dataSources = DefaultDataSource.Factory(this, OkHttpDataSource.Factory(app.http))
        val mediaSources = DefaultMediaSourceFactory(dataSources)
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSources)
            .setAudioAttributes(musicAttributes, /* handleAudioFocus = */ true)
            // Si mette in pausa se si staccano le cuffie o il Bluetooth dell'auto.
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        crossfade = CrossfadeController(
            this, player, mediaSources, musicAttributes, scope,
            seconds = { app.settings.crossfade.value },
            endOfTrackTimer = { app.sleepTimer.value == BaldMusicApp.SLEEP_END_OF_TRACK },
            applyVolume = ::applyVolume,
        )
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
            var previous: UserLibrary? = null
            app.userData.state.collect { lib ->
                val before = previous.also { previous = lib } ?: return@collect
                // Un cuore o una playlist cambiano i risultati: le ricerche si rifanno.
                if (lib.liked != before.liked || lib.playlists != before.playlists) searches.clear()
                // Solo le pagine il cui contenuto è cambiato: un ascolto non fa rileggere i preferiti.
                PersonalSource.PAGES.filter { PersonalSource.shownBy(before, it) != PersonalSource.shownBy(lib, it) }
                    .forEach { id -> session.notifyChildrenChanged(id, Int.MAX_VALUE, null) }
                updateLikeButton()
            }
        }
        scope.launch {
            app.sleepTimer.collect {
                scheduleSleep(it)
                updateLikeButton()
                continueAfterQueue()
            }
        }
        // Il tasto del timer dice quanto manca: si aggiorna ogni mezzo minuto mentre il timer corre.
        scope.launch {
            while (true) {
                delay(30_000)
                val end = app.sleepTimer.value
                if (end != null && end != BaldMusicApp.SLEEP_END_OF_TRACK) updateLikeButton()
            }
        }
        scope.launch { app.settings.replayGain.collect { applyVolume() } }
        scope.launch { app.settings.crossfade.collect { crossfade.update() } }

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
        crossfade.stop()
        scope.cancel()
        session.release()
        player.release()
        super.onDestroy()
    }

    private val queueStore by lazy { QueueStore(java.io.File(filesDir, "coda.json"), scope) }

    /** Dove sei arrivato nella puntata in corso, se è una puntata: la prossima volta riparte da lì. */
    private fun savePodcastProgress() {
        val item = player.currentMediaItem ?: return
        if (!item.mediaId.startsWith("${PodcastSource.KEY}/")) return
        app.podcasts.record(item.mediaId, player.currentPosition, player.duration.takeIf { it != C.TIME_UNSET })
    }

    /**
     * Salva l'ultimo brano e tutta la coda (ordine, punto, casuale, ripeti): alla
     * riaccensione dell'auto, o dalla notifica dopo un riavvio, riparte tutto uguale.
     */
    private fun saveLast(now: Boolean = false) {
        val item = player.currentMediaItem ?: return
        app.settings.saveLast(item.mediaId, player.currentPosition)
        savePodcastProgress()
        val snapshot = QueueSnapshot.of(
            // Letta un brano alla volta: si leggono solo i brani che si tengono.
            ids = object : AbstractList<String>() {
                override val size = player.mediaItemCount
                override fun get(index: Int) = player.getMediaItemAt(index).mediaId
            },
            index = player.currentMediaItemIndex,
            positionMs = player.currentPosition,
            shuffle = player.shuffleModeEnabled,
            repeat = player.repeatMode,
            order = if (player.shuffleModeEnabled) shuffleOrderNow() else null,
        ) ?: return
        queueStore.save(snapshot, now)
    }

    /** La coda salvata, coi brani che si ritrovano ancora; null se non c'è niente da riprendere. */
    private suspend fun savedQueue(): MediaItemsWithStartPosition? {
        val snapshot = queueStore.read() ?: return null
        // Server spento o fuori rete (Tailscale attivo, PC spento): ogni brano aspetterebbe il suo
        // timeout e la ripresa in auto resterebbe ferma per minuti. Si prova una volta sola.
        val remote = "${RemoteSource.KEY}/"
        val reachable = snapshot.ids.none { it.startsWith(remote) } ||
            kotlinx.coroutines.withTimeoutOrNull(3_000) { runCatching { app.subsonic()?.ping() }.isSuccess } == true
        val ids = if (reachable) snapshot.ids else snapshot.ids.filterNot { it.startsWith(remote) }
        // Pochi alla volta: i brani del server chiedono ognuno una chiamata.
        val found = ids.chunked(20).flatMap { chunk ->
            coroutineScope { chunk.map { id -> async { runCatching { tree.resolve(id) }.getOrNull() } }.awaitAll() }
        }.filterNotNull()
        if (found.isEmpty()) return null
        val index = snapshot.indexIn(found.map { it.mediaId })
        val position = if (found[index].mediaId == snapshot.currentId) snapshot.positionMs else 0L
        player.shuffleModeEnabled = snapshot.shuffle
        player.repeatMode = snapshot.repeat
        // Lo stesso ordine del casuale di prima: chiusa e riaperta l'app, non si rimescola.
        restoredOrder = if (snapshot.shuffle) snapshot.orderIn(found.map { it.mediaId }) else null
        shuffleFromCurrent = true
        return MediaItemsWithStartPosition(found.toItems(), index, position)
    }

    private inner class PlayerEvents : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // Il passaggio voluto dalla dissolvenza va avanti; qualunque altro la interrompe.
            if (!crossfade.consumeSeek()) crossfade.stop()
            // Una puntata partita da sola dopo un'altra riprende da dove eri arrivato.
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && mediaItem != null) {
                app.podcasts.resumeAt(mediaItem.mediaId).takeIf { it > 0 }?.let { player.seekTo(it) }
            }
            applyVolume()
            saveLast()
            startScrobble(mediaItem)
            startListen(mediaItem)
            updateLikeButton()
            // Timer "a fine brano": il brano è finito da solo (anche se si ripete), ci si ferma qui.
            val ended = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT
            if (ended && app.sleepTimer.value == BaldMusicApp.SLEEP_END_OF_TRACK) {
                player.pause()
                app.sleepTimer.value = null
            }
            continueAfterQueue()
        }

        override fun onEvents(player: Player, events: Player.Events) {
            if (events.containsAny(Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_IS_PLAYING_CHANGED, Player.EVENT_MEDIA_METADATA_CHANGED)) {
                updateWidget()
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            // Finita la coda col timer "a fine brano": è già fermo, il timer non deve restare armato.
            if (playbackState == Player.STATE_ENDED && app.sleepTimer.value == BaldMusicApp.SLEEP_END_OF_TRACK) app.sleepTimer.value = null
        }

        override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) {
            // Chiamata, Assistente, navigatore: il player si sospende senza andare in pausa, ma il
            // brano che sfuma (senza audio focus) continuerebbe sopra. Si ferma anche lui.
            if (playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE) crossfade.stop()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            // Pausa durante la sfumatura: si ferma anche il brano che stava finendo.
            if (!playWhenReady) crossfade.stop()
            // In pausa il controllo della dissolvenza si ferma; riparte con la musica.
            crossfade.update()
        }

        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            // Si è spostato il punto del brano: il brano preparato per sfumare non vale più.
            if (reason == Player.DISCONTINUITY_REASON_SEEK && !crossfade.seeking) crossfade.drop()
            noteLeft(oldPosition, newPosition, reason)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isPlaying) saveLast()
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            // Un brano che non parte non chiude l'app, ma va nel resoconto di "Segnala un problema".
            val item = player.currentMediaItem
            app.problems.note(
                "Riproduzione: ${error.errorCodeName} ${error.message.orEmpty()} ${error.cause?.let { "(${it.javaClass.simpleName}: ${it.message})" }.orEmpty()} " +
                    "su «${item?.mediaMetadata?.title ?: "?"}» [${item?.mediaId ?: "?"}]",
            )
        }

        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
            // Brani aggiunti, tolti o spostati nella coda.
            if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) {
                val ids = queueIds()
                val inserted = QueueOrder.insertion(knownQueue, ids)
                knownQueue = ids
                if (shuffleFromCurrent) {
                    shuffleFromCurrent = false
                    val restored = restoredOrder?.takeIf { it.size == player.mediaItemCount }
                    restoredOrder = null
                    if (player.shuffleModeEnabled) {
                        if (restored != null) player.setShuffleOrder(androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder(restored.toIntArray(), System.nanoTime()))
                        else shuffleStartingHere()
                    }
                } else if (inserted != null && player.shuffleModeEnabled) {
                    // «Riproduci dopo» / «Aggiungi in coda» col casuale: dove li hai chiesti, non a caso.
                    val order = QueueOrder.shuffleAfterInsert(shuffleOrderNow(), inserted, player.currentMediaItemIndex)
                    player.setShuffleOrder(androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder(order.toIntArray(), System.nanoTime()))
                }
                saveLast()
                continueAfterQueue()
            }
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            if (shuffleModeEnabled) shuffleStartingHere() else scope.launch { keepHeardBehind() }
            updateLikeButton()
            saveLast()
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            updateLikeButton()
            saveLast()
            continueAfterQueue()
        }
    }

    /**
     * Segnala a Navidrome cosa si sta ascoltando e, quando non è più un salto
     * ([ListeningSession.countsAsPlay]), registra l'ascolto: così "più ascoltati" e "ascoltati
     * di recente" restano veri anche per quello che si ascolta in macchina.
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
                if (duration > 0 && ListeningSession.countsAsPlay(player.currentPosition, duration)) {
                    runCatching { client.scrobble(songId, submission = true) }
                    return@launch
                }
            }
        }
    }

    /**
     * Un ascolto conta quando il brano non è più un salto (tre quarti, o fino a 30 s dalla fine):
     * chi salta un brano non lo vuole tra i «più ascoltati» né nei mix. Il contenitore (album,
     * playlist…) va negli «ascoltati di recente» già dopo 20 secondi.
     */
    private fun startListen(item: MediaItem?) {
        listenJob?.cancel()
        if (item == null) return
        val id = MediaId.parse(item.mediaId) ?: return
        if (!id.isTrack) return
        if (id.source == PodcastSource.KEY) {
            // Una puntata non va tra i «più ascoltati» né nei mix: si ricorda solo dove sei arrivato.
            listenJob = scope.launch {
                var queued = false
                while (player.currentMediaItem?.mediaId == item.mediaId) {
                    delay(10_000)
                    if (player.isPlaying) savePodcastProgress()
                    // Poco prima della fine, la puntata dopo in coda: l'indirizzo dell'audio di
                    // Patreon è fresco (scade), e alla fine parte da sola.
                    val left = player.duration.takeIf { it != C.TIME_UNSET && it > 0 }?.let { it - player.currentPosition }
                    if (!queued && left != null && left <= NEXT_EPISODE_BEFORE_MS) {
                        queued = true
                        queueNextEpisode(item)
                    }
                }
            }
            return
        }
        listenJob = scope.launch {
            var played = 0L
            var container: ContainerRef? = null
            while (true) {
                delay(1000)
                if (player.currentMediaItem?.mediaId != item.mediaId) return@launch
                if (player.isPlaying) played += 1000
                // Dopo 20 s il contenitore va negli «ascoltati di recente»…
                if (played == RECENT_AFTER_MS) {
                    container = containerOf(item, id.parent)
                    app.userData.touchRecent(container)
                }
                // …ma l'ascolto conta solo quando non è più un salto ([ListeningSession.countsAsPlay]).
                val duration = item.mediaMetadata.durationMs ?: player.duration.takeIf { it > 0 }
                if (played >= RECENT_AFTER_MS && ListeningSession.countsAsPlay(played, duration)) break
            }
            app.userData.recordPlay(item.toTrackRef(), container ?: containerOf(item, id.parent))
        }
    }

    /**
     * «Puntata dopo puntata» ([PodcastSource.nextAfter]): con «Continua ad ascoltare» acceso,
     * senza ripeti né timer «a fine brano», e solo se dopo la puntata non c'è già altro in coda.
     */
    private suspend fun queueNextEpisode(item: MediaItem) {
        val needed = Autoplay.needed(
            enabled = app.settings.continueListening,
            hasNext = player.hasNextMediaItem(),
            repeatOff = player.repeatMode == Player.REPEAT_MODE_OFF,
            endOfTrackTimer = app.sleepTimer.value == BaldMusicApp.SLEEP_END_OF_TRACK,
        )
        if (!needed) return
        val next = runCatching { app.podcastSource.nextAfter(item.mediaId) }.getOrNull() ?: return
        if (player.currentMediaItem?.mediaId != item.mediaId || player.hasNextMediaItem()) return
        player.addMediaItem(next.toMediaItem(this))
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
        player.volume = (rg * fade * crossfade.crossIn).coerceIn(0f, 1f)
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

    /** I tasti accanto ai comandi, nella notifica e in Android Auto ([SessionControls.layout]). */
    private fun updateLikeButton() {
        val item = player.currentMediaItem
        session.setCustomLayout(
            SessionButtons.of(
                SessionControls.layout(item?.mediaId),
                liked = item != null && app.userData.isLiked(item.mediaId),
                shuffle = player.shuffleModeEnabled,
                repeatMode = player.repeatMode,
                sleepLabel = SleepTimer.label(app.sleepTimer.value, System.currentTimeMillis()),
            ),
        )
    }

    /** Indietro o avanti dentro il brano (le puntate): mai prima dell'inizio né oltre la fine. */
    private fun skipWithin(deltaMs: Long) {
        player.seekTo(SessionControls.skipTarget(player.currentPosition, player.duration.takeIf { it != C.TIME_UNSET }, deltaMs))
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

    private suspend fun searched(query: String): List<LibraryNode> =
        searches.getOrPut(query) { scope.async { runCatching { tree.search(query) }.getOrDefault(emptyList()) } }.await()

    private inner class LibraryCallback : MediaLibrarySession.Callback {

        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
                .add(SessionCommand(CMD_TOGGLE_LIKE, Bundle.EMPTY))
                .add(SessionCommand(CMD_SHUFFLE, Bundle.EMPTY))
                .add(SessionCommand(CMD_REPEAT, Bundle.EMPTY))
                .add(SessionCommand(CMD_SLEEP, Bundle.EMPTY))
                .add(SessionCommand(CMD_BACK, Bundle.EMPTY))
                .add(SessionCommand(CMD_FORWARD, Bundle.EMPTY))
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
                player.repeatMode = SessionControls.nextRepeat(player.repeatMode)
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            if (customCommand.customAction == CMD_SLEEP) {
                app.sleepTimer.value = SleepTimer.next(app.sleepTimer.value, System.currentTimeMillis())
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            if (customCommand.customAction == CMD_BACK || customCommand.customAction == CMD_FORWARD) {
                skipWithin(if (customCommand.customAction == CMD_BACK) -SessionControls.BACK_MS else SessionControls.FORWARD_MS)
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
                val results = searched(query)
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
            val results = searched(query)
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
                return@future voice.play(query)
            }
            // Un podcast intero o «Da ascoltare»: una puntata sola, da dove eri (non tutto l'archivio).
            MediaId.parse(single.mediaId)?.let { app.podcastSource.toPlay(it) }?.let { return@future voice.episode(it) }

            // Dall'elenco di tutti i brani: dopo quello scelto la sua radio (mood e stile), non l'alfabeto.
            val radio = QueueOrder.radioInstead(single.mediaId)?.let { r -> tree.playableIn(r).takeIf { it.isNotEmpty() }?.let { r to it } }
            val (whole, chosen) = radio?.let { it.second to 0 } ?: tree.queueFor(single.mediaId)
            // La stessa raccolta mentre la ascolti: i brani saltati o già sentiti non tornano (tranne quello che tocchi).
            val picked = MediaId.parse(single.mediaId)
            restoredOrder = null
            val container = radio?.first ?: if (picked?.isTrack == true) picked.parent?.toString() else single.mediaId
            listening = listening.startingOn(container, System.currentTimeMillis())
            val (kept, keptStart) = listening.filter(whole.map { it.mediaId }, if (picked?.isTrack == true) chosen else -1)
            // Raccolte e playlist: dal brano toccato alla fine e poi dall'inizio, così suonano tutte
            // prima di «Continua ad ascoltare», anche partendo dall'ultimo brano ([QueueOrder]).
            val order = if (QueueOrder.wrapsAround(container)) QueueOrder.fromHere(kept.size, keptStart) else kept.indices.toList()
            val queue = order.map { whole[kept[it]] }
            val index = if (order.firstOrNull() == keptStart) 0 else keptStart.coerceAtLeast(0)
            shuffleFromCurrent = true
            val shuffle = single.requestMetadata.extras?.getBoolean(EXTRA_SHUFFLE) == true
            val start = if (shuffle && queue.isNotEmpty()) queue.indices.random() else index
            // Una puntata riparte da dove eri arrivato (a meno che chi chiede non dica da dove).
            val position = if (startPositionMs == C.TIME_UNSET || startPositionMs == 0L) {
                queue.getOrNull(start)?.let { app.podcasts.resumeAt(it.mediaId) }?.takeIf { it > 0 } ?: startPositionMs
            } else {
                startPositionMs
            }
            MediaItemsWithStartPosition(queue.toItems(), start, position)
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
            shuffleFromCurrent = true
            val (queue, index) = tree.queueFor(mediaId)
            if (queue.isEmpty()) throw UnsupportedOperationException("Brano non più disponibile")
            MediaItemsWithStartPosition(queue.toItems(), index, position)
        }
    }

    /** L'ultimo brano per cui si è già chiesto il seguito: una richiesta sola per brano. */
    private var continuedAfter: String? = null

    /**
     * "Continua ad ascoltare" ([Autoplay]): parte l'ultimo brano della coda e dopo non c'è
     * niente, allora si aggiungono brani simili (la radio del brano, o brani a caso del server).
     * Si aggiungono subito, così la dissolvenza ha già il brano dopo.
     */
    private fun continueAfterQueue() {
        // Coda appena arrivata: prima si rifà il casuale dal brano scelto (onTimelineChanged),
        // altrimenti il brano potrebbe risultare l'ultimo e si passerebbe ad altro troppo presto.
        if (shuffleFromCurrent) return
        val last = player.currentMediaItem ?: return
        val needed = Autoplay.needed(
            enabled = app.settings.continueListening,
            hasNext = player.hasNextMediaItem(),
            repeatOff = player.repeatMode == Player.REPEAT_MODE_OFF,
            endOfTrackTimer = app.sleepTimer.value == BaldMusicApp.SLEEP_END_OF_TRACK,
        )
        if (!needed) return
        val key = "${player.currentMediaItemIndex}|${last.mediaId}"
        if (continuedAfter == key) return
        continuedAfter = key
        val source = Autoplay.source(last.mediaId) ?: return
        scope.launch {
            val nodes = runCatching { tree.playableIn(source.toString()) }.getOrDefault(emptyList())
            // Nel frattempo è cambiato qualcosa (salto, coda nuova): niente.
            if (player.currentMediaItem?.mediaId != last.mediaId || player.hasNextMediaItem()) return@launch
            val queue = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }
            // Né quelli in coda né quelli già sentiti o saltati in questo ascolto (un saltato è uscito dalla coda).
            val chosen = Autoplay.pick(nodes.map { it.mediaId }, listening.notAgain(queue)).map { nodes[it] }
            if (chosen.isEmpty()) return@launch
            val first = player.mediaItemCount
            player.addMediaItems(chosen.toItems())
            // In casuale i brani nuovi vanno dopo quello in corso, non sparsi anche prima.
            if (player.shuffleModeEnabled) {
                val timeline = player.currentTimeline
                val order = ArrayList<Int>()
                var i = timeline.getFirstWindowIndex(true)
                while (i != C.INDEX_UNSET) {
                    if (i < first) order += i
                    i = timeline.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, true)
                }
                order += first until player.mediaItemCount
                player.setShuffleOrder(androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder(order.toIntArray(), System.nanoTime()))
            }
        }
    }

    /**
     * Ordine casuale che parte dal brano in corso: ExoPlayer ne pesca uno qualunque, e con
     * "ripeti" spento i brani che nell'ordine stanno prima di quello in corso non suonerebbero mai.
     */
    private fun shuffleStartingHere() {
        val count = player.mediaItemCount
        val current = player.currentMediaItemIndex
        if (count < 2 || current !in 0 until count) return
        val ids = queueIds()
        // I brani già sentiti in questo ascolto stanno prima di quello in corso: col casuale rifatto non tornano.
        val order = listening.shuffleOrder(ids, current)
        if (order[0] == current && player.currentTimeline.getFirstWindowIndex(true) == current) return
        player.setShuffleOrder(androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder(order, System.nanoTime()))
    }

    private fun queueIds() = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }

    /** L'ordine in cui suona la coda col casuale. */
    private fun shuffleOrderNow(): List<Int> {
        val timeline = player.currentTimeline
        val out = ArrayList<Int>(timeline.windowCount)
        var i = timeline.getFirstWindowIndex(true)
        while (i != C.INDEX_UNSET) {
            out += i
            i = timeline.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, true)
        }
        return out
    }

    /** Spento il casuale: i brani già sentiti che in ordine verrebbero dopo si spostano prima di quello in corso. */
    private fun keepHeardBehind() {
        val current = player.currentMediaItemIndex
        if (current == C.INDEX_UNSET) return
        listening.linearMoves(queueIds(), current).forEach { (from, to) -> player.moveMediaItem(from, to) }
    }

    /**
     * Lasciato un brano ([ListeningSession.left]): sentito o saltato. Uno saltato esce subito dalla
     * coda, così non torna né con «ripeti tutto» né rifacendo il casuale.
     */
    private fun noteLeft(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
        if (old.mediaItemIndex == new.mediaItemIndex) return
        val item = old.mediaItem ?: return
        val auto = reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION || crossfade.seeking
        if (!auto && reason != Player.DISCONTINUITY_REASON_SEEK) return
        val previous = player.currentTimeline.takeUnless { it.isEmpty }
            ?.getPreviousWindowIndex(old.mediaItemIndex, player.repeatMode, player.shuffleModeEnabled)
        val forward = new.mediaItemIndex != previous
        val duration = item.mediaMetadata.durationMs
        listening = listening.left(item.mediaId, old.positionMs, duration, forward, ended = auto, now = System.currentTimeMillis())
        if (auto || !listening.wasSkipped(item.mediaId)) return
        // Fuori dalla coda, dopo che il player ha finito di passare al brano nuovo.
        scope.launch {
            val at = old.mediaItemIndex
            if (at < player.mediaItemCount && at != player.currentMediaItemIndex && player.getMediaItemAt(at).mediaId == item.mediaId) player.removeMediaItem(at)
        }
    }

    companion object {
        private const val RECENT_ROOT = "recent"
        /** Quanto prima della fine di una puntata si mette in coda la successiva. */
        private const val NEXT_EPISODE_BEFORE_MS = 120_000L
        /** Dopo così il contenitore va negli «ascoltati di recente» (l'ascolto conta più tardi). */
        private const val RECENT_AFTER_MS = 20_000L
        const val CMD_TOGGLE_LIKE = "io.github.baldborne94.baldmusic.TOGGLE_LIKE"
        const val CMD_SHUFFLE = "io.github.baldborne94.baldmusic.SHUFFLE"
        const val CMD_REPEAT = "io.github.baldborne94.baldmusic.REPEAT"
        const val CMD_SLEEP = "io.github.baldborne94.baldmusic.SLEEP"
        /** Nelle puntate: indietro di 15 s, avanti di 30 s. */
        const val CMD_BACK = "io.github.baldborne94.baldmusic.BACK_15"
        const val CMD_FORWARD = "io.github.baldborne94.baldmusic.FORWARD_30"
    }
}
