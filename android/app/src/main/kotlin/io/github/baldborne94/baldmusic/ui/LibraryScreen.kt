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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import io.github.baldborne94.baldmusic.core.library.LocalCatalog
import io.github.baldborne94.baldmusic.core.library.PersonalSource
import io.github.baldborne94.baldmusic.core.library.RemoteSource

private enum class Filter(val label: String) { COLLECTION("Raccolte"), PLAYLIST("Playlist"), ALBUM("Album"), ARTIST("Artisti") }

@Composable
fun LibraryScreen(vm: PlayerViewModel) {
    val ui = LocalUi.current
    val lib by vm.library.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf<Filter?>(null) }
    var alphabetical by rememberSaveable { mutableStateOf(false) }
    var creating by rememberSaveable { mutableStateOf(false) }

    val (roots) = rememberChildren(vm, "root")
    val hasServer = roots?.any { it.mediaId == RemoteSource.KEY } == true
    val (playlists) = rememberChildren(vm, "${PersonalSource.KEY}/${PersonalSource.PLAYLISTS}", lib.playlists)
    val (collections) = rememberChildren(vm, "${PersonalSource.KEY}/${PersonalSource.COLLECTIONS}", lib.playlists)
    val (recent) = rememberChildren(vm, "${PersonalSource.KEY}/${PersonalSource.RECENT}", lib.recent)
    val (albums) = rememberChildren(vm, "${LocalCatalog.KEY}/${LocalCatalog.ALBUMS}")
    val (artists) = rememberChildren(vm, "${LocalCatalog.KEY}/${LocalCatalog.ARTISTS}")
    val (serverPlaylists) = if (hasServer) rememberChildren(vm, "${RemoteSource.KEY}/${RemoteSource.PLAYLISTS}") else (null to null)

    val items: List<MediaItem> = when (filter) {
        null -> (collections.orEmpty() + playlists.orEmpty() + recent.orEmpty()).distinctBy { it.mediaId }
        Filter.COLLECTION -> collections.orEmpty()
        Filter.PLAYLIST -> playlists.orEmpty() + serverPlaylists.orEmpty()
        Filter.ALBUM -> albums.orEmpty()
        Filter.ARTIST -> artists.orEmpty()
    }.let { if (alphabetical) it.sortedBy { i -> i.title().lowercase() } else it }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 16.dp, end = 4.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(34.dp).clip(CircleShape).background(AccentBrush), contentAlignment = Alignment.Center) {
                    Text("B", fontWeight = FontWeight.Black, color = Color.Black)
                }
                Spacer(Modifier.width(12.dp))
                Text("La tua libreria", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
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
                items(Filter.entries.filter { filter == null || it == filter }) { f ->
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
        items(items, key = { it.mediaId }) { item -> ContainerRow(item) { ui.open(item.mediaId, item.title()) } }
        if (items.isEmpty() && filter == null) {
            item { Message("Crea la tua prima playlist con il + qui sopra, oppure tocca il cuore sui brani che ti piacciono.") }
        }
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
