package io.github.baldborne94.baldmusic.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.baldborne94.baldmusic.core.library.PersonalSource
import io.github.baldborne94.baldmusic.core.library.UserLibrary

/** La coda: cosa suona ora e cosa viene dopo, da riordinare o togliere. */
@Composable
fun QueueScreen(vm: PlayerViewModel, onClose: () -> Unit) {
    val queue = vm.queue
    val index = vm.queueIndex
    Column(
        Modifier
            .fillMaxSize()
            .background(Background)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Rounded.KeyboardArrowDown, "Chiudi", Modifier.size(30.dp)) }
            Text("Coda", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            if (index + 1 < queue.size) TextButton(onClick = { vm.clearUpcoming() }) { Text("Svuota", color = TextMuted) }
        }
        if (queue.isEmpty()) Message("La coda è vuota.")
        else LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            queue.getOrNull(index)?.let { now ->
                item { Text("In riproduzione", style = SectionTitle, modifier = Modifier.padding(16.dp)) }
                item {
                    TrackRow(now, active = true, liked = false, onClick = { vm.toggle() }, onMenu = {})
                }
            }
            if (index + 1 < queue.size) {
                item { Text("Prossimi brani", style = SectionTitle, modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 8.dp)) }
            }
            items(queue.withIndex().drop(index + 1).toList(), key = { "${it.index}-${it.value.mediaId}" }) { (i, item) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        Row(Modifier.fillMaxWidth().clickable { vm.skipTo(i) }.padding(start = 16.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Artwork(item, Modifier.size(46.dp), shape = RoundedCornerShape(4.dp))
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(item.title(), maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                                Text(item.subtitle().orEmpty(), color = TextMuted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    IconButton(onClick = { vm.move(i, i - 1) }, enabled = i > index + 1) { Icon(Icons.Rounded.KeyboardArrowUp, "Sposta su", tint = TextMuted) }
                    IconButton(onClick = { vm.move(i, i + 1) }, enabled = i < queue.lastIndex) { Icon(Icons.Rounded.KeyboardArrowDown, "Sposta giù", tint = TextMuted) }
                    IconButton(onClick = { vm.removeAt(i) }) { Icon(Icons.Rounded.Close, "Togli dalla coda", tint = TextMuted) }
                }
            }
        }
    }
}

/** Scegli la playlist in cui aggiungere, oppure creane una nuova. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToPlaylistSheet(lib: UserLibrary, onDismiss: () -> Unit, onCreate: (String) -> Unit, onPick: (String) -> Unit) {
    var creating by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Surface1) {
        Text("Aggiungi a playlist", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        Row(
            Modifier.fillMaxWidth().clickable { creating = true }.padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(56.dp).clip(RoundedCornerShape(6.dp)).background(Surface2), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Add, null, modifier = Modifier.size(30.dp))
            }
            Spacer(Modifier.width(14.dp))
            Text("Nuova playlist", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
            items(lib.playlists.reversed(), key = { it.id }) { pl ->
                Row(
                    Modifier.fillMaxWidth().clickable { onPick(pl.id); onDismiss() }.padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(56.dp).clip(RoundedCornerShape(6.dp)).background(tileBrush(pl.id)), contentAlignment = Alignment.Center) {
                        Text(pl.name.take(1).uppercase(), fontWeight = FontWeight.Black, fontSize = 22.sp)
                    }
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(pl.name, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(PersonalSource.count(pl.tracks.size), color = TextMuted, fontSize = 13.sp)
                    }
                }
            }
        }
    }
    if (creating) {
        NewPlaylistDialog(onDismiss = { creating = false }) { name ->
            onCreate(name)
            creating = false
            onDismiss()
        }
    }
}

@Composable
fun NewPlaylistDialog(
    title: String = "Dai un nome alla playlist",
    initial: String = "",
    confirm: String = "Crea",
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface1,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                placeholder = { Text("La mia playlist") },
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Accent, cursorColor = Accent),
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(name.ifBlank { "La mia playlist" }) }) { Text(confirm, color = Accent, fontWeight = FontWeight.Bold) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla", color = TextMuted) } },
    )
}

@Composable
fun ConfirmDialog(title: String, text: String, confirm: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface1,
        title = { Text(title) },
        text = { Text(text, color = TextMuted) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm, color = Color(0xFFFF5C6C), fontWeight = FontWeight.Bold) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla", color = TextMuted) } },
    )
}

/** Le impostazioni come pagina, con il tasto indietro. */
@Composable
fun SettingsPage(vm: PlayerViewModel) {
    val ui = LocalUi.current
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = ui.back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Indietro") }
            Text("Impostazioni", style = MaterialTheme.typography.titleLarge)
        }
        Box(Modifier.weight(1f)) { SettingsScreen(vm) }
        Spacer(Modifier.height(8.dp))
    }
}
