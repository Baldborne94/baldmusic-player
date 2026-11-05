package io.github.baldborne94.baldmusic.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import io.github.baldborne94.baldmusic.core.library.CoverScreen
import io.github.baldborne94.baldmusic.playback.PlaybackService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch

/**
 * «Schermo copertina» (da «In riproduzione»): fondo nero, la copertina grande e il titolo,
 * anche col telefono bloccato, come il salvaschermo di un Kindle. Lo schermo resta acceso e
 * poco luminoso solo mentre suona ([CoverScreen.keepAwake]); poi si spegne da solo come sempre.
 * Sta sopra il blocco schermo senza sbloccarlo: chiudendolo col telefono bloccato si torna al
 * blocco, non al resto dell'app.
 */
class CoverActivity : ComponentActivity() {
    private var controller: MediaController? = null
    private var connecting: Job? = null
    private var current by mutableStateOf<MediaItem?>(null)
    private var playing by mutableStateOf(false)
    private var stoppedAt by mutableStateOf<Long?>(null)

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = sync(player)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Sopra il blocco schermo, senza sbloccarlo.
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        }
        // A tutto schermo: niente barre in alto e in basso (tornano scorrendo dal bordo).
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).run {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        dim(true)
        setContent {
            BaldMusicTheme {
                // Ogni secondo: acceso mentre suona, poi si lascia spegnere.
                LaunchedEffect(Unit) {
                    while (true) {
                        awake(CoverScreen.keepAwake(playing, stoppedAt, System.currentTimeMillis()))
                        delay(1_000)
                    }
                }
                CoverScreenContent(
                    item = current,
                    playing = playing,
                    onToggle = { controller?.run { if (isPlaying) pause() else play() } },
                    onPrevious = { controller?.seekToPrevious() },
                    onNext = { controller?.seekToNext() },
                    onClose = ::finish,
                    onTouched = { touched -> dim(!touched) },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        connecting = lifecycleScope.launch {
            val token = SessionToken(this@CoverActivity, ComponentName(this@CoverActivity, PlaybackService::class.java))
            val c = runCatching { MediaController.Builder(this@CoverActivity, token).buildAsync().await() }.getOrNull() ?: return@launch finish()
            controller = c
            c.addListener(listener)
            sync(c)
        }
    }

    override fun onStop() {
        connecting?.cancel()
        controller?.run {
            removeListener(listener)
            release()
        }
        controller = null
        super.onStop()
    }

    private fun sync(p: Player) {
        current = p.currentMediaItem
        playing = p.isPlaying
        stoppedAt = CoverScreen.stoppedAt(playing, stoppedAt, System.currentTimeMillis())
        // Non suona più niente (coda chiusa dal ✕ o finita): non resta niente da mostrare.
        if (current == null) finish()
    }

    private fun awake(on: Boolean) {
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /** Poco luminoso; toccato lo schermo, la luminosità di sempre finché ci sono i comandi. */
    private fun dim(on: Boolean) {
        window.attributes = window.attributes.apply {
            screenBrightness = if (on) CoverScreen.BRIGHTNESS else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
    }

    companion object {
        fun open(context: Context) = context.startActivity(Intent(context, CoverActivity::class.java))
    }
}

@Composable
private fun CoverScreenContent(
    item: MediaItem?,
    playing: Boolean,
    onToggle: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
    onTouched: (Boolean) -> Unit,
) {
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

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                touchedAt = System.currentTimeMillis()
            },
    ) {
        BoxWithConstraints(Modifier.fillMaxSize().offset(x, y), contentAlignment = Alignment.Center) {
            // Grande, ma con spazio per il titolo anche col telefono girato.
            val side = min(min(maxWidth * 0.82f, maxHeight * 0.62f), 560.dp)
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.alpha(shown)) {
                Artwork(item, Modifier.size(side), shape = RoundedCornerShape(8.dp))
                Text(
                    item?.title().orEmpty(),
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 18.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(max = side).padding(top = 18.dp),
                )
                Text(
                    item?.mediaMetadata?.artist?.toString().orEmpty(),
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(max = side).padding(top = 4.dp),
                )
            }
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
                IconButton(onClick = { touchedAt = System.currentTimeMillis(); onPrevious() }, Modifier.size(56.dp)) {
                    Icon(Icons.Rounded.SkipPrevious, "Precedente", tint = Color.White, modifier = Modifier.size(36.dp))
                }
                Box(
                    Modifier.size(64.dp).clip(CircleShape).background(Color.White).clickable {
                        touchedAt = System.currentTimeMillis()
                        onToggle()
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
                IconButton(onClick = { touchedAt = System.currentTimeMillis(); onNext() }, Modifier.size(56.dp)) {
                    Icon(Icons.Rounded.SkipNext, "Successivo", tint = Color.White, modifier = Modifier.size(36.dp))
                }
            }
        }
    }
}
