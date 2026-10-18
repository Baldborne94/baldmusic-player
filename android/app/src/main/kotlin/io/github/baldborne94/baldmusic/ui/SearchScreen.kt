package io.github.baldborne94.baldmusic.ui

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import io.github.baldborne94.baldmusic.core.library.LibraryNode.Kind
import io.github.baldborne94.baldmusic.core.library.LocalCatalog
import io.github.baldborne94.baldmusic.core.library.PersonalSource
import io.github.baldborne94.baldmusic.core.library.RemoteSource
import io.github.baldborne94.baldmusic.playback.kind
import kotlinx.coroutines.delay

private data class BrowseTile(val title: String, val id: String)

@Composable
fun SearchScreen(vm: PlayerViewModel) {
    val ui = LocalUi.current
    val lib by vm.library.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<MediaItem>?>(null) }
    var searching by remember { mutableStateOf(false) }
    LaunchedEffect(query, vm.browser) {
        val q = query.trim()
        if (q.length < 2) {
            results = null
            return@LaunchedEffect
        }
        delay(250) // aspetta che si smetta di scrivere
        searching = true
        results = vm.search(q)
        searching = false
    }

    val (decades) = rememberChildren(vm, "${LocalCatalog.KEY}/${LocalCatalog.DECADES}")
    val (roots) = rememberChildren(vm, "root")
    val tiles = buildList {
        add(BrowseTile("Raccolte", "${PersonalSource.KEY}/${PersonalSource.COLLECTIONS}"))
        add(BrowseTile("Brani che ti piacciono", "${PersonalSource.KEY}/${PersonalSource.LIKED}"))
        add(BrowseTile("Album", "${LocalCatalog.KEY}/${LocalCatalog.ALBUMS}"))
        add(BrowseTile("Singoli e brani sparsi", "${LocalCatalog.KEY}/${LocalCatalog.SINGLES}"))
        add(BrowseTile("Artisti", "${LocalCatalog.KEY}/${LocalCatalog.ARTISTS}"))
        add(BrowseTile("Tutti i brani", "${LocalCatalog.KEY}/${LocalCatalog.SONGS}"))
        add(BrowseTile("Aggiunti di recente", "${LocalCatalog.KEY}/${LocalCatalog.RECENT}"))
        add(BrowseTile("Cartelle", "${LocalCatalog.KEY}/${LocalCatalog.FOLDERS}"))
        add(BrowseTile("I più ascoltati", "${PersonalSource.KEY}/${PersonalSource.TOP}"))
        add(BrowseTile("Mix casuale", "${LocalCatalog.KEY}/${LocalCatalog.SHUFFLE}"))
        decades.orEmpty().forEach { add(BrowseTile(it.title(), it.mediaId)) }
        if (roots?.any { it.mediaId == RemoteSource.KEY } == true) {
            add(BrowseTile("Server: album", "${RemoteSource.KEY}/${RemoteSource.ALBUMS}"))
            add(BrowseTile("Server: artisti", "${RemoteSource.KEY}/${RemoteSource.ARTISTS}"))
            add(BrowseTile("Server: playlist", "${RemoteSource.KEY}/${RemoteSource.PLAYLISTS}"))
        }
    }

    fun openResult(item: MediaItem) {
        vm.addSearch(query)
        if (item.kind == Kind.TRACK) vm.play(item, from = "Ricerca") else ui.open(item.mediaId, item.title())
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Text(
                "Cerca",
                Modifier.statusBarsPadding().padding(start = 16.dp, top = 16.dp, bottom = 12.dp),
                style = MaterialTheme.typography.headlineMedium,
            )
        }
        item { SearchField(query) { query = it } }

        val list = results
        when {
            query.trim().length < 2 -> {
                if (lib.searches.isNotEmpty()) {
                    item {
                        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Ricerche recenti", style = SectionTitle, modifier = Modifier.weight(1f))
                            TextButton(onClick = { vm.clearSearches() }) { Text("Cancella", color = TextMuted) }
                        }
                    }
                    items(lib.searches, key = { "s-$it" }) { s ->
                        Row(
                            Modifier.fillMaxWidth().clickable { query = s }.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Rounded.History, null, tint = TextMuted)
                            Spacer(Modifier.width(14.dp))
                            Text(s, fontSize = 16.sp)
                        }
                    }
                }
                item { SectionHeader("Sfoglia tutto") }
                items(tiles.chunked(2), key = { row -> row.joinToString { it.id } }) { row ->
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEach { tile -> BrowseTileView(tile, Modifier.weight(1f)) { ui.open(tile.id, tile.title) } }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
            searching && list == null -> item { Loading() }
            list != null && list.isEmpty() -> item { Message("Nessun risultato per “${query.trim()}”.\nControlla l'ortografia o prova con meno parole.") }
            list != null -> {
                val top = list.firstOrNull { it.kind == Kind.ARTIST } ?: list.firstOrNull { it.kind == Kind.ALBUM } ?: list.first()
                item { SectionHeader("Risultato migliore") }
                item { TopResult(top) { openResult(top) } }
                val tracks = list.filter { it.kind == Kind.TRACK }
                if (tracks.isNotEmpty()) {
                    item { SectionHeader("Brani") }
                    items(tracks.take(20), key = { "t-" + it.mediaId }) { item ->
                        TrackRow(
                            item,
                            active = sameTrack(item.mediaId, vm.current?.mediaId),
                            liked = lib.isLiked(item.mediaId),
                            onClick = { openResult(item) },
                            onMenu = { ui.trackMenu(item, null) },
                    onSwipeQueue = { vm.enqueue(item) },
                        )
                    }
                }
                val artists = list.filter { it.kind == Kind.ARTIST }
                if (artists.isNotEmpty()) {
                    item { SectionHeader("Artisti") }
                    item { Carousel(artists, 120.dp) { openResult(it) } }
                }
                val albums = list.filter { it.kind == Kind.ALBUM }
                if (albums.isNotEmpty()) {
                    item { SectionHeader("Album") }
                    item { Carousel(albums, 140.dp) { openResult(it) } }
                }
                val playlists = list.filter { it.kind == Kind.PLAYLIST }
                if (playlists.isNotEmpty()) {
                    item { SectionHeader("Playlist") }
                    items(playlists, key = { "p-" + it.mediaId }) { pl -> ContainerRow(pl) { openResult(pl) } }
                }
            }
        }
    }
}

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(48.dp).clip(RoundedCornerShape(8.dp)).background(Color.White).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Search, null, tint = Color(0xFF222222))
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text("Cosa vuoi ascoltare?", color = Color(0xFF6B6B6B), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            BasicTextField(
                value,
                onChange,
                singleLine = true,
                textStyle = TextStyle(color = Color(0xFF121212), fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
                cursorBrush = SolidColor(Accent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (value.isNotEmpty()) {
            IconButton(onClick = { onChange("") }) { Icon(Icons.Rounded.Close, "Cancella", tint = Color(0xFF222222)) }
        }
    }
}

@Composable
private fun BrowseTileView(tile: BrowseTile, modifier: Modifier, onClick: () -> Unit) {
    Box(modifier.height(96.dp).clip(RoundedCornerShape(8.dp)).background(tileBrush(tile.id)).clickable(onClick = onClick)) {
        Text(tile.title, Modifier.padding(12.dp), fontWeight = FontWeight.Bold, fontSize = 17.sp, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
        // Il quadratino inclinato in basso a destra, come le categorie di Spotify.
        Box(
            Modifier.align(Alignment.BottomEnd).padding(end = 0.dp).size(58.dp).rotate(25f)
                .clip(RoundedCornerShape(4.dp)).background(Color.Black.copy(alpha = 0.22f)),
        )
    }
}

@Composable
private fun TopResult(item: MediaItem, onClick: () -> Unit) {
    val round = item.kind == Kind.ARTIST
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(10.dp)).background(Surface1).clickable(onClick = onClick).padding(18.dp),
    ) {
        Artwork(item, Modifier.size(96.dp), shape = if (round) CircleShape else RoundedCornerShape(6.dp))
        Spacer(Modifier.height(16.dp))
        Text(item.title(), fontWeight = FontWeight.Black, fontSize = 26.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        val kind = when (item.kind) {
            Kind.ARTIST -> "Artista"
            Kind.ALBUM -> "Album"
            Kind.PLAYLIST -> "Playlist"
            else -> "Brano"
        }
        Text(listOfNotNull(kind, item.subtitle().takeIf { item.kind != Kind.ARTIST }).joinToString(" · "), color = TextMuted)
    }
}
