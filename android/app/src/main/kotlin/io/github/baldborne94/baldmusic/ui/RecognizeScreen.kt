package io.github.baldborne94.baldmusic.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.baldborne94.baldmusic.app
import io.github.baldborne94.baldmusic.core.acoustid.AcoustIdClient
import io.github.baldborne94.baldmusic.core.acoustid.RecognitionIndex.Decision
import io.github.baldborne94.baldmusic.core.library.FileTags
import io.github.baldborne94.baldmusic.core.library.LocalTrack
import io.github.baldborne94.baldmusic.library.LocalSource
import io.github.baldborne94.baldmusic.library.RecognitionWorker
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * "Riconosci i brani": per i file mai passati da Picard, titolo, artista, album e anno
 * veri dal suono (AcoustID + MusicBrainz). Non tocca i file: le proposte si guardano qui,
 * cartella per cartella, e cambiano la libreria solo quando le applichi; si annullano
 * quando vuoi.
 */
@Composable
fun RecognizeScreen(vm: PlayerViewModel) {
    val context = LocalContext.current
    val app = context.app
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<LocalSource.RecognitionStatus?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    val running by remember { RecognitionWorker.running(context) }.collectAsState(initial = false)
    var key by remember { mutableStateOf(app.settings.acoustIdKey) }
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    var choosing by remember { mutableStateOf<LocalSource.Recognized?>(null) }

    // Mentre lavora, i numeri si aggiornano da soli.
    LaunchedEffect(vm.libraryVersion, refresh, running) {
        while (true) {
            status = app.local.recognitionStatus()
            if (!running) break
            delay(3_000)
        }
    }
    val act: (() -> Unit) -> Unit = { change -> scope.launch { change(); refresh++ } }

    LazyColumn(
        Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color(0xFF3B2A5E), 0.3f to Background)),
        contentPadding = PaddingValues(bottom = 32.dp),
    ) {
        item { PageHeader("Riconosci i brani") }
        item {
            Text(
                "Per i brani mai passati da Picard l'app ascolta i primi due minuti, calcola l'impronta acustica e chiede ad AcoustID di che " +
                    "registrazione si tratta: titolo, artista, album, anno e numero di traccia veri da MusicBrainz. I file non si toccano: " +
                    "le proposte valgono quando le applichi, e si annullano quando vuoi.",
                color = TextMuted, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        item { KeyBox(key) { newKey -> app.settings.acoustIdKey = newKey; key = app.settings.acoustIdKey; app.settings.recognitionProblem = null; refresh++ } }

        val s = status ?: run {
            item { Loading() }
            return@LazyColumn
        }
        item { Progress(s, running, key != null, app.settings.recognitionProblem) { RecognitionWorker.schedule(context, now = true) } }

        val pending = s.items.filter { it.entry.decision == Decision.PENDING }
        val applied = s.items.filter { it.entry.decision == Decision.ACCEPTED }
        val rejected = s.items.filter { it.entry.decision == Decision.REJECTED }

        if (pending.isNotEmpty()) {
            val sure = pending.filter { it.entry.proposal!!.sure }
            item {
                Section("Da controllare (${pending.size})", "Prima i tag di adesso, sotto quelli riconosciuti. Tocca un brano per scegliere un altro disco.") {
                    if (sure.isNotEmpty()) {
                        Button(onClick = { act { app.local.decideRecognized(sure.map { it.track.id }, Decision.ACCEPTED) } }, colors = ButtonDefaults.buttonColors(containerColor = Accent)) {
                            Text("Applica quelli sicuri (${sure.size})", color = Color.Black)
                        }
                    }
                }
            }
            folders(pending).forEach { (folder, list) ->
                item(key = "p$folder") {
                    FolderHeader(folder, list.size, open == "p$folder", onToggle = { open = if (open == "p$folder") null else "p$folder" }) {
                        TextButton(onClick = { act { app.local.decideRecognized(list.map { it.track.id }, Decision.ACCEPTED) } }) { Text("Applica", color = Accent) }
                        TextButton(onClick = { act { app.local.decideRecognized(list.map { it.track.id }, Decision.REJECTED) } }) { Text("Scarta", color = TextMuted) }
                    }
                }
                if (open == "p$folder") items(list, key = { "pt" + it.track.id }) { r ->
                    ProposalRow(r, onClick = { choosing = r }) {
                        TextButton(onClick = { act { app.local.decideRecognized(listOf(r.track.id), Decision.ACCEPTED) } }) { Text("Applica", color = Accent) }
                        TextButton(onClick = { act { app.local.decideRecognized(listOf(r.track.id), Decision.REJECTED) } }) { Text("Scarta", color = TextMuted) }
                    }
                }
            }
        }

        if (applied.isNotEmpty()) {
            item {
                Section("Applicati (${applied.size})", "Nella libreria valgono i tag riconosciuti. «Annulla» li rimette da controllare e torna ai tag del file.") {
                    OutlinedButton(onClick = { act { app.local.decideRecognized(applied.map { it.track.id }, Decision.PENDING) } }) { Text("Annulla tutti") }
                }
            }
            folders(applied).forEach { (folder, list) ->
                item(key = "a$folder") {
                    FolderHeader(folder, list.size, open == "a$folder", onToggle = { open = if (open == "a$folder") null else "a$folder" }) {
                        TextButton(onClick = { act { app.local.decideRecognized(list.map { it.track.id }, Decision.PENDING) } }) { Text("Annulla", color = TextMuted) }
                    }
                }
                if (open == "a$folder") items(list, key = { "at" + it.track.id }) { r ->
                    ProposalRow(r, onClick = { choosing = r }) {
                        TextButton(onClick = { act { app.local.decideRecognized(listOf(r.track.id), Decision.PENDING) } }) { Text("Annulla", color = TextMuted) }
                    }
                }
            }
        }

        if (rejected.isNotEmpty() || s.notFound > 0) {
            item {
                Section("Non usati", buildString {
                    if (s.notFound > 0) append("${s.notFound} brani non riconosciuti (AcoustID non li conosce, o il file è troppo corto). ")
                    if (rejected.isNotEmpty()) append("${rejected.size} proposte scartate.")
                }) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (rejected.isNotEmpty()) OutlinedButton(onClick = { act { app.local.decideRecognized(rejected.map { it.track.id }, Decision.PENDING) } }) { Text("Rivedi le scartate") }
                        if (s.notFound > 0 && key != null) OutlinedButton(onClick = {
                            act {
                                // Tornano da riconoscere: AcoustID impara brani nuovi col tempo.
                                app.local.retryNotFound()
                                RecognitionWorker.schedule(context, now = true)
                            }
                        }) { Text("Riprova i non riconosciuti") }
                    }
                }
            }
        }
    }

    choosing?.let { r ->
        val p = r.entry.proposal!!
        AlertDialog(
            onDismissRequest = { choosing = null },
            containerColor = Surface1,
            title = { Text(r.track.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = {
                Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Somiglianza del suono: ${(p.score * 100).toInt()}%. Su quale disco va?", color = TextMuted, fontSize = 13.sp)
                    p.candidates.forEachIndexed { i, c ->
                        Column(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                                .background(if (i == p.chosen) Surface2 else Color.Transparent)
                                .clickable { act { app.local.chooseRecognized(r.track.id, i) }; choosing = null }
                                .padding(10.dp),
                        ) {
                            Text(c.tags.album ?: "Senza titolo", fontWeight = FontWeight.SemiBold)
                            Text(describe(c.tags), color = TextMuted, fontSize = 13.sp)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { choosing = null }) { Text("Chiudi", color = TextMuted) } },
        )
    }
}

private fun folders(list: List<LocalSource.Recognized>) = list.groupBy { it.track.folder }.toSortedMap(compareBy { it.lowercase() })

private fun describe(t: FileTags): String = listOfNotNull(
    t.albumArtist ?: t.artist,
    (t.originalYear.takeIf { it > 0 } ?: t.year.takeIf { it > 0 })?.toString(),
    t.releaseTypes.joinToString(", ") { kindName(it) }.ifBlank { null },
    t.track.takeIf { it > 0 }?.let { if (t.disc > 1) "disco ${t.disc}, traccia $it" else "traccia $it" },
).joinToString(" · ")

private fun kindName(type: String) = when (type) {
    "album" -> "album"
    "single" -> "singolo"
    "ep" -> "EP"
    "compilation" -> "raccolta"
    "soundtrack" -> "colonna sonora"
    "live" -> "dal vivo"
    "remix" -> "remix"
    else -> type
}

@Composable
private fun KeyBox(key: String?, onSave: (String?) -> Unit) {
    val context = LocalContext.current
    var editing by rememberSaveable { mutableStateOf(key == null) }
    var text by rememberSaveable { mutableStateOf(key.orEmpty()) }
    Column(
        Modifier.padding(horizontal = 16.dp, vertical = 8.dp).clip(RoundedCornerShape(12.dp)).background(Surface2).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Chiave di AcoustID", fontWeight = FontWeight.Bold, fontSize = 17.sp)
        if (!editing && key != null) {
            Text("Impostata ✓", color = TextMuted)
            TextButton(onClick = { editing = true }) { Text("Cambia", color = Accent) }
            return@Column
        }
        Text(
            "AcoustID è gratuito ma vuole una chiave, una volta: apri la pagina, accedi (anche con l'account di MusicBrainz o Google), " +
                "crea un'applicazione chiamata «BaldMusic» e copia qui la chiave che ti dà.",
            color = TextMuted, fontSize = 14.sp,
        )
        OutlinedButton(onClick = {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(AcoustIdClient.NEW_KEY_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }) { Text("Crea la chiave") }
        OutlinedTextField(text, { text = it.trim() }, Modifier.fillMaxWidth(), label = { Text("Chiave") }, singleLine = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onSave(text); editing = false }, enabled = text.length >= 6, colors = ButtonDefaults.buttonColors(containerColor = Accent)) { Text("Salva", color = Color.Black) }
            if (key != null) TextButton(onClick = { text = key; editing = false }) { Text("Annulla", color = TextMuted) }
        }
    }
}

@Composable
private fun Progress(s: LocalSource.RecognitionStatus, running: Boolean, hasKey: Boolean, problem: String?, onStart: () -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (s.total == 0) {
            Text("Tutti i brani del telefono hanno già i tag di MusicBrainz: niente da riconoscere.", color = TextMuted)
            return@Column
        }
        val done = s.total - s.toDo
        Text("$done brani su ${s.total} ascoltati${if (s.toDo > 0) ", ${s.toDo} da fare" else ""}.", fontWeight = FontWeight.SemiBold)
        LinearProgressIndicator(progress = { if (s.total > 0) done.toFloat() / s.total else 1f }, Modifier.fillMaxWidth(), color = Accent)
        problem?.let { Text(it, color = Color(0xFFFF8A80), fontSize = 14.sp) }
        when {
            running -> Text("Sto riconoscendo… (si può uscire: continua in sottofondo)", color = TextMuted, fontSize = 14.sp)
            !hasKey -> Text("Serve la chiave qui sopra.", color = TextMuted, fontSize = 14.sp)
            s.toDo > 0 -> {
                Text("Da solo lo fa quando il telefono è in carica. Ogni brano vuole qualche secondo e un po' di internet.", color = TextMuted, fontSize = 14.sp)
                Button(onClick = onStart, colors = ButtonDefaults.buttonColors(containerColor = Accent)) { Text("Riconosci adesso", color = Color.Black) }
            }
        }
    }
}

@Composable
private fun Section(title: String, text: String, actions: @Composable () -> Unit) {
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        if (text.isNotBlank()) Text(text, color = TextMuted, fontSize = 13.sp)
        actions()
    }
}

@Composable
private fun FolderHeader(folder: String, count: Int, expanded: Boolean, onToggle: () -> Unit, actions: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(folder.substringAfterLast('/').ifBlank { folder }, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("$count brani · $folder", color = TextMuted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        actions()
        Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, tint = TextMuted)
    }
}

@Composable
private fun ProposalRow(r: LocalSource.Recognized, onClick: () -> Unit, actions: @Composable () -> Unit) {
    val p = r.entry.proposal!!
    val now = p.tags
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 28.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(before(r.track), color = TextMuted, fontSize = 13.sp, textDecoration = TextDecoration.LineThrough, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(now.title, now.artist).joinToString(" · "), fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(now.album, (now.originalYear.takeIf { it > 0 } ?: now.year.takeIf { it > 0 })?.toString(), "${(p.score * 100).toInt()}%").joinToString(" · "),
                color = if (p.sure) Accent else Color(0xFFFFC46B), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        actions()
    }
}

private fun before(t: LocalTrack) = listOfNotNull(t.title, t.artist, t.album?.let { "«$it»" }, t.shownYear.takeIf { it > 0 }?.toString()).joinToString(" · ")
