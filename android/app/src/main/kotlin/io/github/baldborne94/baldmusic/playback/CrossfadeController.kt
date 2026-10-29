@file:OptIn(UnstableApi::class)

package io.github.baldborne94.baldmusic.playback

import android.content.Context
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import io.github.baldborne94.baldmusic.core.library.Crossfade
import io.github.baldborne94.baldmusic.core.podcast.PodcastSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Dissolvenza incrociata ([Crossfade]). Poco prima della fine, il brano in corso si prepara su un
 * secondo ExoPlayer fermo sul punto in cui partirà la sfumatura ([outgoing]); lì parte lui e si
 * abbassa ([fadingOut]), mentre il player vero (quello della sessione, della notifica e di Android
 * Auto) passa al brano dopo e sale ([crossIn]).
 */
internal class CrossfadeController(
    private val context: Context,
    private val player: ExoPlayer,
    private val mediaSources: MediaSource.Factory,
    private val attributes: AudioAttributes,
    private val scope: CoroutineScope,
    /** Secondi di dissolvenza scelti (0 = spenta). */
    private val seconds: () -> Int,
    /** Timer «a fine brano»: ci si ferma, niente dissolvenza. */
    private val endOfTrackTimer: () -> Boolean,
    /** Il volume del brano entrante è cambiato ([crossIn]). */
    private val applyVolume: () -> Unit,
) {
    private var outgoing: ExoPlayer? = null
    private var outgoingFor = C.INDEX_UNSET
    private var fadingOut: ExoPlayer? = null
    private var crossJob: Job? = null
    private var crossWatch: Job? = null

    /** Il volume del brano che entra, da 0 a 1: si moltiplica per quello del ReplayGain. */
    var crossIn = 1f
        private set

    /** Il passaggio al brano dopo l'ha chiesto la dissolvenza: conta come brano finito da solo. */
    var seeking = false
        private set

    /** Il passaggio voluto dalla dissolvenza: true una volta, poi torna false. */
    fun consumeSeek(): Boolean = seeking.also { seeking = false }

    /** Qualunque interruzione (pausa, salto, chiamata): il brano che sfumava si ferma, quello preparato non vale più. */
    fun stop() {
        end()
        drop()
    }

    /** Il controllo della dissolvenza gira solo mentre si suona con la dissolvenza accesa ([Crossfade.watching]). */
    fun update() {
        val needed = Crossfade.watching(seconds(), player.playWhenReady)
        if (needed == (crossWatch != null)) return
        crossWatch?.cancel()
        crossWatch = null
        if (needed) {
            crossWatch = scope.launch {
                while (isActive) {
                    delay(200)
                    watch()
                }
            }
        } else if (crossJob == null) {
            drop()
        }
    }

    /** Ogni 200 ms: è ora di preparare il brano che finisce, o di cominciare a sfumare? */
    private fun watch() {
        if (crossJob != null) return
        val seconds = seconds()
        val current = player.currentMediaItem
        val duration = player.duration
        val next = player.nextMediaItemIndex
        val ok = seconds > 0 && player.playWhenReady && current != null && duration != C.TIME_UNSET && next != C.INDEX_UNSET &&
            player.repeatMode != Player.REPEAT_MODE_ONE && !endOfTrackTimer() &&
            Crossfade.applies(
                seconds, duration,
                sameAlbum = current.albumId != null && current.albumId == player.getMediaItemAt(next).albumId,
                episode = PodcastSource.episodeKey(current.mediaId) != null || PodcastSource.episodeKey(player.getMediaItemAt(next).mediaId) != null,
            )
        if (!ok) {
            drop()
            return
        }
        val start = duration - seconds * 1000L
        val position = player.currentPosition
        val index = player.currentMediaItemIndex
        when {
            position in (start - 3_000) until start && outgoingFor != index -> prepare(current!!, start, index)
            position >= start && outgoingFor == index -> start(duration - position)
        }
    }

    /** Il brano in corso, su un secondo player fermo sul punto in cui comincerà a sfumare. */
    private fun prepare(item: MediaItem, startMs: Long, index: Int) {
        drop()
        outgoing = ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSources)
            // L'audio focus resta al player vero: questo suona solo per pochi secondi.
            .setAudioAttributes(attributes, /* handleAudioFocus = */ false)
            .build()
            .apply {
                setMediaItem(item, startMs)
                volume = player.volume
                prepare()
            }
        outgoingFor = index
    }

    /** Il brano preparato per sfumare non vale più. */
    fun drop() {
        outgoing?.release()
        outgoing = null
        outgoingFor = C.INDEX_UNSET
    }

    /** Il brano che finisce continua sul secondo player e si abbassa; il player vero passa al brano dopo e sale. */
    private fun start(lengthMs: Long) {
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
        seeking = true
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
            end()
        }
    }

    /** Fine (o interruzione) della sfumatura: il brano vecchio si ferma, quello nuovo a volume pieno. */
    fun end() {
        val job = crossJob
        crossJob = null
        fadingOut?.release()
        fadingOut = null
        crossIn = 1f
        applyVolume()
        // Chiamata anche dalla sfumatura stessa, alla fine: annullarla lì non fa danni.
        job?.cancel()
    }
}
