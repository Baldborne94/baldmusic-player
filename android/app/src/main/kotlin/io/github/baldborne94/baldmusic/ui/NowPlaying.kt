package io.github.baldborne94.baldmusic.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.CloseFullscreen
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Forward30
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import io.github.baldborne94.baldmusic.BaldMusicApp
import io.github.baldborne94.baldmusic.core.library.AppTheme
import io.github.baldborne94.baldmusic.core.lyrics.Lyrics
import io.github.baldborne94.baldmusic.core.podcast.PodcastSource
import io.github.baldborne94.baldmusic.playback.albumId
import io.github.baldborne94.baldmusic.playback.artistId
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingScreen(
    vm: PlayerViewModel,
    onClose: () -> Unit,
    onQueue: () -> Unit,
    onNavigate: (String, String) -> Unit,
    onOpenScreen: (Screen) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val item = vm.current ?: return
    val ui = LocalUi.current
    val lib by vm.library.collectAsStateWithLifecycle()
    val sleepEnd by vm.sleepTimer.collectAsStateWithLifecycle()
    val color by rememberArtworkColor(item.mediaMetadata.artworkUri)
    val liked = vm.isLiked(lib, item)
    var showLyrics by rememberSaveable { mutableStateOf(false) }
    // Col testo aperto il tasto indietro lo riduce, invece di chiudere il lettore.
    androidx.activity.compose.BackHandler(enabled = showLyrics) { showLyrics = false }
    // Mentre leggi il testo lo schermo copertina non si apre da solo.
    androidx.compose.runtime.DisposableEffect(showLyrics) {
        vm.reading = showLyrics
        onDispose { vm.reading = false }
    }
    var sleepSheet by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf(false) }
    // Una puntata di un podcast: −15/+30 al posto di casuale e ripeti, informazioni sulla puntata.
    val episode = remember(item.mediaId) { PodcastSource.episodeKey(item.mediaId) != null }
    var dragY by remember { mutableFloatStateOf(0f) }

    // Testo: si cerca a ogni cambio di brano, dalla cache o da LRCLIB.
    var lyrics by remember(item.mediaId) { mutableStateOf<Lyrics?>(null) }
    var lyricsLoading by remember(item.mediaId) { mutableStateOf(true) }
    LaunchedEffect(item.mediaId) {
        lyricsLoading = true
        lyrics = runCatching { vm.lyricsFor(item) }.getOrNull()
        lyricsLoading = false
    }

    Box(
        Modifier
            .fillMaxSize()
            .offset { IntOffset(0, dragY.coerceAtLeast(0f).roundToInt()) }
            .then(
                // Nel pop: giallo a puntini come i fumetti; nello scuro il colore della copertina.
                if (isPop) Modifier.popDots()
                else Modifier.background(Brush.verticalGradient(0f to color, 0.65f to androidx.compose.ui.graphics.lerp(color, Background, 0.75f), 1f to Background)),
            )
            // Assorbe i tocchi: non devono arrivare alla schermata sotto.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .pointerInput(Unit) {
                // Trascinare in giù chiude il player.
                detectVerticalDragGestures(
                    onDragEnd = {
                        if (dragY > 260) onClose()
                        dragY = 0f
                    },
                    onDragCancel = { dragY = 0f },
                ) { _, delta -> dragY = (dragY + delta).coerceAtLeast(0f) }
            },
    ) {
        // Sul tablet i comandi restano in una colonna centrale, non larghi quanto lo schermo.
        Column(
            Modifier.align(Alignment.TopCenter).widthIn(max = 640.dp).fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Barra in alto: chiudi, da dove arriva, menu.
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.Rounded.KeyboardArrowDown, "Chiudi", Modifier.size(32.dp)) }
                // La playlist o raccolta da cui viene il brano: toccandola si torna alla sua pagina.
                val fromTitle = vm.playingFrom ?: item.mediaMetadata.albumTitle?.toString().orEmpty()
                val fromPage = io.github.baldborne94.baldmusic.core.library.PlayingFrom.container(item.mediaId, vm.playingContainer)
                val goToSource: (() -> Unit)? = fromPage?.let { page ->
                    { onNavigate(page, if (page == vm.playingContainer) fromTitle else "") }
                }
                Column(
                    Modifier.weight(1f).then(if (goToSource != null) Modifier.clickable(onClickLabel = "Apri la pagina", onClick = goToSource) else Modifier),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("IN RIPRODUZIONE DA", fontSize = 10.sp, letterSpacing = 1.sp, color = PlayerText.copy(alpha = 0.75f))
                    Text(fromTitle, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "Altro") }
                    androidx.compose.material3.DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        goToSource?.let { go ->
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text(if (fromTitle.isNotBlank()) "Vai a «$fromTitle»" else "Vai alla playlist") },
                                leadingIcon = { Icon(Icons.AutoMirrored.Rounded.QueueMusic, null) },
                                onClick = { menu = false; go() },
                            )
                        }
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("Aggiungi a playlist") },
                            leadingIcon = { Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, null) },
                            onClick = { menu = false; ui.addToPlaylist(listOf(item)) },
                        )
                        item.albumId?.let { id ->
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text("Vai all'album") },
                                leadingIcon = { Icon(Icons.Rounded.Album, null) },
                                onClick = { menu = false; onNavigate(id, item.mediaMetadata.albumTitle?.toString().orEmpty()) },
                            )
                        }
                        item.artistId?.let { id ->
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text("Vai all'artista") },
                                leadingIcon = { Icon(Icons.Rounded.Person, null) },
                                onClick = { menu = false; onNavigate(id, item.mediaMetadata.artist?.toString().orEmpty()) },
                            )
                        }
                        if (vm.canStartRadio(item)) {
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text("Avvia radio") },
                                leadingIcon = { Icon(Icons.Rounded.Radio, null) },
                                onClick = { menu = false; vm.startRadio(item) },
                            )
                        }
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("Condividi") },
                            leadingIcon = { Icon(Icons.Rounded.Share, null) },
                            onClick = { menu = false; shareTrack(context, item) },
                        )
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("Schermo copertina") },
                            leadingIcon = { Icon(Icons.Rounded.Fullscreen, null) },
                            onClick = { menu = false; vm.openCover(context) },
                        )
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("Timer di spegnimento") },
                            leadingIcon = { Icon(Icons.Rounded.Timer, null) },
                            onClick = { menu = false; sleepSheet = true },
                        )
                        // Il file del brano del server nel cestino del NAS (File Browser), dopo la conferma.
                        ui.trashOnNas?.takeIf { vm.isServerTrack(item) }?.let { ask ->
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text("Elimina dal NAS", color = Danger) },
                                leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = Danger) },
                                onClick = { menu = false; ask(listOf(item)) },
                            )
                        }
                        // Solo per i brani del telefono: i tag del server li legge Navidrome.
                        if (localTrackId(item) != null || episode) {
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text(if (episode) "Informazioni sulla puntata" else "Informazioni sul brano") },
                                leadingIcon = { Icon(Icons.Rounded.Info, null) },
                                onClick = { menu = false; info = true },
                            )
                        }
                    }
                }
            }

            // Copertina (scorri di lato per cambiare brano) oppure il testo a tutto schermo.
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                AnimatedContent(targetState = showLyrics, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "copertina-testo") { lyricsMode ->
                    if (lyricsMode) {
                        // Il testo si riduce da qui (o col tasto indietro), senza chiudere il lettore.
                        Box(Modifier.fillMaxSize()) {
                            LyricsView(vm, lyrics, lyricsLoading, color)
                            val pop = isPop
                            IconButton(
                                onClick = { showLyrics = false },
                                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).size(40.dp)
                                    .popCard(CircleShape, shadow = 2.dp, border = 2.dp).clip(CircleShape)
                                    .background(if (pop) Color.White else Color.Black.copy(alpha = 0.35f)),
                            ) { Icon(Icons.Rounded.CloseFullscreen, "Riduci il testo", tint = if (pop) PlayerText else Color.White) }
                        }
                    } else {
                        SwipeArtwork(vm, item)
                    }
                }
            }

            // Titolo, artista, cuore.
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        item.title(),
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = if (isPop) PopFont else null,
                        maxLines = 1,
                        modifier = Modifier.basicMarquee(),
                    )
                    Text(
                        item.mediaMetadata.artist?.toString().orEmpty(),
                        fontSize = 16.sp,
                        color = PlayerText.copy(alpha = 0.75f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable(enabled = item.artistId != null) {
                            item.artistId?.let { onNavigate(it, item.mediaMetadata.artist?.toString().orEmpty()) }
                        },
                    )
                }
                LikeButton(liked) { vm.toggleLike(item) }
            }

            // Il testo sotto il titolo, come su Spotify: la riga di adesso e la prossima; tocca per tutto.
            if (!showLyrics) lyrics?.let { LyricsPeek(vm, it) { showLyrics = true } }

            ProgressBar(vm)

            // Comandi.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                // Una puntata: indietro di 15 s e avanti di 30 s al posto di casuale e ripeti.
                if (episode) SkipButton(Icons.Rounded.Replay, "15", "Indietro di 15 secondi") { vm.skipBack() }
                else IconButton(onClick = { vm.toggleShuffle() }) {
                    Icon(Icons.Rounded.Shuffle, "Casuale", tint = if (vm.shuffle) Accent else PlayerText)
                }
                IconButton(onClick = { vm.previous() }, Modifier.size(60.dp)) { Icon(Icons.Rounded.SkipPrevious, "Precedente", Modifier.size(42.dp)) }
                // Nel pop rosso con l'icona bianca e il contorno nero; nello scuro bianco con l'icona nera.
                val playColor = if (isPop) Accent else PlayerText
                val playIcon = if (isPop) Color.White else PlayerOnText
                Box(
                    Modifier.size(72.dp).popCard(CircleShape, shadow = 4.dp, border = 3.dp).clip(CircleShape).background(playColor).clickable { vm.toggle() },
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        vm.buffering -> CircularProgressIndicator(Modifier.size(30.dp), color = playIcon, strokeWidth = 3.dp)
                        vm.isPlaying -> Icon(Icons.Rounded.Pause, "Pausa", tint = playIcon, modifier = Modifier.size(40.dp))
                        else -> Icon(Icons.Rounded.PlayArrow, "Riproduci", tint = playIcon, modifier = Modifier.size(40.dp))
                    }
                }
                IconButton(onClick = { vm.next() }, Modifier.size(60.dp)) { Icon(Icons.Rounded.SkipNext, "Successivo", Modifier.size(42.dp)) }
                if (episode) SkipButton(Icons.Rounded.Forward30, null, "Avanti di 30 secondi") { vm.skipForward() }
                else IconButton(onClick = { vm.cycleRepeat() }) {
                    Icon(
                        if (vm.repeat == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                        "Ripeti",
                        tint = if (vm.repeat != Player.REPEAT_MODE_OFF) Accent else PlayerText,
                    )
                }
            }

            // Riga in basso: timer, testo, coda.
            Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                SleepButton(sleepEnd) { sleepSheet = true }
                Spacer(Modifier.weight(1f))
                // La copertina a tutto schermo, anche col telefono bloccato (come un Kindle).
                IconButton(onClick = { vm.openCover(context) }) { Icon(Icons.Rounded.Fullscreen, "Schermo copertina") }
                IconButton(onClick = { showLyrics = !showLyrics }) {
                    Icon(Icons.Rounded.Mic, "Testo", tint = if (showLyrics) Accent else PlayerText)
                }
                IconButton(onClick = onQueue) { Icon(Icons.AutoMirrored.Rounded.QueueMusic, "Coda") }
            }

            vm.error?.let { Text(it, color = Color(0xFFFFB4B4), fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp)) }
        }
    }

    if (info) {
        if (episode) EpisodeInfoDialog(item.mediaId, onDismiss = { info = false })
        else localTrackId(item)?.let { id -> TrackInfoDialog(id, item.title(), onDismiss = { info = false }) }
    }
    if (sleepSheet) {
        ModalBottomSheet(onDismissRequest = { sleepSheet = false }, sheetState = rememberModalBottomSheetState(), containerColor = Surface1) {
            SleepTimerOptions(sleepEnd) { minutes ->
                vm.setSleepTimer(minutes)
                sleepSheet = false
            }
        }
    }
}

/** La copertina grande: scorrendo di lato passa al brano successivo o precedente. */
@Composable
private fun SwipeArtwork(vm: PlayerViewModel, item: MediaItem) {
    var drag by remember { mutableFloatStateOf(0f) }
    val shown by animateFloatAsState(drag, label = "trascinamento")
    val scale by animateFloatAsState(if (vm.isPlaying) 1f else 0.9f, label = "pausa")
    Box(
        Modifier
            .fillMaxWidth()
            .widthIn(max = 520.dp)
            .aspectRatio(1f)
            .graphicsLayer {
                translationX = shown
                scaleX = scale
                scaleY = scale
                alpha = 1f - (kotlin.math.abs(shown) / 900f).coerceIn(0f, 0.6f)
            }
            .pointerInput(item.mediaId) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (drag < -180) vm.next() else if (drag > 180) vm.previous()
                        drag = 0f
                    },
                    onDragCancel = { drag = 0f },
                ) { _, delta -> drag += delta }
            },
    ) {
        Artwork(item, Modifier.fillMaxSize(), shape = RoundedCornerShape(10.dp))
    }
}

@Composable
private fun LikeButton(liked: Boolean, onClick: () -> Unit) {
    val scale by animateFloatAsState(if (liked) 1.15f else 1f, label = "cuore")
    IconButton(onClick = onClick) {
        Icon(
            if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
            if (liked) "Togli da Mi piace" else "Mi piace",
            tint = if (liked) Accent else PlayerText,
            modifier = Modifier.size(30.dp).graphicsLayer { scaleX = scale; scaleY = scale },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProgressBar(vm: PlayerViewModel) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    val duration = vm.duration.coerceAtLeast(1L).toFloat()
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        val value = (dragging ?: vm.position.toFloat()).coerceIn(0f, duration)
        val onChange: (Float) -> Unit = { dragging = it }
        val onDone = {
            dragging?.let { vm.seekTo(it.toLong()) }
            dragging = null
        }
        if (isPop) {
            // Pop: barra bianca col contorno nero, riempita di rosso; pallino nero bordato di bianco.
            val ink = PlayerText
            val red = Accent
            Slider(
                value = value,
                onValueChange = onChange,
                onValueChangeFinished = onDone,
                valueRange = 0f..duration,
                modifier = Modifier.height(28.dp),
                thumb = {
                    Box(Modifier.size(22.dp).clip(CircleShape).background(ink).border(3.dp, Color.White, CircleShape))
                },
                track = { state ->
                    val range = state.valueRange.endInclusive - state.valueRange.start
                    val fraction = if (range > 0f) ((state.value - state.valueRange.start) / range).coerceIn(0f, 1f) else 0f
                    val shape = RoundedCornerShape(8.dp)
                    Box(Modifier.fillMaxWidth().height(14.dp).clip(shape).background(Color.White)) {
                        Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(red))
                        Box(Modifier.matchParentSize().border(3.dp, ink, shape))
                    }
                },
            )
        } else {
            Slider(
                value = value,
                onValueChange = onChange,
                onValueChangeFinished = onDone,
                valueRange = 0f..duration,
                colors = SliderDefaults.colors(
                    thumbColor = PlayerText,
                    activeTrackColor = PlayerText,
                    inactiveTrackColor = PlayerText.copy(alpha = 0.25f),
                ),
                modifier = Modifier.height(28.dp),
            )
        }
        Row(Modifier.fillMaxWidth()) {
            val pos = (dragging?.toLong() ?: vm.position).coerceAtLeast(0)
            val timeColor = if (isPop) PlayerText else PlayerText.copy(alpha = 0.7f)
            val timeWeight = if (isPop) FontWeight.Bold else FontWeight.Normal
            Text(formatDuration(pos), fontSize = 12.sp, color = timeColor, fontWeight = timeWeight)
            Spacer(Modifier.weight(1f))
            Text("-" + formatDuration((vm.duration - pos).coerceAtLeast(0)), fontSize = 12.sp, color = timeColor, fontWeight = timeWeight)
        }
    }
}

/** Il testo del brano in corso nel pannello fisso del tablet, accanto alla libreria. */
@Composable
fun SideLyrics(vm: PlayerViewModel) {
    val item = vm.current ?: return
    val color by rememberArtworkColor(item.mediaMetadata.artworkUri)
    var lyrics by remember(item.mediaId) { mutableStateOf<Lyrics?>(null) }
    var loading by remember(item.mediaId) { mutableStateOf(true) }
    LaunchedEffect(item.mediaId) {
        loading = true
        lyrics = runCatching { vm.lyricsFor(item) }.getOrNull()
        loading = false
    }
    Box(Modifier.fillMaxSize().padding(12.dp)) { LyricsView(vm, lyrics, loading, color) }
}

/** Due righe del testo sotto il titolo (vedi [Lyrics.preview]); niente se il testo non c'è. */
@Composable
private fun LyricsPeek(vm: PlayerViewModel, lyrics: Lyrics, onOpen: () -> Unit) {
    // Si ridisegna quando cambiano le righe, non a ogni passo della posizione.
    val preview by remember(lyrics) { derivedStateOf { lyrics.preview(vm.position + 300) } }
    val shown = preview ?: return
    // Nel pop un riquadro bianco col contorno nero, come le altre schede; nello scuro un velo scuro.
    val pop = isPop
    val shape = RoundedCornerShape(10.dp)
    Column(
        Modifier.fillMaxWidth().padding(top = 10.dp).popCard(shape, shadow = 3.dp, border = 2.5.dp).clip(shape).clickable(onClick = onOpen)
            .background(if (pop) Color.White else Color.Black.copy(alpha = 0.18f)).padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        if (pop) Text("TESTO", fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp, color = Accent)
        Text(
            shown.current ?: "♪",
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            color = PlayerText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        shown.next?.let {
            Text(it, fontSize = 15.sp, color = PlayerText.copy(alpha = 0.55f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Testo sincronizzato: la riga corrente in evidenza, le altre sfumate; tocca una riga per saltarci. */
@Composable
private fun LyricsView(vm: PlayerViewModel, lyrics: Lyrics?, loading: Boolean, color: Color) {
    // Nel pop un riquadro bianco col contorno nero; nello scuro il colore della copertina scurito,
    // col testo chiaro o scuro secondo quanto è scuro (AppTheme.lightLyrics: mai nero su nero).
    val pop = isPop
    val background = if (pop) Color.White else androidx.compose.ui.graphics.lerp(color, Color.Black, 0.25f)
    val ink = if (AppTheme.lightLyrics(pop, background.luminance())) Color.White else if (pop) PlayerText else Color.Black
    val shape = RoundedCornerShape(12.dp)
    // Nel pop lascia posto all'ombra netta (AnimatedContent taglia quello che esce).
    val room = if (pop) 5.dp else 0.dp
    Box(Modifier.fillMaxSize().padding(end = room, bottom = room).popCard(shape, shadow = 5.dp, border = 3.dp).clip(shape).background(background)) {
        when {
            loading -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = if (pop) Accent else ink)
            lyrics == null || lyrics.isEmpty -> Text(
                "Testo non disponibile per questo brano.",
                Modifier.align(Alignment.Center).padding(24.dp),
                color = ink.copy(alpha = 0.8f),
                fontWeight = FontWeight.SemiBold,
            )
            lyrics.synced.isNotEmpty() -> {
                val state = rememberLazyListState()
                // Si ridisegna quando cambia la riga, non a ogni passo della posizione.
                val current by remember(lyrics) { derivedStateOf { lyrics.lineAt(vm.position + 300) } }
                LaunchedEffect(current) {
                    if (current >= 0) state.animateScrollToItem((current - 2).coerceAtLeast(0))
                }
                LazyColumn(state = state, contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    itemsIndexed(lyrics.synced) { i, line ->
                        Text(
                            line.text.ifBlank { "♪" },
                            fontSize = 24.sp,
                            lineHeight = 30.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = when {
                                i == current -> ink
                                i < current -> ink.copy(alpha = 0.6f)
                                else -> ink.copy(alpha = 0.35f)
                            },
                            modifier = Modifier.fillMaxWidth().clickable { vm.seekTo(line.timeMs) },
                        )
                    }
                }
            }
            else -> LazyColumn(contentPadding = PaddingValues(20.dp)) {
                item { Text(lyrics.plain.orEmpty(), fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold, color = ink) }
            }
        }
    }
}

@Composable
private fun SleepButton(end: Long?, onClick: () -> Unit) {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(end) {
        while (end != null && end > 0) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    Row(Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onClick).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.Timer, "Timer di spegnimento", tint = if (end != null) Accent else PlayerText)
        val label = when {
            end == null -> null
            end == BaldMusicApp.SLEEP_END_OF_TRACK -> "Fine brano"
            else -> formatDuration((end - now).coerceAtLeast(0))
        }
        label?.let {
            Spacer(Modifier.size(6.dp))
            Text(it, color = Accent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun SleepTimerOptions(current: Long?, onPick: (Int?) -> Unit) {
    Column(Modifier.padding(bottom = 24.dp)) {
        Text("Timer di spegnimento", fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        listOf(5, 10, 15, 30, 45, 60, 90).forEach { m ->
            SheetAction(Icons.Rounded.Timer, "$m minuti") { onPick(m) }
        }
        SheetAction(Icons.Rounded.Album, "Fine del brano") { onPick(0) }
        if (current != null) SheetAction(Icons.Rounded.Timer, "Spegni il timer", tint = Accent) { onPick(null) }
    }
}

/** Indietro o avanti nella puntata; [label] scritto dentro l'icona (Material non ha «indietro di 15»). */
@Composable
private fun SkipButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String?, description: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, Modifier.size(48.dp)) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, description, Modifier.size(32.dp))
            label?.let { Text(it, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 3.dp)) }
        }
    }
}
