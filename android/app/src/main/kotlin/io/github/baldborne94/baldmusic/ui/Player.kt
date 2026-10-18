package io.github.baldborne94.baldmusic.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player

@Composable
fun MiniPlayer(vm: PlayerViewModel, onOpen: () -> Unit) {
    val item = vm.current ?: return
    Column(
        Modifier
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onOpen),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Artwork(item, Modifier.size(44.dp), shape = RoundedCornerShape(8.dp))
            Column(Modifier.weight(1f)) {
                Text(item.title(), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                item.subtitle()?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            PlayPauseIcon(vm, small = true)
            IconButton(onClick = { vm.next() }) { Icon(Icons.Rounded.SkipNext, "Successivo") }
        }
        val progress = if (vm.duration > 0) (vm.position.toFloat() / vm.duration).coerceIn(0f, 1f) else 0f
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth().height(2.dp),
            trackColor = Color.Transparent,
            drawStopIndicator = {},
        )
    }
}

@Composable
private fun PlayPauseIcon(vm: PlayerViewModel, small: Boolean) {
    val size = if (small) 24.dp else 40.dp
    IconButton(
        onClick = { vm.toggle() },
        modifier = if (small) Modifier else Modifier.size(80.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onBackground),
    ) {
        val tint = if (small) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.background
        when {
            vm.buffering -> CircularProgressIndicator(Modifier.size(size), color = tint, strokeWidth = 3.dp)
            vm.isPlaying -> Icon(Icons.Rounded.Pause, "Pausa", Modifier.size(size), tint = tint)
            else -> Icon(Icons.Rounded.PlayArrow, "Riproduci", Modifier.size(size), tint = tint)
        }
    }
}

@Composable
fun NowPlayingScreen(vm: PlayerViewModel, onClose: () -> Unit, onQueue: () -> Unit) {
    val item = vm.current ?: return
    var dragging by remember { mutableStateOf<Float?>(null) }
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .clickable(enabled = false) {},
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.Rounded.KeyboardArrowDown, "Chiudi", Modifier.size(32.dp)) }
                Text(
                    "IN RIPRODUZIONE",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                IconButton(onClick = onQueue) { Icon(Icons.AutoMirrored.Rounded.QueueMusic, "Coda") }
            }
            Spacer(Modifier.weight(1f))
            Artwork(item, Modifier.fillMaxWidth().widthIn(max = 480.dp), shape = RoundedCornerShape(18.dp))
            Spacer(Modifier.height(28.dp))
            Column(Modifier.fillMaxWidth()) {
                Text(item.title(), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val sub = listOfNotNull(item.mediaMetadata.artist?.toString(), item.mediaMetadata.albumTitle?.toString()).joinToString(" · ")
                if (sub.isNotEmpty()) Text(sub, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(16.dp))
            val duration = vm.duration.coerceAtLeast(1L).toFloat()
            Slider(
                value = (dragging ?: vm.position.toFloat()).coerceIn(0f, duration),
                onValueChange = { dragging = it },
                onValueChangeFinished = {
                    dragging?.let { vm.seekTo(it.toLong()) }
                    dragging = null
                },
                valueRange = 0f..duration,
                colors = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.onBackground),
            )
            Row(Modifier.fillMaxWidth()) {
                Text(formatDuration((dragging?.toLong() ?: vm.position).coerceAtLeast(0)).ifEmpty { "0:00" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                Text(formatDuration(vm.duration), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { vm.toggleShuffle() }) {
                    Icon(Icons.Rounded.Shuffle, "Casuale", tint = if (vm.shuffle) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { vm.previous() }, Modifier.size(64.dp)) { Icon(Icons.Rounded.SkipPrevious, "Precedente", Modifier.size(40.dp)) }
                PlayPauseIcon(vm, small = false)
                IconButton(onClick = { vm.next() }, Modifier.size(64.dp)) { Icon(Icons.Rounded.SkipNext, "Successivo", Modifier.size(40.dp)) }
                IconButton(onClick = { vm.cycleRepeat() }) {
                    Icon(
                        if (vm.repeat == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                        "Ripeti",
                        tint = if (vm.repeat != Player.REPEAT_MODE_OFF) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            vm.error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.weight(1f))
        }
    }
}
