package io.github.baldborne94.baldmusic.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.NewReleases
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import io.github.baldborne94.baldmusic.app
import io.github.baldborne94.baldmusic.core.library.LibraryNode.Kind
import io.github.baldborne94.baldmusic.core.library.MediaId
import io.github.baldborne94.baldmusic.core.library.RemoteSource
import io.github.baldborne94.baldmusic.core.subsonic.Credentials
import io.github.baldborne94.baldmusic.core.subsonic.SubsonicClient
import io.github.baldborne94.baldmusic.library.LocalSource
import io.github.baldborne94.baldmusic.playback.kind
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Stesso brano anche se arrivato da contenitori diversi (album, ricerca, coda…). */
fun sameTrack(a: String?, b: String?): Boolean {
    val x = a?.let { MediaId.parse(it) } ?: return false
    val y = b?.let { MediaId.parse(it) } ?: return false
    return x.isTrack && y.isTrack && x.source == y.source && x.id == y.id
}

private fun sectionIcon(mediaId: String) = when (MediaId.parse(mediaId)?.kind) {
    "albums" -> Icons.Rounded.Album
    "recent" -> Icons.Rounded.NewReleases
    "artists" -> Icons.Rounded.Person
    "playlists" -> Icons.AutoMirrored.Rounded.QueueMusic
    "starred" -> Icons.Rounded.Favorite
    "random", "shuffle" -> Icons.Rounded.Shuffle
    else -> Icons.Rounded.MusicNote
}

/** Carica i figli di un nodo e li ricarica quando cambia la libreria. */
@Composable
private fun rememberChildren(vm: PlayerViewModel, mediaId: String): Pair<List<MediaItem>?, String?> {
    var items by remember(mediaId) { mutableStateOf<List<MediaItem>?>(null) }
    var error by remember(mediaId) { mutableStateOf<String?>(null) }
    LaunchedEffect(vm.browser, mediaId, vm.libraryVersion) {
        if (vm.browser == null) return@LaunchedEffect
        vm.children(mediaId).fold({ items = it; error = null }, { error = it.message })
    }
    return items to error
}

@Composable
fun HomeScreen(vm: PlayerViewModel, go: (Screen) -> Unit) {
    val context = LocalContext.current
    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) context.app.settings.touch()
    }
    // Al primo avvio si chiede subito l'accesso alla musica del telefono.
    LaunchedEffect(Unit) {
        if (!context.app.local.hasPermission()) askPermission.launch(LocalSource.PERMISSION)
    }

    val (sources, error) = rememberChildren(vm, MediaId.ROOT)
    LazyColumn(Modifier.fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
        if (error != null) item { Message(error) }
        if (sources == null && error == null) item { Loading() }
        sources?.forEach { source ->
            item(key = source.mediaId) {
                SourceBlock(vm, source, go, onAskPermission = { askPermission.launch(LocalSource.PERMISSION) })
            }
        }
        if (sources != null && sources.none { it.mediaId == RemoteSource.KEY }) {
            item {
                InfoCard(
                    icon = Icons.Rounded.Dns,
                    title = "Ascolta anche la musica del PC",
                    text = "Collega il tuo server Navidrome per avere la stessa libreria sul telefono, sul tablet, sul PC e in auto.",
                    action = "Collega server",
                    onAction = { go(Screen.Settings) },
                )
            }
        }
    }
}

@Composable
private fun SourceBlock(vm: PlayerViewModel, source: MediaItem, go: (Screen) -> Unit, onAskPermission: () -> Unit) {
    val (sections, error) = rememberChildren(vm, source.mediaId)
    Column(Modifier.padding(bottom = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 8.dp, bottom = 8.dp)) {
            Icon(
                if (source.mediaId == RemoteSource.KEY) Icons.Rounded.Dns else Icons.Rounded.PhoneAndroid,
                null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.size(10.dp))
            Text(
                if (source.mediaId == RemoteSource.KEY) "Sul server" else "Sul telefono",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        }
        when {
            error != null -> Message(error)
            sections == null -> Loading()
            sections.any { it.kind == Kind.MESSAGE } -> InfoCard(
                icon = Icons.Rounded.PhoneAndroid,
                title = "Accesso alla musica",
                text = "Per elencare i brani salvati sul telefono serve il permesso di leggere i file audio.",
                action = "Consenti",
                onAction = onAskPermission,
            )
            else -> sections.forEach { section ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { go(Screen.Browse(section.mediaId, section.title())) }
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SectionIcon(sectionIcon(section.mediaId))
                    Spacer(Modifier.size(14.dp))
                    Text(section.title(), style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

@Composable
private fun InfoCard(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, text: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Card(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionIcon(icon)
                Spacer(Modifier.size(12.dp))
                Text(title, fontWeight = FontWeight.Bold)
            }
            Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            if (action != null && onAction != null) Button(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
fun BrowseScreen(vm: PlayerViewModel, screen: Screen.Browse, go: (Screen) -> Unit) {
    val (items, error) = rememberChildren(vm, screen.mediaId)
    val id = MediaId.parse(screen.mediaId)
    val list = items.orEmpty()
    val hasTracks = list.any { it.kind == Kind.TRACK }
    val canPlayAll = hasTracks || id?.kind == "artist"
    val grid = list.isNotEmpty() && list.all { it.kind == Kind.ALBUM || it.kind == Kind.PLAYLIST }
    val numbered = id?.kind == "album"
    val open: (MediaItem) -> Unit = { item ->
        if (item.kind == Kind.TRACK) vm.play(item) else go(Screen.Browse(item.mediaId, item.title()))
    }

    when {
        error != null -> Message(error)
        items == null -> Loading()
        list.isEmpty() -> Message("Qui non c'è niente.")
        grid -> LazyVerticalGrid(
            GridCells.Adaptive(150.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (canPlayAll) item(span = { GridItemSpan(maxLineSpan) }) { PlayButtons(vm, screen.mediaId) }
            items(list, key = { it.mediaId }) { GridCard(it) { open(it) } }
        }
        else -> LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp)) {
            if (canPlayAll) item { PlayButtons(vm, screen.mediaId) }
            items(list, key = { it.mediaId }) { item ->
                val isTrack = item.kind == Kind.TRACK
                ItemRow(
                    item,
                    active = sameTrack(item.mediaId, vm.current?.mediaId),
                    number = if (numbered) item.mediaMetadata.trackNumber else null,
                    onClick = { open(item) },
                    onPlayNext = if (isTrack) ({ vm.playNext(item) }) else null,
                    onEnqueue = if (isTrack) ({ vm.enqueue(item) }) else null,
                )
            }
        }
    }
}

@Composable
private fun PlayButtons(vm: PlayerViewModel, containerId: String) {
    Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = { vm.playAll(containerId, shuffled = false) }, modifier = Modifier.weight(1f)) {
            Icon(Icons.Rounded.PlayArrow, null)
            Spacer(Modifier.size(6.dp))
            Text("Riproduci")
        }
        FilledTonalButton(onClick = { vm.playAll(containerId, shuffled = true) }, modifier = Modifier.weight(1f)) {
            Icon(Icons.Rounded.Shuffle, null)
            Spacer(Modifier.size(6.dp))
            Text("Casuale")
        }
    }
}

@Composable
fun SearchScreen(vm: PlayerViewModel, go: (Screen) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<MediaItem>?>(null) }
    var searching by remember { mutableStateOf(false) }
    LaunchedEffect(query, vm.browser) {
        val q = query.trim()
        if (q.length < 2) {
            results = null
            return@LaunchedEffect
        }
        delay(300) // aspetta che si smetta di scrivere
        searching = true
        results = vm.search(q)
        searching = false
    }
    Column(Modifier.padding(horizontal = 12.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Artisti, album, brani…") },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, "Cancella") } },
            singleLine = true,
            shape = RoundedCornerShape(28.dp),
        )
        Spacer(Modifier.height(8.dp))
        val list = results
        when {
            searching && list == null -> Loading()
            list != null && list.isEmpty() -> Message("Nessun risultato per “${query.trim()}”.")
            list != null -> LazyColumn {
                items(list, key = { it.mediaId }) { item ->
                    val isTrack = item.kind == Kind.TRACK
                    ItemRow(
                        item,
                        active = sameTrack(item.mediaId, vm.current?.mediaId),
                        onClick = { if (isTrack) vm.play(item) else go(Screen.Browse(item.mediaId, item.title())) },
                        onPlayNext = if (isTrack) ({ vm.playNext(item) }) else null,
                        onEnqueue = if (isTrack) ({ vm.enqueue(item) }) else null,
                    )
                }
            }
        }
    }
}

@Composable
fun QueueScreen(vm: PlayerViewModel) {
    val queue = vm.queue
    if (queue.isEmpty()) {
        Message("La coda è vuota.")
        return
    }
    LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp)) {
        item {
            OutlinedButton(onClick = { vm.clearQueue() }, modifier = Modifier.padding(8.dp)) { Text("Svuota la coda") }
        }
        itemsIndexed(queue) { index, item ->
            ItemRow(
                item,
                active = index == vm.queueIndex,
                onClick = { vm.skipTo(index) },
                trailing = {
                    IconButton(onClick = { vm.removeAt(index) }) { Icon(Icons.Rounded.Close, "Rimuovi") }
                },
            )
        }
    }
}

private val BITRATES = listOf(0 to "Originale", 320 to "320 kbps", 192 to "192 kbps", 128 to "128 kbps")

@Composable
fun SettingsScreen(vm: PlayerViewModel) {
    val context = LocalContext.current
    val settings = context.app.settings
    val scope = rememberCoroutineScope()
    var creds by remember { mutableStateOf(settings.credentials) }
    var url by rememberSaveable { mutableStateOf(creds?.url ?: "") }
    var user by rememberSaveable { mutableStateOf(creds?.user ?: "") }
    var password by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var bitrate by remember { mutableIntStateOf(settings.maxBitRate) }

    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Server Navidrome", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        val current = creds
        if (current != null) {
            Text("Collegato come ${current.user} a ${current.url}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = {
                    settings.credentials = null
                    creds = null
                    status = "Server scollegato."
                }) { Text("Scollega") }
            }
            Text("Qualità in streaming", fontWeight = FontWeight.SemiBold)
            Text(
                "Su rete mobile un bitrate più basso consuma meno dati. La conversione richiede ffmpeg sul PC.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BITRATES.forEach { (kbps, label) ->
                    FilterChip(
                        selected = bitrate == kbps,
                        onClick = {
                            bitrate = kbps
                            settings.maxBitRate = kbps
                            settings.touch()
                        },
                        label = { Text(label) },
                    )
                }
            }
        } else {
            Text(
                "Inserisci l'indirizzo del PC: a casa va bene http://192.168.x.x:4533, fuori casa serve l'indirizzo di Tailscale (es. http://100.x.x.x:4533 o https://pc.tail1234.ts.net).",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), label = { Text("Indirizzo del server") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
            OutlinedTextField(user, { user = it }, Modifier.fillMaxWidth(), label = { Text("Utente") }, singleLine = true)
            OutlinedTextField(
                password, { password = it }, Modifier.fillMaxWidth(),
                label = { Text("Password") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
            Button(
                enabled = !busy && url.isNotBlank() && user.isNotBlank() && password.isNotEmpty(),
                onClick = {
                    busy = true
                    status = "Connessione…"
                    scope.launch {
                        val candidate = Credentials.create(url, user, password)
                        status = runCatching { SubsonicClient(candidate, context.app.http).ping() }.fold(
                            onSuccess = {
                                settings.credentials = candidate
                                creds = candidate
                                password = ""
                                "Collegato! La musica del server è nella home e in Android Auto."
                            },
                            onFailure = { it.message ?: "Connessione non riuscita" },
                        )
                        busy = false
                    }
                },
            ) { Text(if (busy) "Connessione…" else "Collega") }
        }
        status?.let { Text(it, color = MaterialTheme.colorScheme.primary) }

        Spacer(Modifier.height(12.dp))
        Text("Android Auto", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(
            "BaldMusic compare tra le app musicali di Android Auto. Poiché l'app non arriva dal Play Store, la prima volta serve un passaggio:\n\n" +
                "1. Apri le impostazioni di Android Auto sul telefono.\n" +
                "2. Tocca 10 volte \"Versione\" per sbloccare le impostazioni sviluppatore.\n" +
                "3. Dal menu ⋮ → Impostazioni sviluppatore, attiva \"Origini sconosciute\".\n" +
                "4. Ricollega il telefono all'auto.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedButton(onClick = {
            val intent = context.packageManager.getLaunchIntentForPackage("com.google.android.projection.gearhead")
                ?: Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:com.google.android.projection.gearhead"))
            runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }) { Text("Apri Android Auto") }

        Spacer(Modifier.height(12.dp))
        Text("Informazioni", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(
            "BaldMusic è open source: github.com/Baldborne94/baldmusic-player",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
