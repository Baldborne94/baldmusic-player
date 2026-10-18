package io.github.baldborne94.baldmusic.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.MediaItem
import coil3.compose.SubcomposeAsyncImage
import io.github.baldborne94.baldmusic.core.library.LibraryNode.Kind
import io.github.baldborne94.baldmusic.core.library.MediaId
import io.github.baldborne94.baldmusic.core.library.PersonalSource
import io.github.baldborne94.baldmusic.playback.kind

fun MediaItem.title() = mediaMetadata.title?.toString().orEmpty()
fun MediaItem.subtitle() = (mediaMetadata.subtitle ?: mediaMetadata.artist)?.toString()

fun formatDuration(ms: Long?): String {
    if (ms == null || ms <= 0) return "0:00"
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

fun formatTotal(ms: Long): String {
    val min = (ms / 60_000).toInt()
    return if (min < 60) "$min min" else "${min / 60} h ${min % 60} min"
}

/** Stesso brano anche se arrivato da contenitori diversi (album, ricerca, coda…). */
fun sameTrack(a: String?, b: String?): Boolean {
    val x = a?.let { MediaId.parse(it) } ?: return false
    val y = b?.let { MediaId.parse(it) } ?: return false
    return x.isTrack && y.isTrack && x.source == y.source && x.id == y.id
}

fun isLikedSongs(mediaId: String) = mediaId == "${PersonalSource.KEY}/${PersonalSource.LIKED}"

fun kindIcon(kind: Kind?): ImageVector = when (kind) {
    Kind.ARTIST -> Icons.Rounded.Person
    Kind.ALBUM -> Icons.Rounded.Album
    Kind.PLAYLIST -> Icons.AutoMirrored.Rounded.QueueMusic
    Kind.FOLDER -> Icons.Rounded.Folder
    else -> Icons.Rounded.MusicNote
}

/** Copertina con segnaposto; i "Brani che ti piacciono" hanno la loro copertina col cuore. */
@Composable
fun Artwork(item: MediaItem?, modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(6.dp)) {
    if (item != null && isLikedSongs(item.mediaId)) {
        LikedArtwork(modifier, shape)
        return
    }
    val uri = item?.mediaMetadata?.artworkUri
    val kind = item?.kind
    Box(
        modifier
            .aspectRatio(1f)
            .clip(shape)
            .background(Brush.linearGradient(listOf(Surface2, Color(0xFF30333E)))),
        contentAlignment = Alignment.Center,
    ) {
        val placeholder = @Composable {
            Icon(kindIcon(kind), null, tint = TextMuted, modifier = Modifier.fillMaxSize(0.42f))
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

@Composable
fun LikedArtwork(modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(6.dp)) {
    Box(modifier.aspectRatio(1f).clip(shape).background(LikedBrush), contentAlignment = Alignment.Center) {
        Icon(Icons.Rounded.Favorite, null, tint = Color.White, modifier = Modifier.fillMaxSize(0.42f))
    }
}

/** Titolo di sezione con eventuale "Mostra tutto". */
@Composable
fun SectionHeader(title: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 28.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = SectionTitle, modifier = Modifier.weight(1f))
        if (action != null && onAction != null) {
            TextButton(onClick = onAction) { Text(action, color = TextMuted, fontWeight = FontWeight.Bold) }
        }
    }
}

/** Carosello orizzontale di album, playlist o artisti. */
@Composable
fun Carousel(items: List<MediaItem>, size: Dp = 150.dp, onClick: (MediaItem) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        items(items, key = { it.mediaId }) { CoverCard(it, size) { onClick(it) } }
    }
}

@Composable
fun CoverCard(item: MediaItem, size: Dp, onClick: () -> Unit) {
    val round = item.kind == Kind.ARTIST
    Column(
        Modifier.width(size).clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick),
        horizontalAlignment = if (round) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        Artwork(item, Modifier.size(size), shape = if (round) CircleShape else RoundedCornerShape(8.dp))
        Spacer(Modifier.height(8.dp))
        Text(item.title(), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
        item.subtitle()?.let {
            Text(it, color = TextMuted, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, lineHeight = 16.sp)
        }
    }
}

/** Griglia di copertine (per sezioni come "Album"). */
@Composable
fun GridCard(item: MediaItem, onClick: () -> Unit) {
    val round = item.kind == Kind.ARTIST
    Column(
        Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(6.dp),
        horizontalAlignment = if (round) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        Artwork(item, Modifier.fillMaxWidth(), shape = if (round) CircleShape else RoundedCornerShape(8.dp))
        Spacer(Modifier.height(8.dp))
        Text(item.title(), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
        item.subtitle()?.let { Text(it, color = TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp) }
    }
}

/** Riga di un brano: copertina o numero, titolo, artista, cuore, menu. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackRow(
    item: MediaItem,
    active: Boolean,
    liked: Boolean,
    number: Int? = null,
    onClick: () -> Unit,
    onMenu: () -> Unit,
    onSwipeQueue: (() -> Unit)? = null,
) {
    if (onSwipeQueue == null) {
        TrackRowContent(item, active, liked, number, onClick, onMenu)
        return
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    // Scorri verso destra per mettere in coda, come in Spotify: la riga poi torna al suo posto.
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.StartToEnd) {
                onSwipeQueue()
                haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                android.widget.Toast.makeText(context, "Aggiunto in coda", android.widget.Toast.LENGTH_SHORT).show()
            }
            false
        },
        positionalThreshold = { it * 0.35f },
    )
    SwipeToDismissBox(
        state = state,
        enableDismissFromEndToStart = false,
        backgroundContent = {
            Box(Modifier.fillMaxSize().background(Color(0xFF1F8A4C)).padding(start = 24.dp), contentAlignment = Alignment.CenterStart) {
                Icon(Icons.AutoMirrored.Rounded.QueueMusic, "In coda", tint = Color.White)
            }
        },
    ) {
        Box(Modifier.background(Background)) { TrackRowContent(item, active, liked, number, onClick, onMenu) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TrackRowContent(
    item: MediaItem,
    active: Boolean,
    liked: Boolean,
    number: Int?,
    onClick: () -> Unit,
    onMenu: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onMenu)
            .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (number != null) {
            Text(
                "$number",
                Modifier.width(30.dp),
                color = if (active) Accent else TextMuted,
                textAlign = TextAlign.Start,
                fontSize = 15.sp,
            )
        } else {
            Artwork(item, Modifier.size(50.dp), shape = RoundedCornerShape(4.dp))
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                item.title(),
                color = if (active) Accent else MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (liked) {
                    Icon(Icons.Rounded.Favorite, "Ti piace", tint = Accent, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                }
                Text(item.subtitle().orEmpty(), color = TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
            }
        }
        IconButton(onClick = onMenu) { Icon(Icons.Rounded.MoreVert, "Altre azioni", tint = TextMuted) }
    }
}

/** Riga di un contenitore (album, playlist, artista) nelle liste stile libreria. */
@Composable
fun ContainerRow(item: MediaItem, subtitle: String? = item.subtitle(), pinned: Boolean = false, onClick: () -> Unit) {
    val round = item.kind == Kind.ARTIST
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(item, Modifier.size(64.dp), shape = if (round) CircleShape else RoundedCornerShape(6.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title(), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (pinned) {
                    Icon(Icons.Rounded.PushPin, null, tint = Accent, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                }
                Text(subtitle.orEmpty(), color = TextMuted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Il grande tasto play delle pagine, come quello di Spotify. */
@Composable
fun BigPlayButton(playing: Boolean, size: Dp = 56.dp, onClick: () -> Unit) {
    Box(
        Modifier.size(size).clip(CircleShape).background(AccentBrush).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Pausa" else "Riproduci", tint = Color.Black, modifier = Modifier.size(size * 0.55f))
    }
}

@Composable
fun Loading(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Accent) }
}

@Composable
fun Message(text: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text, textAlign = TextAlign.Center, color = TextMuted)
        if (action != null && onAction != null) TextButton(onClick = onAction) { Text(action, color = Accent) }
    }
}

/** Una voce dei menu a foglio dal basso. */
@Composable
fun SheetAction(icon: ImageVector, label: String, tint: Color = MaterialTheme.colorScheme.onBackground, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (tint == Accent || tint == Danger) tint else TextMuted)
        Spacer(Modifier.width(18.dp))
        Text(label, fontSize = 16.sp, color = tint)
    }
}

/** Le azioni su un brano: il foglio che si apre col ⋮ o con la pressione lunga. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackActionsSheet(
    item: MediaItem,
    liked: Boolean,
    onDismiss: () -> Unit,
    onToggleLike: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onPlayNext: () -> Unit,
    onEnqueue: () -> Unit,
    onGoAlbum: (() -> Unit)?,
    onGoArtist: (() -> Unit)?,
    onRemoveFromPlaylist: (() -> Unit)? = null,
    removeLabel: String = "Rimuovi da questa playlist",
    onRadio: (() -> Unit)? = null,
    onShare: (() -> Unit)? = null,
    onInfo: (() -> Unit)? = null,
    onTrash: (() -> Unit)? = null,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(), containerColor = Surface1) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Artwork(item, Modifier.size(52.dp))
            Spacer(Modifier.width(14.dp))
            Column {
                Text(item.title(), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.basicMarquee())
                Text(item.subtitle().orEmpty(), color = TextMuted, maxLines = 1, fontSize = 13.sp)
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.outline)
        val act: (() -> Unit) -> () -> Unit = { f -> { f(); onDismiss() } }
        SheetAction(if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, if (liked) "Togli da Brani che ti piacciono" else "Aggiungi a Brani che ti piacciono", if (liked) Accent else MaterialTheme.colorScheme.onBackground, act(onToggleLike))
        SheetAction(Icons.AutoMirrored.Rounded.PlaylistAdd, "Aggiungi a playlist", onClick = act(onAddToPlaylist))
        onRemoveFromPlaylist?.let { SheetAction(Icons.Rounded.RemoveCircleOutline, removeLabel, onClick = act(it)) }
        SheetAction(Icons.Rounded.SkipNext, "Riproduci dopo", onClick = act(onPlayNext))
        SheetAction(Icons.AutoMirrored.Rounded.QueueMusic, "Aggiungi in coda", onClick = act(onEnqueue))
        onRadio?.let { SheetAction(Icons.Rounded.Radio, "Avvia radio", onClick = act(it)) }
        onGoAlbum?.let { SheetAction(Icons.Rounded.Album, "Vai all'album", onClick = act(it)) }
        onGoArtist?.let { SheetAction(Icons.Rounded.Person, "Vai all'artista", onClick = act(it)) }
        onShare?.let { SheetAction(Icons.Rounded.Share, "Condividi", onClick = act(it)) }
        onInfo?.let { SheetAction(Icons.Rounded.Info, "Informazioni sul brano", onClick = act(it)) }
        onTrash?.let { SheetAction(Icons.Rounded.Delete, "Elimina dal telefono", tint = Danger, onClick = act(it)) }
        Spacer(Modifier.height(24.dp))
    }
}
