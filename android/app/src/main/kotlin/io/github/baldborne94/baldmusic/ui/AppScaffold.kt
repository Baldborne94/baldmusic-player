package io.github.baldborne94.baldmusic.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import io.github.baldborne94.baldmusic.core.library.LocalCatalog
import io.github.baldborne94.baldmusic.core.library.MediaId
import io.github.baldborne94.baldmusic.core.library.PersonalSource
import io.github.baldborne94.baldmusic.playback.albumId
import io.github.baldborne94.baldmusic.playback.artistId

enum class Tab { HOME, SEARCH, LIBRARY }

sealed interface Screen {
    data object Root : Screen
    data class Detail(val mediaId: String, val title: String) : Screen
    data object Settings : Screen
    data object Stats : Screen
    data object Cleanup : Screen
    data object Trash : Screen
    data object Health : Screen
}

/** Le azioni comuni che ogni schermata può chiedere, senza passarle di mano in mano. */
data class Ui(
    val open: (mediaId: String, title: String) -> Unit,
    val trackMenu: (item: MediaItem, playlistId: String?) -> Unit,
    val addToPlaylist: (items: List<MediaItem>) -> Unit,
    val settings: () -> Unit,
    val back: () -> Unit,
    val push: (Screen) -> Unit,
    /**
     * Sposta nel cestino di Android i file dei brani del telefono tra [items], dopo una
     * conferma; [onDone] solo se sono stati buttati davvero. Null dove non si può (prima
     * di Android 11 non c'è il cestino).
     */
    val trash: ((items: List<MediaItem>, title: String, note: String?, onDone: () -> Unit) -> Unit)?,
)

/** Una richiesta di cestino in attesa delle conferme; [note] sostituisce la frase finale. */
private class TrashAsk(val items: List<MediaItem>, val title: String, val note: String?, val onDone: () -> Unit)

/** Il file di un brano del telefono, per MediaStore; null per gli altri brani. */
fun localFileUri(item: MediaItem): android.net.Uri? =
    MediaId.parse(item.mediaId)?.takeIf { it.source == LocalCatalog.KEY && it.isTrack }?.id?.toLongOrNull()
        ?.let { android.content.ContentUris.withAppendedId(android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, it) }

val LocalUi = staticCompositionLocalOf<Ui> { error("Ui non fornita") }

@Composable
fun AppScaffold(vm: PlayerViewModel) {
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    val stacks = remember { Tab.entries.associateWith { mutableStateListOf<Screen>(Screen.Root) } }
    val stack = stacks.getValue(tab)
    var nowPlaying by rememberSaveable { mutableStateOf(false) }
    var queueOpen by rememberSaveable { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<Pair<MediaItem, String?>?>(null) }
    var addTo by remember { mutableStateOf<List<MediaItem>?>(null) }
    var infoFor by remember { mutableStateOf<Pair<Long, String>?>(null) }
    var trashAsk by remember { mutableStateOf<TrashAsk?>(null) }
    var trashing by remember { mutableStateOf<TrashAsk?>(null) }
    val lib by vm.library.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    // La finestra di Android "Spostare nel cestino?": l'app non può buttare file da sola.
    val trashLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        val done = trashing
        trashing = null
        if (done != null && result.resultCode == android.app.Activity.RESULT_OK) {
            // Prima chi ha chiesto (i doppioni passano alla copia tenuta), poi si dimentica il resto.
            done.onDone()
            vm.forgetTrashed(done.items)
        }
    }
    val canTrash = android.os.Build.VERSION.SDK_INT >= 30

    val ui = remember(tab) {
        Ui(
            open = { id, title -> stacks.getValue(tab).add(Screen.Detail(id, title)) },
            trackMenu = { item, playlist -> menuFor = item to playlist },
            addToPlaylist = { items -> addTo = items },
            settings = { stacks.getValue(tab).add(Screen.Settings) },
            back = { stacks.getValue(tab).let { if (it.size > 1) it.removeAt(it.lastIndex) } },
            push = { stacks.getValue(tab).add(it) },
            trash = if (canTrash) { items, title, note, onDone -> trashAsk = TrashAsk(items.filter { localFileUri(it) != null }, title, note, onDone) } else null,
        )
    }

    BackHandler(enabled = nowPlaying || queueOpen || stack.size > 1 || tab != Tab.HOME) {
        when {
            queueOpen -> queueOpen = false
            nowPlaying -> nowPlaying = false
            stack.size > 1 -> stack.removeAt(stack.lastIndex)
            else -> tab = Tab.HOME
        }
    }

    val selectTab: (Tab) -> Unit = { selected ->
        // Toccare di nuovo la scheda attiva riporta alla sua radice, come in Spotify.
        if (selected == tab) stacks.getValue(tab).let { while (it.size > 1) it.removeAt(it.lastIndex) }
        tab = selected
    }

    CompositionLocalProvider(LocalUi provides ui) {
        BoxWithConstraints(Modifier.fillMaxSize().background(Background)) {
            // Tablet: le schede in una barra a sinistra; in orizzontale anche la coda fissa a destra.
            val wide = maxWidth >= 600.dp
            val sideQueue = maxWidth >= 900.dp && vm.current != null
            // In orizzontale la libreria resta a sinistra e quello che apri compare accanto, come sul PC.
            val content = maxWidth - (if (wide) 88.dp else 0.dp) - (if (sideQueue) 341.dp else 0.dp)
            val split = tab == Tab.LIBRARY && content >= 760.dp
            Row(Modifier.fillMaxSize()) {
                if (wide) NavRail(tab, selectTab)
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        if (split) {
                            Row(Modifier.fillMaxSize()) {
                                // Dalla libreria a sinistra si sceglie cosa vedere a destra: non si accumula una pila.
                                val pick = remember(ui) {
                                    ui.copy(open = { id, title ->
                                        stacks.getValue(Tab.LIBRARY).let { s ->
                                            while (s.size > 1) s.removeAt(s.lastIndex)
                                            s.add(Screen.Detail(id, title))
                                        }
                                    })
                                }
                                Box(Modifier.width(340.dp).fillMaxHeight()) {
                                    CompositionLocalProvider(LocalUi provides pick) { LibraryScreen(vm) }
                                }
                                Box(Modifier.width(1.dp).fillMaxHeight().background(Surface2))
                                Box(Modifier.weight(1f).fillMaxHeight()) {
                                    when (val screen = stack.last()) {
                                        Screen.Root -> SplitPlaceholder()
                                        else -> ScreenContent(vm, tab, screen)
                                    }
                                }
                            }
                        } else {
                            ScreenContent(vm, tab, stack.last())
                        }
                    }
                    MiniPlayer(vm, liked = vm.isLiked(lib, vm.current), onOpen = { nowPlaying = true })
                    if (wide) Spacer(Modifier.navigationBarsPadding()) else BottomBar(tab, selectTab)
                }
                if (sideQueue) {
                    Box(Modifier.width(1.dp).fillMaxHeight().background(Surface2))
                    SidePanel(vm, Modifier.width(340.dp).fillMaxHeight())
                }
            }

            AnimatedVisibility(
                visible = nowPlaying && vm.current != null,
                enter = slideInVertically(tween(320)) { it } + fadeIn(),
                exit = slideOutVertically(tween(280)) { it } + fadeOut(),
            ) {
                NowPlayingScreen(
                    vm,
                    onClose = { nowPlaying = false },
                    onOpenScreen = { screen ->
                        nowPlaying = false
                        stack.add(screen)
                    },
                    onQueue = { queueOpen = true },
                    onNavigate = { id, title ->
                        nowPlaying = false
                        stack.add(Screen.Detail(id, title))
                    },
                )
            }

            AnimatedVisibility(visible = queueOpen, enter = slideInVertically { it }, exit = slideOutVertically { it }) {
                QueueScreen(vm, onClose = { queueOpen = false })
            }

            menuFor?.let { (item, playlistId) ->
                val liked = vm.isLiked(lib, item)
                TrackActionsSheet(
                    item = item,
                    liked = liked,
                    onDismiss = { menuFor = null },
                    onToggleLike = { vm.toggleLike(item) },
                    onAddToPlaylist = { addTo = listOf(item) },
                    onPlayNext = { vm.playNext(item) },
                    onEnqueue = { vm.enqueue(item) },
                    onGoAlbum = item.albumId?.let { id -> { nowPlaying = false; stack.add(Screen.Detail(id, item.mediaMetadata.albumTitle?.toString().orEmpty())) } },
                    onGoArtist = item.artistId?.let { id -> { nowPlaying = false; stack.add(Screen.Detail(id, item.mediaMetadata.artist?.toString().orEmpty())) } },
                    onRemoveFromPlaylist = playlistId?.let { pl -> { vm.removeFromPlaylist(pl, item) } },
                    removeLabel = if (lib.playlists.firstOrNull { it.id == playlistId }?.isCollection == true) "Togli da questa raccolta" else "Rimuovi da questa playlist",
                    onRadio = if (vm.canStartRadio(item)) ({ vm.startRadio(item) }) else null,
                    onShare = { shareTrack(context, item) },
                    // Solo per i brani del telefono: i tag del server li legge Navidrome.
                    onTrash = ui.trash?.takeIf { localFileUri(item) != null }?.let { trash ->
                        { trash(listOf(item), "Eliminare “${item.title()}” dal telefono?", null) {} }
                    },
                    onInfo = MediaId.parse(item.mediaId)?.takeIf { it.source == LocalCatalog.KEY && it.isTrack }?.id?.toLongOrNull()
                        ?.let { id -> { infoFor = id to item.title() } },
                )
            }

            infoFor?.let { (id, title) -> TrackInfoDialog(id, title, onDismiss = { infoFor = null }) }

            // Dopo un aggiornamento, una volta: cosa c'è di nuovo.
            WhatsNewDialog(vm)

            trashAsk?.let { ask ->
                val n = ask.items.size
                ConfirmDialog(
                    title = ask.title,
                    text = (if (n == 1) "Il file va nel cestino di Android" else "I $n file vanno nel cestino di Android") +
                        ": per 30 giorni li recuperi dall'app File (Cestino), poi vengono cancellati. " +
                        (ask.note ?: "Spariscono da raccolte, preferiti e playlist."),
                    confirm = "Sposta nel cestino",
                    onDismiss = { trashAsk = null },
                ) {
                    trashAsk = null
                    val uris = ask.items.mapNotNull(::localFileUri)
                    if (uris.isNotEmpty() && android.os.Build.VERSION.SDK_INT >= 30) {
                        val request = android.provider.MediaStore.createTrashRequest(context.contentResolver, uris, true)
                        trashing = ask
                        trashLauncher.launch(androidx.activity.result.IntentSenderRequest.Builder(request.intentSender).build())
                    }
                }
            }

            addTo?.let { items ->
                AddToPlaylistSheet(lib, onDismiss = { addTo = null }, onCreate = { name -> vm.createPlaylist(name, items) }, onPick = { id -> vm.addToPlaylist(id, items) })
            }
        }
    }
}

/** La schermata in cima alla pila di una scheda. */
@Composable
private fun ScreenContent(vm: PlayerViewModel, tab: Tab, screen: Screen) {
    when (screen) {
        Screen.Root -> when (tab) {
            Tab.HOME -> HomeScreen(vm)
            Tab.SEARCH -> SearchScreen(vm)
            Tab.LIBRARY -> LibraryScreen(vm)
        }
        is Screen.Detail -> DetailScreen(vm, screen.mediaId, screen.title)
        Screen.Settings -> SettingsPage(vm)
        Screen.Stats -> StatsScreen(vm)
        Screen.Cleanup -> CleanupScreen(vm)
        Screen.Trash -> TrashScreen(vm)
        Screen.Health -> HealthScreen(vm)
    }
}

/** La parte destra della libreria affiancata, finché non si sceglie niente. */
@Composable
private fun SplitPlaceholder() {
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Outlined.LibraryMusic, null, tint = TextMuted, modifier = Modifier.size(64.dp))
        Spacer(Modifier.height(16.dp))
        Text("Scegli una raccolta, una playlist, un album o un artista dalla libreria.", color = TextMuted, textAlign = TextAlign.Center)
    }
}

private enum class Side { QUEUE, LYRICS }

/** Il pannello fisso a destra del tablet in orizzontale: la coda o il testo del brano. */
@Composable
private fun SidePanel(vm: PlayerViewModel, modifier: Modifier) {
    var side by rememberSaveable { mutableStateOf(Side.QUEUE) }
    Column(modifier.background(Background).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Coda", selected = side == Side.QUEUE) { side = Side.QUEUE }
            Chip("Testo", selected = side == Side.LYRICS) { side = Side.LYRICS }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (side) {
                Side.QUEUE -> QueueScreen(vm, onClose = null)
                Side.LYRICS -> SideLyrics(vm)
            }
        }
    }
}

/** Le schede del tablet: una barra verticale a sinistra al posto di quella in basso. */
@Composable
private fun NavRail(tab: Tab, onSelect: (Tab) -> Unit) {
    Column(
        Modifier
            .fillMaxHeight()
            .width(88.dp)
            .background(Color.Black)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(top = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        RailItem("Home", Icons.Rounded.Home, Icons.Outlined.Home, tab == Tab.HOME) { onSelect(Tab.HOME) }
        RailItem("Cerca", Icons.Rounded.Search, Icons.Outlined.Search, tab == Tab.SEARCH) { onSelect(Tab.SEARCH) }
        RailItem("Libreria", Icons.Rounded.LibraryMusic, Icons.Outlined.LibraryMusic, tab == Tab.LIBRARY) { onSelect(Tab.LIBRARY) }
    }
}

@Composable
private fun RailItem(label: String, selectedIcon: ImageVector, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(if (selected) selectedIcon else icon, null, tint = if (selected) Color.White else TextMuted, modifier = Modifier.size(28.dp))
        Text(label, fontSize = 12.sp, color = if (selected) Color.White else TextMuted, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
    }
}

@Composable
private fun BottomBar(tab: Tab, onSelect: (Tab) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Background.copy(alpha = 0.92f), Color.Black)))
            .navigationBarsPadding()
            .height(64.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NavItem("Home", Icons.Rounded.Home, Icons.Outlined.Home, tab == Tab.HOME) { onSelect(Tab.HOME) }
        NavItem("Cerca", Icons.Rounded.Search, Icons.Outlined.Search, tab == Tab.SEARCH) { onSelect(Tab.SEARCH) }
        NavItem("La tua libreria", Icons.Rounded.LibraryMusic, Icons.Outlined.LibraryMusic, tab == Tab.LIBRARY) { onSelect(Tab.LIBRARY) }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.NavItem(label: String, selectedIcon: ImageVector, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.weight(1f).clickable(onClick = onClick).padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(if (selected) selectedIcon else icon, null, tint = if (selected) Color.White else TextMuted, modifier = Modifier.size(26.dp))
        Text(label, fontSize = 11.sp, color = if (selected) Color.White else TextMuted, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
    }
}

/**
 * Il mini-player sopra la barra: colorato con la copertina, cuore, play/pausa.
 * Si scorre di lato per cambiare brano e si tocca per aprire il player.
 */
@Composable
fun MiniPlayer(vm: PlayerViewModel, liked: Boolean, onOpen: () -> Unit) {
    val item = vm.current ?: return
    val color by rememberArtworkColor(item.mediaMetadata.artworkUri)
    var drag by remember { mutableStateOf(0f) }
    Column(
        Modifier
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(color)
            .clickable(onClick = onOpen)
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (drag < -120) vm.next() else if (drag > 120) vm.previous()
                        drag = 0f
                    },
                ) { _, delta -> drag += delta }
            },
    ) {
        Row(Modifier.fillMaxWidth().padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Artwork(item, Modifier.size(42.dp), shape = RoundedCornerShape(4.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(item.title(), fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(item.subtitle().orEmpty(), color = Color.White.copy(alpha = 0.75f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = { vm.toggleLike(item) }) {
                Icon(if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, if (liked) "Togli da Mi piace" else "Mi piace", tint = if (liked) Accent else Color.White)
            }
            IconButton(onClick = { vm.toggle() }) {
                when {
                    vm.buffering -> CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp)
                    vm.isPlaying -> Icon(Icons.Rounded.Pause, "Pausa", tint = Color.White, modifier = Modifier.size(30.dp))
                    else -> Icon(Icons.Rounded.PlayArrow, "Riproduci", tint = Color.White, modifier = Modifier.size(30.dp))
                }
            }
        }
        val progress = if (vm.duration > 0) (vm.position.toFloat() / vm.duration).coerceIn(0f, 1f) else 0f
        Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp).height(2.dp).clip(RoundedCornerShape(1.dp)).background(Color.White.copy(alpha = 0.25f))) {
            Box(Modifier.fillMaxWidth(progress).height(2.dp).background(Color.White))
        }
        Spacer(Modifier.height(2.dp))
    }
}

/** L'ID di una playlist tua da un ID di contenitore, se lo è. */
fun personalPlaylistId(mediaId: String): String? =
    MediaId.parse(mediaId)?.takeIf { it.source == PersonalSource.KEY && it.kind == PersonalSource.PLAYLIST }?.id

/** Condivide titolo e artista con le altre app (messaggi, social…). */
fun shareTrack(context: android.content.Context, item: MediaItem) {
    val text = listOfNotNull(item.title().takeIf { it.isNotBlank() }, item.mediaMetadata.artist?.toString()).joinToString(" – ")
    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(android.content.Intent.EXTRA_TEXT, "🎵 $text")
    }
    context.startActivity(android.content.Intent.createChooser(send, "Condividi").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
}
