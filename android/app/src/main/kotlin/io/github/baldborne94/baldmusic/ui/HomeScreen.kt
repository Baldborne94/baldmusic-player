package io.github.baldborne94.baldmusic.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import io.github.baldborne94.baldmusic.app
import io.github.baldborne94.baldmusic.core.library.LibraryNode.Kind
import io.github.baldborne94.baldmusic.core.library.PersonalSource
import io.github.baldborne94.baldmusic.core.library.RemoteSource
import io.github.baldborne94.baldmusic.library.LocalSource
import io.github.baldborne94.baldmusic.playback.kind
import java.util.Calendar

/** Carica i figli di un nodo e li ricarica quando cambia la libreria o la tua musica. */
@Composable
fun rememberChildren(vm: PlayerViewModel, mediaId: String, key: Any? = null): Pair<List<MediaItem>?, String?> {
    var items by remember(mediaId) { mutableStateOf<List<MediaItem>?>(null) }
    var error by remember(mediaId) { mutableStateOf<String?>(null) }
    LaunchedEffect(vm.browser, mediaId, vm.libraryVersion, key) {
        if (vm.browser == null) return@LaunchedEffect
        vm.children(mediaId).fold({ items = it; error = null }, { error = it.message })
    }
    return items to error
}

private fun greeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
    in 5..12 -> "Buongiorno"
    in 13..17 -> "Buon pomeriggio"
    else -> "Buonasera"
}

@Composable
fun HomeScreen(vm: PlayerViewModel) {
    val ui = LocalUi.current
    val context = LocalContext.current
    val lib by vm.library.collectAsStateWithLifecycle()
    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) context.app.settings.touch()
    }
    LaunchedEffect(Unit) {
        if (!context.app.local.hasPermission()) askPermission.launch(LocalSource.PERMISSION)
    }

    val (recent) = rememberChildren(vm, "${PersonalSource.KEY}/${PersonalSource.RECENT}", lib.recent)
    val (top) = rememberChildren(vm, "${PersonalSource.KEY}/${PersonalSource.TOP}", lib.plays.size)
    val (liked) = rememberChildren(vm, "${PersonalSource.KEY}/${PersonalSource.LIKED}", lib.liked.size)
    val (localSections) = rememberChildren(vm, LocalSource.KEY)
    val (recentAdded) = rememberChildren(vm, "${LocalSource.KEY}/${LocalSource.RECENT}")
    val newAlbums = recentAdded?.filter { it.kind != Kind.TRACK }
    val (allAlbums) = rememberChildren(vm, "${LocalSource.KEY}/${LocalSource.ALBUMS}")
    val (decades) = rememberChildren(vm, "${LocalSource.KEY}/${LocalSource.DECADES}")
    val (artists) = rememberChildren(vm, "${LocalSource.KEY}/${LocalSource.ARTISTS}")
    val (roots) = rememberChildren(vm, "root")
    val hasServer = roots?.any { it.mediaId == RemoteSource.KEY } == true
    val (serverNew) = if (hasServer) rememberChildren(vm, "${RemoteSource.KEY}/${RemoteSource.RECENT}") else (null to null)
    val needsPermission = localSections?.any { it.kind == Kind.MESSAGE } == true

    // "Riscopri" cambia a ogni apertura dell'app, non a ogni ricomposizione.
    val rediscover = remember(allAlbums) { allAlbums?.shuffled()?.take(12) }
    val topArtists = remember(artists) { artists?.shuffled()?.take(10) }

    val header = remember { greeting() }
    LazyColumn(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(0f to Color(0xFF3A2A4E), 0.25f to Background)),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 16.dp, end = 4.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(header, style = androidx.compose.material3.MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = { ui.push(Screen.Stats) }) { Icon(Icons.Outlined.Insights, "Le tue statistiche") }
                IconButton(onClick = ui.settings) { Icon(Icons.Outlined.Settings, "Impostazioni") }
            }
        }

        if (needsPermission) {
            item {
                Column(Modifier.padding(16.dp).clip(RoundedCornerShape(12.dp)).background(Surface2).padding(18.dp)) {
                    Icon(Icons.Rounded.PhoneAndroid, null, tint = Accent)
                    Spacer(Modifier.height(8.dp))
                    Text("Consenti l'accesso alla musica", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    Text("Serve per trovare i brani salvati sul telefono.", color = TextMuted)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { askPermission.launch(LocalSource.PERMISSION) }, colors = ButtonDefaults.buttonColors(containerColor = Accent)) { Text("Consenti") }
                }
            }
        }

        // Accesso rapido: 6 tessere come nella home di Spotify.
        val quick = buildList {
            liked?.let { if (it.isNotEmpty()) add(QuickTile.Liked(it.size)) }
            recent.orEmpty().take(if (isEmpty()) 6 else 5).forEach { add(QuickTile.Item(it)) }
            if (size < 6) rediscover.orEmpty().take(6 - size).forEach { add(QuickTile.Item(it)) }
        }
        if (quick.isNotEmpty()) {
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    quick.chunked(2).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { tile -> QuickTileView(tile, vm, Modifier.weight(1f)) }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }

        if (!recent.isNullOrEmpty()) {
            item { SectionHeader("Ascoltati di recente") }
            item { Carousel(recent, 140.dp) { ui.open(it.mediaId, it.title()) } }
        }

        // I tuoi mix: si generano dalla tua musica, come i Daily Mix.
        val mixes = buildList {
            add(Mix("Mix casuale", "Tutta la tua musica, mescolata", "${LocalSource.KEY}/${LocalSource.SHUFFLE}"))
            if (!liked.isNullOrEmpty()) add(Mix("Preferiti in shuffle", "I brani che ti piacciono", "${PersonalSource.KEY}/${PersonalSource.LIKED}"))
            if (!top.isNullOrEmpty()) add(Mix("In loop", "Quelli che ascolti di più", "${PersonalSource.KEY}/${PersonalSource.TOP}"))
            // Un mix per ognuno dei tuoi artisti più ascoltati (brani del telefono).
            lib.plays.values.filter { it.track.id.startsWith("${LocalSource.KEY}/") }
                .groupBy { it.track.artist }
                .filterKeys { !it.isNullOrBlank() }
                .entries.sortedByDescending { e -> e.value.sumOf { it.count } }
                .take(4)
                .forEach { (artist, _) ->
                    add(Mix("Mix $artist", "$artist e musica simile", "${LocalSource.KEY}/${LocalSource.ARTIST_MIX}/${java.net.URLEncoder.encode(artist, "UTF-8")}"))
                }
            decades.orEmpty().forEach { add(Mix("Mix ${it.title()}", it.subtitle().orEmpty(), it.mediaId)) }
        }
        item { SectionHeader("I tuoi mix") }
        item {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                items(mixes, key = { it.id }) { mix -> MixCard(mix) { vm.playAll(mix.id, shuffled = true, from = mix.title) } }
            }
        }

        if (!newAlbums.isNullOrEmpty()) {
            item { SectionHeader("Aggiunti di recente", "Mostra tutto") { ui.open("${LocalSource.KEY}/${LocalSource.RECENT}", "Aggiunti di recente") } }
            item { Carousel(newAlbums.take(15), 150.dp) { ui.open(it.mediaId, it.title()) } }
        }

        if (!top.isNullOrEmpty()) {
            item { SectionHeader("I più ascoltati", "Mostra tutto") { ui.open("${PersonalSource.KEY}/${PersonalSource.TOP}", "I più ascoltati") } }
            items(top.take(5), key = { "top-" + it.mediaId }) { item ->
                TrackRow(
                    item,
                    active = sameTrack(item.mediaId, vm.current?.mediaId),
                    liked = lib.isLiked(item.mediaId),
                    onClick = { vm.play(item, from = "I più ascoltati") },
                    onMenu = { ui.trackMenu(item, null) },
                    onSwipeQueue = { vm.enqueue(item) },
                )
            }
        }

        if (!topArtists.isNullOrEmpty()) {
            item { SectionHeader("I tuoi artisti") }
            item { Carousel(topArtists, 130.dp) { ui.open(it.mediaId, it.title()) } }
        }

        if (!rediscover.isNullOrEmpty()) {
            item { SectionHeader("Da riscoprire") }
            item { Carousel(rediscover, 150.dp) { ui.open(it.mediaId, it.title()) } }
        }

        if (!serverNew.isNullOrEmpty()) {
            item { SectionHeader("Novità sul server") }
            item { Carousel(serverNew.take(15), 150.dp) { ui.open(it.mediaId, it.title()) } }
        }
    }
}

private sealed interface QuickTile {
    data class Liked(val count: Int) : QuickTile
    data class Item(val item: MediaItem) : QuickTile
}

@Composable
private fun QuickTileView(tile: QuickTile, vm: PlayerViewModel, modifier: Modifier) {
    val ui = LocalUi.current
    val (title, open) = when (tile) {
        is QuickTile.Liked -> "Brani che ti piacciono" to { ui.open("${PersonalSource.KEY}/${PersonalSource.LIKED}", "Brani che ti piacciono") }
        is QuickTile.Item -> tile.item.title() to { ui.open(tile.item.mediaId, tile.item.title()) }
    }
    val containerId = when (tile) {
        is QuickTile.Liked -> "${PersonalSource.KEY}/${PersonalSource.LIKED}"
        is QuickTile.Item -> tile.item.mediaId
    }
    val playing = vm.playingContainer == containerId && vm.isPlaying
    Row(
        modifier.height(56.dp).clip(RoundedCornerShape(6.dp)).background(Surface2).clickable(onClick = open),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (tile) {
            is QuickTile.Liked -> LikedArtwork(Modifier.size(56.dp), RoundedCornerShape(0.dp))
            is QuickTile.Item -> Artwork(tile.item, Modifier.size(56.dp), RoundedCornerShape(0.dp))
        }
        Text(
            title,
            Modifier.weight(1f).padding(horizontal = 10.dp),
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            lineHeight = 16.sp,
            color = if (playing) Accent else Color.White,
        )
    }
}

private data class Mix(val title: String, val subtitle: String, val id: String)

@Composable
private fun MixCard(mix: Mix, onClick: () -> Unit) {
    Column(Modifier.width(150.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick)) {
        Box(Modifier.size(150.dp).clip(RoundedCornerShape(8.dp)).background(tileBrush(mix.id))) {
            Text(
                mix.title,
                Modifier.align(Alignment.BottomStart).padding(12.dp),
                fontWeight = FontWeight.Black,
                fontSize = 20.sp,
                lineHeight = 22.sp,
                color = Color.White,
            )
            Box(Modifier.align(Alignment.TopEnd).padding(10.dp).size(10.dp).clip(RoundedCornerShape(5.dp)).background(Brush.linearGradient(listOf(Accent, Accent2))))
        }
        Spacer(Modifier.height(8.dp))
        Text(mix.subtitle, color = TextMuted, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 16.sp)
    }
}
