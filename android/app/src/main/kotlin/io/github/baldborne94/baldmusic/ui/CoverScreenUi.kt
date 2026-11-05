package io.github.baldborne94.baldmusic.ui

import android.content.ComponentName
import android.content.Context
import android.os.BatteryManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import coil3.compose.AsyncImage
import io.github.baldborne94.baldmusic.app
import io.github.baldborne94.baldmusic.core.library.CoverScreen
import io.github.baldborne94.baldmusic.playback.PlaybackService
import io.github.baldborne94.baldmusic.playback.toTrackRef
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch

/**
 * Quello che suona, visto dallo schermo copertina (l'attività e il salvaschermo): un suo
 * `MediaController`, collegato solo mentre la copertina si vede.
 */
internal class CoverPlayer(private val context: Context, private val onGone: () -> Unit) {
    var current by mutableStateOf<MediaItem?>(null)
        private set
    var playing by mutableStateOf(false)
        private set
    private var stoppedAt: Long? = null
    private var timerWhilePlaying = false
    private var controller: MediaController? = null
    private var connecting: Job? = null

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = sync(player)
    }

    fun connect(scope: CoroutineScope) {
        connecting = scope.launch {
            val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
            val c = runCatching { MediaController.Builder(context, token).buildAsync().await() }.getOrNull()
            if (c == null) {
                onGone()
                return@launch
            }
            controller = c
            c.addListener(listener)
            sync(c)
        }
    }

    fun release() {
        connecting?.cancel()
        controller?.run {
            removeListener(listener)
            release()
        }
        controller = null
    }

    fun toggle() = controller?.run { if (isPlaying) pause() else play() }
    fun previous() = controller?.seekToPrevious()
    fun next() = controller?.seekToNext()

    /** Lo schermo resta acceso adesso? ([CoverScreen.keepAwake], con timer e batteria di adesso.) */
    fun stayOn(nowMs: Long): Boolean {
        val timer = context.app.sleepTimer.value != null
        if (playing) timerWhilePlaying = timer
        val battery = context.getSystemService(BatteryManager::class.java)
        val percent = battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 }
        return CoverScreen.keepAwake(
            playing = playing,
            stoppedAtMs = stoppedAt,
            nowMs = nowMs,
            // Il timer, finito, ha messo in pausa e si è spento da solo: la musica è finita per la notte.
            byTimer = !playing && timerWhilePlaying && !timer,
            batteryPercent = percent,
            charging = battery?.isCharging == true,
        )
    }

    private fun sync(p: Player) {
        current = p.currentMediaItem
        playing = p.isPlaying
        stoppedAt = CoverScreen.stoppedAt(playing, stoppedAt, System.currentTimeMillis())
        // Non suona più niente (coda chiusa dal ✕ o finita): non resta niente da mostrare.
        if (current == null) onGone()
    }
}

/**
 * La copertina a tutto schermo: da bordo a bordo, dietro la stessa copertina ingrandita,
 * sfocata e scurita. Un tocco mostra i comandi, il doppio tocco mette o toglie «mi piace»,
 * scorrere di lato cambia brano.
 */
@Composable
internal fun CoverScreenContent(player: CoverPlayer, onClose: () -> Unit, onTouched: (Boolean) -> Unit) {
    val item = player.current
    val playing = player.playing
    val context = LocalContext.current
    val library by context.app.userData.state.collectAsState()
    val liked = item != null && library.isLiked(item.mediaId)

    // Un tocco mostra i comandi per qualche secondo.
    var touchedAt by remember { mutableLongStateOf(0L) }
    var controls by remember { mutableStateOf(false) }
    LaunchedEffect(touchedAt) {
        if (touchedAt == 0L) return@LaunchedEffect
        controls = true
        onTouched(true)
        delay(CoverScreen.CONTROLS_MS)
        controls = false
        onTouched(false)
    }
    // Il cuore che compare un attimo dopo il doppio tocco.
    var heartAt by remember { mutableLongStateOf(0L) }
    var heart by remember { mutableStateOf(false) }
    LaunchedEffect(heartAt) {
        if (heartAt == 0L) return@LaunchedEffect
        heart = true
        delay(900)
        heart = false
    }
    // Ogni minuto la copertina si sposta di poco: sullo schermo OLED non resta il segno.
    val step by produceState(0L) {
        while (true) {
            delay(CoverScreen.SHIFT_EVERY_MS)
            value++
        }
    }
    val (dx, dy) = CoverScreen.shift(step)
    val x by animateDpAsState(dx.dp, tween(2_000), label = "spostamento-x")
    val y by animateDpAsState(dy.dp, tween(2_000), label = "spostamento-y")
    val shown by animateFloatAsState(if (playing) 1f else 0.55f, label = "pausa")
    var drag by remember { mutableFloatStateOf(0f) }
    val dragShown by animateFloatAsState(drag, label = "trascinamento")

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { touchedAt = System.currentTimeMillis() },
                    onDoubleTap = {
                        player.current?.let { context.app.userData.toggleLike(it.toTrackRef()) }
                        heartAt = System.currentTimeMillis()
                    },
                )
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        when (CoverScreen.swipe(drag, size.width.toFloat())) {
                            1 -> player.next()
                            -1 -> player.previous()
                        }
                        drag = 0f
                    },
                    onDragCancel = { drag = 0f },
                ) { _, delta -> drag += delta }
            },
        contentAlignment = Alignment.Center,
    ) {
        // A tutto schermo: la copertina è quadrata, lo schermo no; tagliata si perderebbero i lati.
        item?.mediaMetadata?.artworkUri?.let { uri ->
            AsyncImage(
                model = uri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        // Un po' più grande dello schermo: spostandosi non scopre i bordi.
                        scaleX = 1.15f
                        scaleY = 1.15f
                        translationX = x.toPx()
                        translationY = y.toPx()
                    }
                    // Android 12 e oltre; prima resta solo scurita.
                    .blur(40.dp)
                    .alpha(0.45f * shown),
            )
        }
        // Da bordo a bordo, con lo spazio per il titolo anche col telefono girato.
        val side = min(min(maxWidth, maxHeight * 0.7f), 720.dp)
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .offset(x, y)
                .graphicsLayer {
                    translationX = dragShown
                    alpha = shown * (1f - (kotlin.math.abs(dragShown) / 900f).coerceIn(0f, 0.6f))
                },
        ) {
            Artwork(item, Modifier.size(side), shape = RectangleShape)
            Text(
                item?.title().orEmpty(),
                color = Color.White.copy(alpha = 0.9f),
                fontSize = 20.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = side).padding(start = 24.dp, end = 24.dp, top = 20.dp),
            )
            Text(
                item?.mediaMetadata?.artist?.toString().orEmpty(),
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = side).padding(start = 24.dp, end = 24.dp, top = 4.dp),
            )
        }

        AnimatedVisibility(heart, enter = scaleIn() + fadeIn(), exit = fadeOut()) {
            Icon(
                if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                if (liked) "Ti piace" else "Tolto da Mi piace",
                tint = if (liked) Accent else Color.White,
                modifier = Modifier.size(120.dp),
            )
        }

        AnimatedVisibility(controls, Modifier.align(Alignment.TopEnd), enter = fadeIn(), exit = fadeOut()) {
            IconButton(onClick = onClose, modifier = Modifier.padding(24.dp)) {
                Icon(Icons.Rounded.Close, "Chiudi lo schermo copertina", tint = Color.White)
            }
        }
        AnimatedVisibility(controls, Modifier.align(Alignment.BottomCenter), enter = fadeIn(), exit = fadeOut()) {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 40.dp),
                horizontalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { touchedAt = System.currentTimeMillis(); player.previous() }, Modifier.size(56.dp)) {
                    Icon(Icons.Rounded.SkipPrevious, "Precedente", tint = Color.White, modifier = Modifier.size(36.dp))
                }
                Box(
                    Modifier.size(64.dp).clip(CircleShape).background(Color.White).clickable {
                        touchedAt = System.currentTimeMillis()
                        player.toggle()
                    },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        if (playing) "Pausa" else "Riproduci",
                        tint = Color.Black,
                        modifier = Modifier.size(36.dp),
                    )
                }
                IconButton(onClick = { touchedAt = System.currentTimeMillis(); player.next() }, Modifier.size(56.dp)) {
                    Icon(Icons.Rounded.SkipNext, "Successivo", tint = Color.White, modifier = Modifier.size(36.dp))
                }
            }
        }
    }
}

/** La luminosità dello schermo copertina adesso: più bassa di notte ([CoverScreen.brightness]). */
internal fun coverBrightness(): Float = CoverScreen.brightness(java.time.LocalTime.now().hour)
