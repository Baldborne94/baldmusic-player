package io.github.baldborne94.baldmusic.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.baldborne94.baldmusic.app
import io.github.baldborne94.baldmusic.core.library.FolderHealth
import io.github.baldborne94.baldmusic.core.library.HealthReport
import io.github.baldborne94.baldmusic.core.library.LibraryHealth
import io.github.baldborne94.baldmusic.core.library.TagProblem
import io.github.baldborne94.baldmusic.core.library.TrackHealth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * "Salute della libreria": quali file del telefono hanno i tag da sistemare, cartella per
 * cartella, e perché un brano è finito tra i brani sparsi. Non tocca niente: dice quali
 * cartelle passare da Picard sul PC (guida in server/README.md).
 */
@Composable
fun HealthScreen(vm: PlayerViewModel) {
    val context = LocalContext.current
    val ui = LocalUi.current
    var report by remember { mutableStateOf<HealthReport?>(null) }
    LaunchedEffect(vm.libraryVersion) {
        val tracks = context.app.local.allTracks()
        report = withContext(Dispatchers.Default) { LibraryHealth.check(tracks) }
    }
    var filter by rememberSaveable { mutableStateOf<TagProblem?>(null) }
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    var infoFor by remember { mutableStateOf<TrackHealth?>(null) }

    LazyColumn(
        Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color(0xFF2A3A5E), 0.3f to Background)),
        contentPadding = PaddingValues(bottom = 32.dp),
    ) {
        item { PageHeader("Salute della libreria") }
        val r = report ?: run {
            item { Loading() }
            return@LazyColumn
        }
        if (r.total == 0) {
            item { Message("Nessun brano sul telefono, o manca il permesso di leggere la musica.") }
            return@LazyColumn
        }
        item { Summary(r) }
        if (r.notTagged > 0) {
            item {
                androidx.compose.material3.TextButton(onClick = { ui.push(Screen.Recognize) }, modifier = Modifier.padding(horizontal = 8.dp)) {
                    Text("Riconosci dal suono i ${r.notTagged} brani mai passati da Picard →", color = Accent)
                }
            }
        }
        if (r.counts.isNotEmpty()) {
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("Tutti", selected = filter == null) { filter = null }
                    TagProblem.entries.filter { it != TagProblem.NOT_TAGGED && (r.counts[it] ?: 0) > 0 }.forEach { p ->
                        Chip("${p.label} (${r.counts[p]})", selected = filter == p) { filter = if (filter == p) null else p }
                    }
                }
            }
            filter?.let { p -> item { Text(p.hint, color = TextMuted, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) } }
        }
        val folders = r.folders.mapNotNull { f ->
            if (filter == null) f else f.copy(tracks = f.tracks.filter { filter in it.problems }).takeIf { it.tracks.isNotEmpty() }
        }
        if (folders.isEmpty()) {
            item { Message("Tutto in ordine: nessun brano con i tag da sistemare.") }
        }
        folders.forEach { f ->
            item(key = "f" + f.folder) {
                FolderRow(f, expanded = open == f.folder) { open = if (open == f.folder) null else f.folder }
            }
            if (open == f.folder) {
                items(f.tracks, key = { "t" + it.track.id }) { h -> HealthRow(h) { infoFor = h } }
            }
        }
        item {
            Text(
                "Come si sistema: collega il telefono al PC, copia la cartella, passala in Picard (Raggruppa → Analizza → Salva) e ricopiala sul telefono al posto della vecchia. " +
                    "L'app rilegge da sola i file cambiati. La guida completa è in server/README.md, «La musica scaricata sul telefono».",
                color = TextMuted,
                fontSize = 13.sp,
                modifier = Modifier.padding(16.dp),
            )
        }
    }

    infoFor?.let { h -> TrackInfoDialog(h.track.id, h.track.title, onDismiss = { infoFor = null }) }
}

@Composable
private fun Summary(r: HealthReport) {
    Column(
        Modifier.padding(horizontal = 16.dp, vertical = 8.dp).clip(RoundedCornerShape(12.dp)).background(Surface2).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val ok = r.total - r.toFix
        Text(
            if (r.toFix == 0) "Tutti i ${r.total} brani stanno bene" else "${r.toFix} brani su ${r.total} hanno tag da sistemare",
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
        )
        HealthBar(ok.toFloat() / r.total)
        if (r.loose > 0) Text("${r.loose} sono finiti tra «Singoli e brani sparsi» invece che nel loro album.", color = TextMuted)
        if (r.notTagged > 0) Text("${r.notTagged} non sono mai passati da Picard (niente ID MusicBrainz).", color = TextMuted)
        Text("Tocca una cartella per vedere i brani, e un brano per i suoi tag.", color = TextMuted, fontSize = 13.sp)
    }
}

/** Quanta parte sta bene: verde su rosso. */
@Composable
private fun HealthBar(share: Float) {
    Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Danger.copy(alpha = 0.6f))) {
        Box(Modifier.fillMaxWidth(share.coerceIn(0f, 1f)).height(6.dp).background(Color(0xFF1ED760)))
    }
}

@Composable
private fun FolderRow(f: FolderHealth, expanded: Boolean, onClick: () -> Unit) {
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(f.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${f.tracks.size} da sistemare su ${f.total} · ${f.folder}", color = TextMuted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            HealthBar(f.ok.toFloat() / f.total)
        }
        // L'elenco da portarsi al PC (o da riscaricare).
        IconButton(onClick = {
            val text = "${f.folder}\n" + f.tracks.joinToString("\n") { h ->
                "${h.track.artist.orEmpty()} - ${h.track.title}: ${h.problems.filter { it != TagProblem.NOT_TAGGED }.joinToString { it.label.lowercase() }}"
            }
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
            runCatching { context.startActivity(Intent.createChooser(send, "Condividi l'elenco").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }) { Icon(Icons.Rounded.Share, "Condividi l'elenco", tint = TextMuted) }
        Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, tint = TextMuted)
    }
}

@Composable
private fun HealthRow(h: TrackHealth, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 32.dp, end = 16.dp, top = 6.dp, bottom = 6.dp)) {
        Text(h.track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
        Text(
            listOfNotNull(h.track.artist, h.track.album?.let { "album «$it»" }).joinToString(" · "),
            color = TextMuted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Text(h.problems.filter { it != TagProblem.NOT_TAGGED }.joinToString(" · ") { it.label }, color = Accent, fontSize = 13.sp, maxLines = 2)
    }
}
