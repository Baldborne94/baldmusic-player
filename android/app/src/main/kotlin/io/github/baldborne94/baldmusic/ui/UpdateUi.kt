package io.github.baldborne94.baldmusic.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.baldborne94.baldmusic.core.update.ReleaseInfo
import io.github.baldborne94.baldmusic.core.update.ReleaseNotes
import io.github.baldborne94.baldmusic.update.Updater
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Il tasto "Aggiorna": la prima volta Android chiede di consentire a BaldMusic di installare
 * app (una schermata delle impostazioni); al ritorno, se è consentito, parte l'aggiornamento.
 */
@Composable
private fun rememberInstall(vm: PlayerViewModel): (ReleaseInfo) -> Unit {
    var askFor by remember { mutableStateOf<ReleaseInfo?>(null) }
    val settings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val info = askFor
        askFor = null
        if (info != null && vm.updater.canInstall()) vm.installUpdate(info)
    }
    var explain by remember { mutableStateOf<ReleaseInfo?>(null) }
    explain?.let { info ->
        AlertDialog(
            onDismissRequest = { explain = null },
            containerColor = Surface1,
            title = { Text("Consenti gli aggiornamenti") },
            text = {
                Text(
                    "Android chiede una volta sola il permesso: nella schermata che si apre attiva «Consenti da questa fonte», poi torna indietro e l'aggiornamento parte.",
                    color = TextMuted,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    explain = null
                    askFor = info
                    settings.launch(vm.updater.permissionIntent())
                }) { Text("Apri impostazioni", color = Accent) }
            },
            dismissButton = { TextButton(onClick = { explain = null }) { Text("Annulla", color = TextMuted) } },
        )
    }
    return { info -> if (vm.updater.canInstall()) vm.installUpdate(info) else explain = info }
}

/** In cima alla Home quando c'è una versione nuova (finché non si dice "Più tardi"). */
@Composable
fun UpdateBanner(vm: PlayerViewModel) {
    val state by vm.updater.state.collectAsState()
    val skipped by vm.updater.skipped.collectAsState()
    val info = when (val s = state) {
        is Updater.State.Available -> s.info.takeIf { it.versionCode.toLong() != skipped }
        is Updater.State.Downloading -> s.info
        is Updater.State.Installing -> s.info
        is Updater.State.Failed -> s.info?.takeIf { it.isNewerThan(vm.updater.installed) }
        else -> null
    } ?: return
    val install = rememberInstall(vm)
    Column(Modifier.padding(16.dp).clip(RoundedCornerShape(12.dp)).background(Surface2).padding(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.SystemUpdate, null, tint = Accent)
            Spacer(Modifier.width(10.dp))
            Text("È uscita la versione ${info.versionName.ifBlank { "${info.versionCode}" }}", fontWeight = FontWeight.Bold, fontSize = 17.sp)
        }
        Spacer(Modifier.height(6.dp))
        info.changesBetween(vm.updater.installed).flatMap { it.changes }.distinct().take(3).forEach {
            Text("• $it", color = TextMuted, maxLines = 2)
        }
        Spacer(Modifier.height(12.dp))
        UpdateProgress(state)
        if (state is Updater.State.Available || state is Updater.State.Failed) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { install(info) }, colors = ButtonDefaults.buttonColors(containerColor = Accent)) { Text(if (state is Updater.State.Failed) "Riprova" else "Aggiorna") }
                if (state is Updater.State.Failed) {
                    // Dal browser l'installazione passa da Android e non dall'app: a volte va dove l'altra no.
                    val context = LocalContext.current
                    TextButton(onClick = {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Updater.RELEASE_PAGE)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    }) { Text("Pagina della release", color = Accent) }
                } else {
                    TextButton(onClick = { vm.updater.later(info) }) { Text("Più tardi", color = TextMuted) }
                }
            }
            if (state is Updater.State.Failed) TextButton(onClick = { vm.updater.later(info) }) { Text("Più tardi", color = TextMuted) }
        }
    }
}

/** Download in corso, installazione o errore. */
@Composable
private fun UpdateProgress(state: Updater.State) {
    when (state) {
        is Updater.State.Downloading -> {
            Text(state.progress?.let { "Scarico l'aggiornamento… ${(it * 100).toInt()}%" } ?: "Scarico l'aggiornamento…", color = TextMuted)
            Spacer(Modifier.height(6.dp))
            val p = state.progress
            if (p != null) LinearProgressIndicator(progress = { p }, Modifier.fillMaxWidth(), color = Accent)
            else LinearProgressIndicator(Modifier.fillMaxWidth(), color = Accent)
        }
        is Updater.State.Installing -> Text("Conferma nella finestra di Android: l'app si chiude e si riapre aggiornata.", color = TextMuted)
        is Updater.State.Failed -> {
            Text(state.message, color = Color(0xFFFF8A80))
            Spacer(Modifier.height(8.dp))
        }
        else -> Unit
    }
}

/** "Aggiornamenti" nelle Impostazioni: versione installata, controllo a mano, novità. */
@Composable
fun UpdateSection(vm: PlayerViewModel) {
    val context = LocalContext.current
    val state by vm.updater.state.collectAsState()
    val install = rememberInstall(vm)
    Text("Aggiornamenti", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    Text("Versione installata: ${vm.updater.installedName.ifBlank { "${vm.updater.installed}" }}", color = MaterialTheme.colorScheme.onSurfaceVariant)
    when (val s = state) {
        Updater.State.Checking -> Text("Controllo…", color = TextMuted)
        is Updater.State.UpToDate ->
            Text(if (s.info == null) "Non riesco a controllare: serve internet." else "Hai già l'ultima versione ✓", color = TextMuted)
        is Updater.State.Available -> {
            Text("È uscita la versione ${s.info.versionName.ifBlank { "${s.info.versionCode}" }}${s.info.size?.let { " (${it / 1_000_000} MB)" } ?: ""}", fontWeight = FontWeight.SemiBold)
            NotesList(s.info.changesBetween(vm.updater.installed))
        }
        else -> UpdateProgress(s)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val info = when (val s = state) {
            is Updater.State.Available -> s.info
            is Updater.State.Failed -> s.info?.takeIf { it.isNewerThan(vm.updater.installed) }
            else -> null
        }
        if (info != null) {
            Button(onClick = { install(info) }, colors = ButtonDefaults.buttonColors(containerColor = Accent)) { Text("Aggiorna") }
        } else {
            OutlinedButton(
                onClick = { vm.checkUpdates(force = true) },
                enabled = state !is Updater.State.Checking && state !is Updater.State.Downloading && state !is Updater.State.Installing,
            ) { Text("Controlla aggiornamenti") }
        }
        TextButton(onClick = {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Updater.RELEASE_PAGE)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }) { Text("Pagina della release", color = TextMuted) }
    }
    Text(
        "L'app controlla da sola ogni qualche ora. L'aggiornamento si installa sopra: preferiti, playlist e impostazioni restano.",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
    )
}

/** Le novità, versione per versione. */
@Composable
private fun NotesList(notes: List<ReleaseNotes>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        notes.forEach { n ->
            Text(listOfNotNull("Versione 0.1.${n.versionCode}", formatDate(n.date)).joinToString(" · "), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            n.changes.forEach { Text("• $it", color = TextMuted, fontSize = 14.sp) }
        }
    }
}

private fun formatDate(iso: String): String? =
    runCatching { LocalDate.parse(iso).format(DateTimeFormatter.ofPattern("d/M/yyyy")) }.getOrNull()

/** Una volta dopo l'aggiornamento: cosa c'è di nuovo. */
@Composable
fun WhatsNewDialog(vm: PlayerViewModel) {
    val notes by vm.updater.whatsNew.collectAsState()
    if (notes.isEmpty()) return
    AlertDialog(
        onDismissRequest = { vm.updater.dismissWhatsNew() },
        containerColor = Surface1,
        title = { Text("Novità di BaldMusic") },
        text = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) { NotesList(notes) } },
        confirmButton = { TextButton(onClick = { vm.updater.dismissWhatsNew() }) { Text("Ok", color = Accent) } },
    )
}
