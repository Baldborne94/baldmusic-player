package io.github.baldborne94.baldmusic.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material.icons.rounded.SelectAll
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import io.github.baldborne94.baldmusic.core.library.LetterIndex
import io.github.baldborne94.baldmusic.core.library.LibraryNode.Kind
import io.github.baldborne94.baldmusic.core.library.MediaId
import io.github.baldborne94.baldmusic.core.library.PersonalSource
import io.github.baldborne94.baldmusic.playback.group
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
    // Una raccolta è una playlist nata da una cartella del telefono: si modifica allo stesso modo.
    val isCollection = playlistId?.let { pid -> lib.playlists.firstOrNull { it.id == pid }?.isCollection } == true
    val kind = node?.kind
    val list = items.orEmpty()
    val tracks = list.filter { it.kind == Kind.TRACK }
    val containers = list.filter { it.kind != Kind.TRACK && it.kind != Kind.MESSAGE }
    val hero = tracks.isNotEmpty() || kind in setOf(Kind.ALBUM, Kind.ARTIST, Kind.PLAYLIST, Kind.FOLDER) || likedPage || playlistId != null
    val shownTitle = node?.title()?.takeIf { it.isNotBlank() } ?: title

    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    // Selezione di più brani (pressione lunga o "Seleziona brani").
    var selecting by remember(mediaId) { mutableStateOf(false) }
    val selected = remember(mediaId) { mutableStateListOf<String>() }
    fun toggle(item: MediaItem) {
        if (!selected.remove(item.mediaId)) selected.add(item.mediaId)
    }
    // Riordino trascinando: si sposta una copia della lista, e si salva quando si lascia.
    var reordering by remember(mediaId) { mutableStateOf(false) }
    val order = remember(mediaId) { mutableStateListOf<MediaItem>() }
    var dragging by remember(mediaId) { mutableStateOf<String?>(null) }
    var dragOffset by remember(mediaId) { mutableFloatStateOf(0f) }
    var rowHeight by remember { mutableFloatStateOf(1f) }
    LaunchedEffect(tracks, reordering) {
        if (dragging == null) {
            order.clear()
            order.addAll(tracks)
        }
    }
    BackHandler(enabled = selecting || reordering) {
        selecting = false
        selected.clear()
        reordering = false
    }

    val artworkColor by rememberArtworkColor(node?.mediaMetadata?.artworkUri ?: tracks.firstOrNull()?.mediaMetadata?.artworkUri, tileColor(mediaId))
    val headerColor = when {
        likedPage -> Color(0xFF4A2DB0)
        else -> artworkColor
    }

    // Le lettere a lato: per album e artisti, o per i brani ("Tutti i brani"), sopra c'è la testata.
    val gridState = rememberLazyGridState()
    val letters = remember(containers, tracks, selecting, reordering, kind) {
        LetterIndex.entries(containers.map { it.group }, before = 1).ifEmpty {
            if (reordering) emptyList()
            else {
                val discography = if (kind == Kind.ARTIST && containers.isNotEmpty() && containers.none { it.group != null }) 1 else 0
                val above = 1 + containers.size + LetterIndex.headers(containers.map { it.group }) + discography + if (selecting) 1 else 0
                LetterIndex.entries(tracks.map { it.group }, before = above)
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(160.dp),
            modifier = Modifier.fillMaxSize(),
            state = gridState,
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
                        menuItems = buildList {
                            if (tracks.isNotEmpty()) add("Seleziona brani" to { reordering = false; selecting = true })
                            if (playlistId != null && tracks.size > 1) {
                                add("Riordina brani" to { selecting = false; selected.clear(); reordering = true })
                                add("Ordina per titolo" to { vm.sortPlaylist(playlistId, byArtist = false) })
                                add("Ordina per artista" to { vm.sortPlaylist(playlistId, byArtist = true) })
                            }
                        },
                        onRename = if (playlistId != null) ({ renaming = true }) else null,
                        onDelete = if (playlistId != null) ({ confirmDelete = true }) else null,
                        // Solo le raccolte: buttare i file di una playlist (magari col server dentro) non avrebbe senso.
                        onTrashAll = ui.trash?.takeIf { isCollection && playlistId != null && tracks.any { localFileUri(it) != null } }?.let { trash ->
                            {
                                val files = tracks.filter { localFileUri(it) != null }
                                trash(files, "Eliminare “$shownTitle” e i suoi ${if (files.size == 1) "brano" else "${files.size} brani"}?", null) {
                                    vm.deleteCollectionWithFiles(playlistId!!)
                                    ui.back()
                                }
                            }
                        },
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
            // Le intestazioni (lettera, tipo di uscita) vengono dal gruppo di ogni elemento, come in Android Auto.
            if (containers.isNotEmpty()) {
                if (kind == Kind.ARTIST) {
                    if (containers.none { it.group != null }) item(span = full) { SectionHeader("Discografia") }
                    containers.forEachIndexed { i, c ->
                        groupHeader(containers, i, full)
                        item(key = c.mediaId, span = full) {
                            ContainerRow(c, subtitle = if (c.kind == Kind.SECTION) c.subtitle() else c.mediaMetadata.recordingYear?.toString()) { ui.open(c.mediaId, c.title()) }
                        }
                    }
                } else if (containers.all { it.kind == Kind.SECTION || it.kind == Kind.FOLDER }) {
                    containers.forEachIndexed { i, c ->
                        groupHeader(containers, i, full)
                        item(key = c.mediaId, span = full) { ContainerRow(c) { ui.open(c.mediaId, c.title()) } }
                    }
                } else {
                    containers.forEachIndexed { i, c ->
                        groupHeader(containers, i, full)
                        // Una sezione in mezzo alla griglia (es. "I tuoi più ascoltati" tra gli artisti) va su una riga sua.
                        if (c.kind == Kind.SECTION) {
                            item(key = c.mediaId, span = full) { ContainerRow(c) { ui.open(c.mediaId, c.title()) } }
                        } else {
                            item(key = c.mediaId) { Box(Modifier.padding(horizontal = 6.dp)) { GridCard(c) { ui.open(c.mediaId, c.title()) } } }
                        }
                    }
                }
            }

            val numbered = id?.kind == "album"
            if (selecting) {
                item(span = full) {
                    val chosen = tracks.filter { it.mediaId in selected }
                    Row(Modifier.fillMaxWidth().background(Surface2).padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { selecting = false; selected.clear() }) { Icon(Icons.Rounded.Close, "Esci dalla selezione") }
                        Text(if (chosen.size == 1) "1 selezionato" else "${chosen.size} selezionati", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                        IconButton(onClick = {
                            if (chosen.size == tracks.size) selected.clear() else { selected.clear(); selected.addAll(tracks.map { it.mediaId }) }
                        }) { Icon(Icons.Rounded.SelectAll, "Seleziona tutti") }
                        IconButton(enabled = chosen.isNotEmpty(), onClick = { ui.addToPlaylist(chosen) }) {
                            Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, "Aggiungi a una playlist o raccolta")
                        }
                        if (playlistId != null) {
                            IconButton(enabled = chosen.isNotEmpty(), onClick = { vm.removeFromPlaylist(playlistId, chosen); selected.clear() }) {
                                Icon(Icons.Rounded.RemoveCircleOutline, if (isCollection) "Togli dalla raccolta" else "Togli dalla playlist")
                            }
                        }
                        val trash = ui.trash
                        if (trash != null && chosen.any { localFileUri(it) != null }) {
                            IconButton(onClick = {
                                val files = chosen.filter { localFileUri(it) != null }
                                trash(files, "Eliminare ${if (files.size == 1) "1 brano" else "${files.size} brani"} dal telefono?", null) {
                                    selected.clear()
                                    selecting = false
                                }
                            }) { Icon(Icons.Rounded.Delete, "Elimina dal telefono", tint = Danger) }
                        }
                    }
                }
            }
            if (reordering && playlistId != null) {
                item(span = full) {
                    Row(Modifier.fillMaxWidth().background(Surface2).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Trascina ≡ per spostare un brano", Modifier.weight(1f), color = TextMuted, fontSize = 14.sp)
                        androidx.compose.material3.TextButton(onClick = { reordering = false }) { Text("Fatto", color = Accent, fontWeight = FontWeight.Bold) }
                    }
                }
                items(order, key = { it.mediaId }, span = { GridItemSpan(maxLineSpan) }) { item ->
                    val moving = dragging == item.mediaId
                    ReorderRow(
                        item,
                        Modifier
                            .zIndex(if (moving) 1f else 0f)
                            .graphicsLayer { translationY = if (moving) dragOffset else 0f }
                            .onSizeChanged { rowHeight = it.height.toFloat().coerceAtLeast(1f) },
                        onDragStart = { dragging = item.mediaId; dragOffset = 0f },
                        onDrag = { dy ->
                            dragOffset += dy
                            val i = order.indexOfFirst { it.mediaId == item.mediaId }
                            // Superata metà della riga vicina, il brano prende il suo posto.
                            if (dragOffset > rowHeight / 2 && i in 0 until order.lastIndex) {
                                order.add(i + 1, order.removeAt(i))
                                dragOffset -= rowHeight
                            } else if (dragOffset < -rowHeight / 2 && i > 0) {
                                order.add(i - 1, order.removeAt(i))
                                dragOffset += rowHeight
                            }
                        },
                        onDragEnd = {
                            dragging = null
                            dragOffset = 0f
                            vm.reorderPlaylist(playlistId, order.toList())
                        },
                    )
                }
            } else {
                tracks.forEachIndexed { i, item ->
                    // "Disco 2" in un album, la lettera in "Tutti i brani".
                    groupHeader(tracks, i, full)
                    item(key = item.mediaId, span = full) {
                        TrackRow(
                            item,
                            active = sameTrack(item.mediaId, vm.current?.mediaId),
                            liked = lib.isLiked(item.mediaId),
                            number = if (numbered) item.mediaMetadata.trackNumber else null,
                            onClick = { if (selecting) toggle(item) else vm.play(item, from = shownTitle) },
                            onMenu = { ui.trackMenu(item, playlistId) },
                            onSwipeQueue = { vm.enqueue(item) },
                            selected = if (selecting) item.mediaId in selected else null,
                            // Pressione lunga: si comincia a selezionare (il menu resta sul ⋮).
                            onLongClick = {
                                selecting = true
                                reordering = false
                                toggle(item)
                            },
                        )
                    }
                }
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
        LetterRail(letters, gridState.isScrollInProgress, onJump = { gridState.scrollToItem(it) }, Modifier.align(Alignment.CenterEnd).padding(end = 4.dp))
    }

    if (renaming && playlistId != null) {
        NewPlaylistDialog(title = if (isCollection) "Rinomina raccolta" else "Rinomina playlist", initial = shownTitle, confirm = "Salva", onDismiss = { renaming = false }) { name ->
            vm.renamePlaylist(playlistId, name)
            renaming = false
        }
    }
    if (confirmDelete && playlistId != null) {
        ConfirmDialog(
            title = "Eliminare “$shownTitle”?",
            text = if (isCollection) "La raccolta sparirà dalla libreria e non verrà ricreata. I file restano sul telefono, nella loro cartella."
            else "La playlist sparirà dalla libreria. I brani restano dove sono.",
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
    menuItems: List<Pair<String, () -> Unit>> = emptyList(),
    onRename: (() -> Unit)?,
    onDelete: (() -> Unit)?,
    onTrashAll: (() -> Unit)? = null,
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
            if (menuItems.isNotEmpty() || onRename != null || onDelete != null || onTrashAll != null) {
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "Altro", tint = TextMuted) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        menuItems.forEach { (label, action) -> DropdownMenuItem(text = { Text(label) }, onClick = { menu = false; action() }) }
                        onRename?.let { DropdownMenuItem(text = { Text("Rinomina") }, leadingIcon = { Icon(Icons.Rounded.Edit, null) }, onClick = { menu = false; it() }) }
                        onDelete?.let { DropdownMenuItem(text = { Text("Elimina") }, leadingIcon = { Icon(Icons.Rounded.Delete, null) }, onClick = { menu = false; it() }) }
                        onTrashAll?.let {
                            DropdownMenuItem(
                                text = { Text("Elimina raccolta e brani dal telefono", color = Danger) },
                                leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = Danger) },
                                onClick = { menu = false; it() },
                            )
                        }
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

/** Riga di un brano mentre si riordina: si sposta trascinando la maniglia ≡. */
@Composable
private fun ReorderRow(item: MediaItem, modifier: Modifier, onDragStart: () -> Unit, onDrag: (Float) -> Unit, onDragEnd: () -> Unit) {
    val start by rememberUpdatedState(onDragStart)
    val drag by rememberUpdatedState(onDrag)
    val end by rememberUpdatedState(onDragEnd)
    Row(
        modifier.fillMaxWidth().background(Background).padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(item, Modifier.size(50.dp), shape = RoundedCornerShape(4.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title(), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Text(item.subtitle().orEmpty(), color = TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
        }
        Icon(
            Icons.Rounded.DragHandle,
            "Trascina per spostare",
            tint = TextMuted,
            modifier = Modifier
                .size(48.dp)
                .pointerInput(item.mediaId) {
                    detectDragGestures(
                        onDragStart = { start() },
                        onDragEnd = { end() },
                        onDragCancel = { end() },
                    ) { change, amount ->
                        change.consume()
                        drag(amount.y)
                    }
                }
                .padding(12.dp),
        )
    }
}

/** Intestazione prima dell'elemento [i] se il suo gruppo è diverso da quello di prima. */
private fun androidx.compose.foundation.lazy.grid.LazyGridScope.groupHeader(
    list: List<MediaItem>,
    i: Int,
    full: androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope.() -> GridItemSpan,
) {
    val group = list[i].group ?: return
    if (i > 0 && list[i - 1].group == group) return
    item(key = "gruppo-$i-$group", span = full) { SectionHeader(group) }
}
