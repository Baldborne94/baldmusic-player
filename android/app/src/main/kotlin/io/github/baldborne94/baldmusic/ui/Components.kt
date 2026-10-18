package io.github.baldborne94.baldmusic.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import coil3.compose.SubcomposeAsyncImage
import io.github.baldborne94.baldmusic.core.library.LibraryNode.Kind
import io.github.baldborne94.baldmusic.playback.kind

/** Copertina con segnaposto: un'icona al posto dell'immagine mancante. */
@Composable
fun Artwork(item: MediaItem?, modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(10.dp), fallback: ImageVector = Icons.Rounded.MusicNote) {
    val uri = item?.mediaMetadata?.artworkUri
    Box(
        modifier
            .aspectRatio(1f)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val placeholder = @Composable {
            Icon(fallback, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxSize(0.4f))
        }
        if (uri == null) placeholder()
        else SubcomposeAsyncImage(
            model = uri,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
            loading = { placeholder() },
            error = { placeholder() },
        )
    }
}

fun MediaItem.title() = mediaMetadata.title?.toString().orEmpty()
fun MediaItem.subtitle() = (mediaMetadata.subtitle ?: mediaMetadata.artist)?.toString()

fun formatDuration(ms: Long?): String {
    if (ms == null || ms <= 0) return ""
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/** Album o playlist in griglia. */
@Composable
fun GridCard(item: MediaItem, onClick: () -> Unit) {
    val round = item.kind == Kind.ARTIST
    Column(
        Modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(6.dp),
        horizontalAlignment = if (round) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        Artwork(
            item,
            Modifier.fillMaxWidth(),
            shape = if (round) CircleShape else RoundedCornerShape(10.dp),
            fallback = if (round) Icons.Rounded.Person else Icons.Rounded.Album,
        )
        Spacer(Modifier.size(8.dp))
        Text(item.title(), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        item.subtitle()?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Una riga: brano, artista o sezione. */
@Composable
fun ItemRow(
    item: MediaItem,
    active: Boolean = false,
    number: Int? = null,
    onClick: () -> Unit,
    onPlayNext: (() -> Unit)? = null,
    onEnqueue: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val kind = item.kind
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (number != null) {
            Text(
                "$number",
                Modifier.width(28.dp),
                textAlign = TextAlign.End,
                color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Artwork(
                item,
                Modifier.size(48.dp),
                shape = if (kind == Kind.ARTIST) CircleShape else RoundedCornerShape(6.dp),
                fallback = when (kind) {
                    Kind.ARTIST -> Icons.Rounded.Person
                    Kind.ALBUM -> Icons.Rounded.Album
                    Kind.PLAYLIST -> Icons.AutoMirrored.Rounded.QueueMusic
                    else -> Icons.Rounded.MusicNote
                },
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                item.title(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                fontWeight = if (kind == Kind.TRACK) FontWeight.Normal else FontWeight.SemiBold,
            )
            item.subtitle()?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (kind == Kind.TRACK) {
            Text(formatDuration(item.mediaMetadata.durationMs), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing?.invoke()
        if (onPlayNext != null || onEnqueue != null) {
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "Altre azioni") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    onPlayNext?.let {
                        DropdownMenuItem(
                            text = { Text("Riproduci dopo") },
                            leadingIcon = { Icon(Icons.AutoMirrored.Rounded.QueueMusic, null) },
                            onClick = { menu = false; it() },
                        )
                    }
                    onEnqueue?.let {
                        DropdownMenuItem(
                            text = { Text("Aggiungi alla coda") },
                            leadingIcon = { Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, null) },
                            onClick = { menu = false; it() },
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun Loading() {
    Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@Composable
fun Message(text: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (action != null && onAction != null) {
            TextButton(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
fun SectionIcon(icon: ImageVector, size: Dp = 40.dp) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = MaterialTheme.colorScheme.primary) }
}
