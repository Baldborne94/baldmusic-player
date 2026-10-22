package io.github.baldborne94.baldmusic.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import io.github.baldborne94.baldmusic.core.library.ArtistNames
import io.github.baldborne94.baldmusic.core.library.LetterIndex
import io.github.baldborne94.baldmusic.core.library.LocalCatalog
import io.github.baldborne94.baldmusic.core.library.PersonalSource
import io.github.baldborne94.baldmusic.core.library.RemoteSource
import io.github.baldborne94.baldmusic.core.library.sortedByKey
import io.github.baldborne94.baldmusic.core.podcast.PodcastSource
import io.github.baldborne94.baldmusic.playback.group

private enum class Filter(val label: String) { COLLECTION("Raccolte"), PLAYLIST("Playlist"), ALBUM("Album"), ARTIST("Artisti"), PODCAST("Podcast") }

@Composable
fun LibraryScreen(vm: PlayerViewModel) {
    val ui = LocalUi.current
    val lib by vm.library.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf<Filter?>(null) }
    var alphabetical by rememberSaveable { mutableStateOf(false) }
    var creating by rememberSaveable { mutableStateOf(false) }

    val (roots) = rememberChildren(vm, "root")
    val hasServer = roots?.any { it.mediaId == RemoteSource.KEY } == true
    val hasPodcasts = roots?.any { it.mediaId == PodcastSource.KEY } == true
    val (playlists) = rememberChildren(vm, "${PersonalSource.KEY}/${PersonalSource.PLAYLISTS}", lib.playlists)
    val (collections) = rememberChildren(vm, "${PersonalSource.KEY}/${PersonalSource.COLLECTIONS}", lib.playlists)
    val (recent) = rememberChildren(vm, "${PersonalSource.KEY}/${PersonalSource.RECENT}", lib.recent)
    // Album e artisti (migliaia) solo quando si guardano.
    val (albums) = if (filter == Filter.ALBUM) rememberChildren(vm, "${LocalCatalog.KEY}/${LocalCatalog.ALBUMS}") else (null to null)
    val (artists) = if (filter == Filter.ARTIST) rememberChildren(vm, "${LocalCatalog.KEY}/${LocalCatalog.ARTISTS}") else (null to null)
    val (podcastShows) = if (filter == Filter.PODCAST) rememberChildren(vm, PodcastSource.KEY) else (null to null)
    val (serverPlaylists) = if (hasServer) rememberChildren(vm, "${RemoteSource.KEY}/${RemoteSource.PLAYLISTS}") else (null to null)

    // Una volta per elenco, non a ogni ridisegno; la chiave d'ordine si calcola una volta per elemento.
    val items: List<MediaItem> = remember(filter, alphabetical, collections, playlists, recent, albums, artists, serverPlaylists, podcastShows) {
        when (filter) {
            null -> (collections.orEmpty() + playlists.orEmpty() + recent.orEmpty()).distinctBy { it.mediaId }
            Filter.COLLECTION -> collections.orEmpty()
            Filter.PLAYLIST -> playlists.orEmpty() + serverPlaylists.orEmpty()
            Filter.ALBUM -> albums.orEmpty()
            Filter.ARTIST -> artists.orEmpty()
            Filter.PODCAST -> podcastShows.orEmpty()
        }.let { if (alphabetical) it.sortedByKey { i -> ArtistNames.sortKey(i.title()) } else it }
    }

    // Artisti e album per lettera, come in Android Auto (solo nell'ordine della libreria, che è già alfabetico).
    val lettered = !alphabetical && (filter == Filter.ARTIST || filter == Filter.ALBUM)
    val listState = rememberLazyListState()
    // Sopra l'elenco: titolo, filtri e ordine.
    val letters = remember(items, lettered) { if (lettered) LetterIndex.entries(items.map { it.group }, before = 3) else emptyList() }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 16.dp, end = 4.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(34.dp).clip(CircleShape).background(AccentBrush), contentAlignment = Alignment.Center) {
                        Text("B", fontWeight = FontWeight.Black, color = Color.Black)
                    }
                    Spacer(Modifier.width(12.dp))
                    Text("La tua libreria", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                    IconButton(onClick = { ui.push(Screen.Cleanup) }) { Icon(Icons.Rounded.CleaningServices, "Pulizia della libreria") }
                    IconButton(onClick = { creating = true }) { Icon(Icons.Rounded.Add, "Crea playlist", modifier = Modifier.size(28.dp)) }
                }
            }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (filter != null) {
                        item {
                            Box(Modifier.size(34.dp).clip(CircleShape).background(Surface2).clickable { filter = null }, contentAlignment = Alignment.Center) {
                                Icon(Icons.Rounded.Close, "Tutto", modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                    items(Filter.entries.filter { (filter == null || it == filter) && (it != Filter.PODCAST || hasPodcasts) }) { f ->
                        Chip(f.label, selected = filter == f) { filter = if (filter == f) null else f }
                    }
                }
            }
            item {
                Row(
                    Modifier.fillMaxWidth().clickable { alphabetical = !alphabetical }.padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.AutoMirrored.Rounded.Sort, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (alphabetical) "Alfabetico" else "Recenti", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                }
            }
            if (filter == null || filter == Filter.PLAYLIST) {
                item {
                    val liked = MediaItem.Builder()
                        .setMediaId("${PersonalSource.KEY}/${PersonalSource.LIKED}")
                        .setMediaMetadata(androidx.media3.common.MediaMetadata.Builder().setTitle("Brani che ti piacciono").build())
                        .build()
                    ContainerRow(liked, subtitle = "Playlist · ${PersonalSource.count(lib.liked.size)}", pinned = true) {
                        ui.open(liked.mediaId, "Brani che ti piacciono")
                    }
                }
            }
            items.forEachIndexed { i, item ->
                val group = item.group
                if (lettered && group != null && (i == 0 || items[i - 1].group != group)) {
                    item(key = "lettera-$i-$group") { SectionHeader(group) }
                }
                item(key = item.mediaId) { ContainerRow(item) { ui.open(item.mediaId, item.title()) } }
            }
            if (items.isEmpty() && filter == null) {
                item { Message("Crea la tua prima playlist con il + qui sopra, oppure tocca il cuore sui brani che ti piacciono.") }
            }
        }
        LetterRail(letters, scrolling = { listState.isScrollInProgress }, onJump = { listState.scrollToItem(it) }, Modifier.align(Alignment.CenterEnd).padding(end = 4.dp))
    }

    if (creating) {
        NewPlaylistDialog(onDismiss = { creating = false }) { name ->
            val id = vm.createPlaylist(name)
            creating = false
            ui.open("${PersonalSource.KEY}/${PersonalSource.PLAYLIST}/$id", name)
        }
    }
}

@Composable
fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(50)).background(if (selected) Accent else Surface2).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(label, fontSize = 14.sp, color = if (selected) Color.Black else Color.White, fontWeight = FontWeight.SemiBold)
    }
}
