package io.github.baldborne94.baldmusic.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import io.github.baldborne94.baldmusic.core.library.LibraryNode.Kind
import io.github.baldborne94.baldmusic.core.library.MediaId
import io.github.baldborne94.baldmusic.core.library.PersonalSource
import io.github.baldborne94.baldmusic.playback.kind

@Composable
fun DetailScreen(vm: PlayerViewModel, mediaId: String, title: String) {
    val ui = LocalUi.current
    val lib by vm.library.collectAsStateWithLifecycle()
    val id = remember(mediaId) { MediaId.parse(mediaId) }
    val personal = id?.source == PersonalSource.KEY
    // La musica tua cambia mentre la guardi (un cuore tolto, un brano aggiunto): si ricarica.
    val (items, error) = rememberChildren(vm, mediaId, if (personal) lib else null)
    var node by remember(mediaId) { mutableStateOf<MediaItem?>(null) }
    LaunchedEffect(vm.browser, mediaId, if (personal) lib.playlists else null) { node = vm.item(mediaId) }

    val likedPage = isLikedSongs(mediaId)
    val playlistId = personalPlaylistId(mediaId)
    val kind = node?.kind
    val list = items.orEmpty()
    val tracks = list.filter { it.kind == Kind.TRACK }
    val containers = list.filter { it.kind != Kind.TRACK && it.kind != Kind.MESSAGE }
    val hero = tracks.isNotEmpty() || kind in setOf(Kind.ALBUM, Kind.ARTIST, Kind.PLAYLIST, Kind.FOLDER) || likedPage || playlistId != null
    val shownTitle = node?.title()?.takeIf { it.isNotBlank() } ?: title

    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    val artworkColor by rememberArtworkColor(node?.mediaMetadata?.artworkUri ?: tracks.firstOrNull()?.mediaMetadata?.artworkUri, tileColor(mediaId))
    val headerColor = when {
        likedPage -> Color(0xFF4A2DB0)
        else -> artworkColor
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(160.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        val full: (androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope.() -> GridItemSpan) = { GridItemSpan(maxLineSpan) }

        if (hero) {
            item(span = full) {
                HeroHeader(
                    vm = vm,
                    mediaId = mediaId,
                    node = node,
                    title = shownTitle,
                    color = headerColor,
                    likedPage = likedPage,
                    tracks = tracks,
                    containers = containers,
                    onAddAll = { ui.addToPlaylist(tracks) },
                    onRename = if (playlistId != null) ({ renaming = true }) else null,
                    onDelete = if (playlistId != null) ({ confirmDelete = true }) else null,
                )
            }
        } else {
            item(span = full) {
                Column(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(headerColor.copy(alpha = 0.6f), Background))).statusBarsPadding()) {
                    IconButton(onClick = ui.back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Indietro") }
                    Text(shownTitle, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp))
                }
            }
        }

        when {
            error != null -> item(span = full) { Message(error) }
            items == null -> item(span = full) { Loading() }
            list.isEmpty() -> item(span = full) {
                Message(
                    when {
                        likedPage -> "Tocca il cuore su un brano per ritrovarlo qui."
                        playlistId != null -> "La playlist è vuota.\nAggiungi brani dal menu ⋮ di un brano o dal player."
                        else -> "Qui non c'è niente."
                    },
                )
            }
        }

        // Album, playlist, artisti: in griglia, tranne dentro un artista dove sono una lista con l'anno.
        if (containers.isNotEmpty()) {
            if (kind == Kind.ARTIST) {
                item(span = full) { SectionHeader("Discografia") }
                items(containers, key = { it.mediaId }, span = { GridItemSpan(maxLineSpan) }) { c ->
                    ContainerRow(c, subtitle = listOfNotNull(c.mediaMetadata.recordingYear?.toString(), "Album").joinToString(" · ")) { ui.open(c.mediaId, c.title()) }
                }
            } else if (containers.all { it.kind == Kind.SECTION || it.kind == Kind.FOLDER }) {
                items(containers, key = { it.mediaId }, span = { GridItemSpan(maxLineSpan) }) { c -> ContainerRow(c) { ui.open(c.mediaId, c.title()) } }
            } else {
                items(containers, key = { it.mediaId }) { c ->
                    Box(Modifier.padding(horizontal = 6.dp)) { GridCard(c) { ui.open(c.mediaId, c.title()) } }
                }
            }
        }

        val numbered = id?.kind == "album"
        items(tracks, key = { it.mediaId }, span = { GridItemSpan(maxLineSpan) }) { item ->
            TrackRow(
                item,
                active = sameTrack(item.mediaId, vm.current?.mediaId),
                liked = lib.isLiked(item.mediaId),
                number = if (numbered) item.mediaMetadata.trackNumber else null,
                onClick = { vm.play(item, from = shownTitle) },
                onMenu = { ui.trackMenu(item, playlistId) },
                    onSwipeQueue = { vm.enqueue(item) },
            )
        }

        if (tracks.size > 1) {
            item(span = full) {
                val total = tracks.sumOf { it.mediaMetadata.durationMs ?: 0L }
                Text(
                    "${PersonalSource.count(tracks.size)}${if (total > 0) " · ${formatTotal(total)}" else ""}",
                    color = TextMuted,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }

    if (renaming && playlistId != null) {
        NewPlaylistDialog(title = "Rinomina playlist", initial = shownTitle, confirm = "Salva", onDismiss = { renaming = false }) { name ->
            vm.renamePlaylist(playlistId, name)
            renaming = false
        }
    }
    if (confirmDelete && playlistId != null) {
        ConfirmDialog(
            title = "Eliminare “$shownTitle”?",
            text = "La playlist sparirà dalla libreria. I brani restano dove sono.",
            confirm = "Elimina",
            onDismiss = { confirmDelete = false },
        ) {
            vm.deletePlaylist(playlistId)
            confirmDelete = false
            ui.back()
        }
    }
}

@Composable
private fun HeroHeader(
    vm: PlayerViewModel,
    mediaId: String,
    node: MediaItem?,
    title: String,
    color: Color,
    likedPage: Boolean,
    tracks: List<MediaItem>,
    containers: List<MediaItem>,
    onAddAll: () -> Unit,
    onRename: (() -> Unit)?,
    onDelete: (() -> Unit)?,
) {
    val ui = LocalUi.current
    val kind = node?.kind
    var menu by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(color, color.copy(alpha = 0.35f), Background))).statusBarsPadding(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = ui.back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Indietro") }
        }
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            when {
                likedPage -> LikedArtwork(Modifier.size(220.dp), RoundedCornerShape(4.dp))
                node != null -> Artwork(node, Modifier.size(220.dp), shape = if (kind == Kind.ARTIST) CircleShape else RoundedCornerShape(4.dp))
                tracks.isNotEmpty() -> Artwork(tracks.first(), Modifier.size(220.dp), shape = RoundedCornerShape(4.dp))
            }
        }
        Spacer(Modifier.height(20.dp))
        Text(
            title,
            Modifier.padding(horizontal = 16.dp),
            style = MaterialTheme.typography.headlineMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Start,
        )
        val sub = when {
            likedPage -> "La tua musica"
            kind == Kind.ALBUM -> listOfNotNull(node?.subtitle(), node?.mediaMetadata?.recordingYear?.toString()).joinToString(" · ")
            kind == Kind.ARTIST -> if (containers.size == 1) "1 album" else "${containers.size} album"
            else -> node?.subtitle()
        }
        if (!sub.isNullOrBlank()) {
            Text(sub, Modifier.padding(horizontal = 16.dp, vertical = 6.dp), color = Color.White.copy(alpha = 0.8f), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        }
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (tracks.isNotEmpty()) {
                IconButton(onClick = onAddAll) { Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, "Aggiungi tutto a una playlist", tint = TextMuted) }
            }
            if (onRename != null || onDelete != null) {
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "Altro", tint = TextMuted) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        onRename?.let { DropdownMenuItem(text = { Text("Rinomina") }, leadingIcon = { Icon(Icons.Rounded.Edit, null) }, onClick = { menu = false; it() }) }
                        onDelete?.let { DropdownMenuItem(text = { Text("Elimina playlist") }, leadingIcon = { Icon(Icons.Rounded.Delete, null) }, onClick = { menu = false; it() }) }
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            if (tracks.isNotEmpty() || kind == Kind.ARTIST) {
                IconButton(onClick = { vm.toggleShuffle() }) {
                    Icon(Icons.Rounded.Shuffle, "Casuale", tint = if (vm.shuffle) Accent else TextMuted, modifier = Modifier.size(28.dp))
                }
                Spacer(Modifier.width(8.dp))
                val playingHere = vm.playingContainer == mediaId && vm.isPlaying
                BigPlayButton(playingHere) {
                    if (vm.playingContainer == mediaId && vm.current != null) vm.toggle()
                    else vm.playAll(mediaId, shuffled = vm.shuffle, from = title)
                }
            }
        }
    }
}
