package io.github.baldborne94.baldmusic.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.HealthAndSafety
import androidx.compose.material.icons.rounded.Restore
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
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import io.github.baldborne94.baldmusic.app
import io.github.baldborne94.baldmusic.core.library.DuplicateGroup
import io.github.baldborne94.baldmusic.core.library.LocalCatalog
import io.github.baldborne94.baldmusic.core.library.LocalTrack

/**
 * "Pulizia della libreria": doppioni (lo stesso brano in più cartelle) e anteprime di
 * 30 secondi. Tutto finisce nel cestino di Android con le solite due conferme; per i
 * doppioni le raccolte che avevano la copia buttata tengono quella rimasta.
 */
@Composable
fun CleanupScreen(vm: PlayerViewModel) {
    val ui = LocalUi.current
    val context = LocalContext.current
    var report by remember { mutableStateOf<Pair<List<DuplicateGroup>, List<LocalTrack>>?>(null) }
    // Dopo ogni cestino la libreria si rilegge (libraryVersion) e la pagina si aggiorna.
    LaunchedEffect(vm.libraryVersion) { report = context.app.local.cleanup() }
    val trash = ui.trash

    LazyColumn(
        Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color(0xFF1F4D3A), 0.3f to Background)),
        contentPadding = PaddingValues(bottom = 32.dp),
    ) {
        item { PageHeader("Pulizia della libreria") }
        if (trash != null) {
            item {
                androidx.compose.material3.TextButton(onClick = { ui.push(Screen.Trash) }, modifier = Modifier.padding(horizontal = 8.dp)) {
                    Icon(Icons.Rounded.Restore, null, tint = Accent)
                    Text("  Brani nel cestino: ripescali e rimettili al loro posto", color = Accent)
                }
            }
        }
        item {
            androidx.compose.material3.TextButton(onClick = { ui.push(Screen.Health) }, modifier = Modifier.padding(horizontal = 8.dp)) {
                Icon(Icons.Rounded.HealthAndSafety, null, tint = Accent)
                Text("  Salute della libreria: quali tag sistemare, cartella per cartella", color = Accent)
            }
        }
        val (duplicates, previews) = report ?: run {
            item { Loading() }
            return@LazyColumn
        }
        if (trash == null) {
            item { Message("Per buttare i file nel cestino serve Android 11 o successivo.") }
        }
        if (duplicates.isEmpty() && previews.isEmpty()) {
            item { Message("Tutto in ordine: niente doppioni e niente anteprime di 30 secondi.") }
            return@LazyColumn
        }

        if (duplicates.isNotEmpty()) {
            val extra = duplicates.sumOf { it.others.size }
            item {
                Intro(
                    title = "Doppioni",
                    text = "${count(duplicates.size, "brano", "brani")} in più copie, in cartelle diverse. Si tiene una copia sola " +
                        "(quella coi tag più completi): le raccolte che avevano le altre continuano ad avere il brano.",
                    action = if (trash != null) "Tieni una copia di tutti (${count(extra, "file", "file")} nel cestino)" else null,
                ) {
                    trash?.invoke(duplicates.flatMap { g -> g.others.map(::asItem) }, "Buttare ${count(extra, "copia doppia", "copie doppie")}?", KEEP_NOTE) {
                        vm.mergeDuplicates(duplicates)
                    }
                }
            }
            items(duplicates, key = { "d" + it.keep.id }) { g ->
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(g.keep.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(listOfNotNull(g.keep.artist, duration(g.keep.durationMs)).joinToString(" · "), color = TextMuted, fontSize = 13.sp, maxLines = 1)
                        Text("Resta in: ${folderName(g.keep)}", color = Accent, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("Da buttare: ${g.others.joinToString { folderName(it) }}", color = TextMuted, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    if (trash != null) {
                        IconButton(onClick = {
                            trash(g.others.map(::asItem), "Tenere una copia sola di “${g.keep.title}”?", KEEP_NOTE) { vm.mergeDuplicates(listOf(g)) }
                        }) { Icon(Icons.Rounded.Delete, "Butta le copie doppie", tint = Danger) }
                    }
                }
            }
        }

        if (previews.isNotEmpty()) {
            item {
                Intro(
                    title = "Brani di 30 secondi",
                    text = "${count(previews.size, "brano dura", "brani durano")} circa 30 secondi: quasi sempre sono anteprime scaricate " +
                        "al posto della canzone intera. Vanno riscaricati o buttati.",
                    action = if (trash != null) "Butta tutte le anteprime (${count(previews.size, "file", "file")})" else null,
                ) {
                    trash?.invoke(previews.map(::asItem), "Buttare ${count(previews.size, "anteprima", "anteprime")}?", null) {}
                }
            }
            items(previews, key = { "p" + it.id }) { t ->
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(t.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(listOfNotNull(t.artist, duration(t.durationMs), folderName(t)).joinToString(" · "), color = TextMuted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (trash != null) {
                        IconButton(onClick = { trash(listOf(asItem(t)), "Eliminare “${t.title}” dal telefono?", null) {} }) {
                            Icon(Icons.Rounded.Delete, "Butta", tint = Danger)
                        }
                    }
                }
            }
        }
    }
}

private const val KEEP_NOTE = "Le raccolte, i preferiti e le playlist che avevano queste copie tengono quella rimasta."

@Composable
private fun Intro(title: String, text: String, action: String?, onAction: () -> Unit) {
    Column(
        Modifier.padding(horizontal = 16.dp, vertical = 12.dp).clip(RoundedCornerShape(12.dp)).background(Surface2).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Text(text, color = TextMuted)
        if (action != null) {
            Button(onClick = onAction, colors = ButtonDefaults.buttonColors(containerColor = Danger)) { Text(action, color = Color.Black) }
        }
    }
}

/** Un brano del telefono come lo vuole il cestino (`Ui.trash`). */
private fun asItem(t: LocalTrack): MediaItem = MediaItem.Builder()
    .setMediaId(LocalCatalog.ref(t).id)
    .setMediaMetadata(MediaMetadata.Builder().setTitle(t.title).setArtist(t.artist).build())
    .build()

private fun folderName(t: LocalTrack) = t.folder.substringAfterLast('/').ifEmpty { t.folder }

private fun duration(ms: Long) = "%d:%02d".format(ms / 60_000, ms / 1000 % 60)

private fun count(n: Int, one: String, many: String) = if (n == 1) "1 $one" else "$n $many"
