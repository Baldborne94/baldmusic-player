package io.github.baldborne94.baldmusic.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.baldborne94.baldmusic.app
import io.github.baldborne94.baldmusic.core.library.Backup
import io.github.baldborne94.baldmusic.core.library.RestorePlan
import io.github.baldborne94.baldmusic.data.Backups
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun formatWhen(millis: Long): String = SimpleDateFormat("d/M/yyyy HH:mm", Locale.ITALY).format(Date(millis))

private fun count(n: Int, one: String, many: String) = if (n == 1) "1 $one" else "$n $many"

/**
 * Il ripristino da un file: si sceglie con la finestra di Android, si legge, si mostra
 * cosa contiene e quanti brani si ritrovano, e solo dopo la conferma si sostituisce.
 */
@Composable
private fun rememberRestore(onMessage: (String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val backups = context.app.backups
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<Pair<Backup, RestorePlan>?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val backup = backups.read(uri)
            if (backup == null) onMessage("Questo file non è un backup di BaldMusic.")
            else pending = backup to backups.plan(backup)
        }
    }
    pending?.let { (backup, plan) ->
        val lib = plan.library
        val playlists = lib.playlists.count { !it.isCollection }
        AlertDialog(
            onDismissRequest = { pending = null },
            containerColor = Surface1,
            title = { Text("Ripristinare questo backup?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        (if (backup.createdAt > 0) "Backup del ${formatWhen(backup.createdAt)}: " else "Nel backup: ") +
                            "${count(lib.liked.size, "brano che ti piace", "brani che ti piacciono")}, " +
                            "${count(playlists, "playlist", "playlist")}, ${count(lib.collections.size, "raccolta", "raccolte")}, " +
                            "ascolti di ${count(lib.plays.size, "brano", "brani")}.",
                    )
                    if (plan.localTracks > 0) {
                        Text(
                            "Sul telefono ritrovo ${plan.found} brani su ${plan.localTracks}." +
                                (if (plan.missing.isNotEmpty()) " Gli altri non ci sono più e restano fuori (es. ${plan.missing.take(3).joinToString { "«${it.title}»" }})." else ""),
                            color = TextMuted,
                        )
                    }
                    plan.settings?.let { s ->
                        Text(
                            "Tornano anche le impostazioni" + (s.serverUrl?.let { " e il server ($it): per ricollegarlo basta la password" } ?: "") + ".",
                            color = TextMuted,
                        )
                    }
                    Text("La tua musica di adesso viene sostituita da quella del backup.", color = TextMuted)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    backups.apply(plan)
                    pending = null
                    onMessage(
                        "Fatto: preferiti, playlist e raccolte sono tornati." +
                            (if (plan.settings?.serverUrl != null && context.app.settings.credentials == null) " Per il server: Impostazioni → Server Navidrome, scrivi la password e collegati." else ""),
                    )
                }) { Text("Ripristina", color = Accent) }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("Annulla", color = TextMuted) } },
        )
    }
    return {
        // Senza accesso alla musica i brani del telefono non si ritroverebbero: uscirebbero tutti.
        if (!context.app.local.hasPermission()) onMessage("Prima consenti l'accesso alla musica (Home → Consenti), poi riprova.")
        else pick.launch(arrayOf("application/json", "application/octet-stream", "text/plain"))
    }
}

/** "La tua musica: backup" nelle Impostazioni. */
@Composable
fun BackupSection() {
    val context = LocalContext.current
    val backups = context.app.backups
    val scope = rememberCoroutineScope()
    val auto by backups.auto.collectAsState()
    val autoOn by backups.autoEnabled.collectAsState()
    var message by remember { mutableStateOf<String?>(null) }
    val restore = rememberRestore { message = it }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        if (uri != null) scope.launch {
            message = if (backups.writeTo(uri).isSuccess) "Backup salvato." else "Non sono riuscito a salvare il backup."
        }
    }

    Text("Backup della tua musica", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    Text(
        "Preferiti, playlist, raccolte (con l'ordine e i brani tolti), ascolti e ricerche. Su un telefono nuovo i brani si ritrovano per cartella e titolo.",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
    )
    if (backups.autoSupported) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Backup automatico", fontWeight = FontWeight.SemiBold)
                Text(
                    when {
                        !autoOn -> "Spento."
                        auto.error != null -> "Ultimo tentativo non riuscito: ${auto.error}"
                        auto.lastAt != null -> "In ${Backups.AUTO_FOLDER}, aggiornato il ${formatWhen(auto.lastAt!!)}. Resta anche se disinstalli l'app."
                        else -> "In ${Backups.AUTO_FOLDER}, alla prossima modifica. Resta anche se disinstalli l'app."
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(
                checked = autoOn,
                onCheckedChange = backups::setAutoEnabled,
                colors = SwitchDefaults.colors(checkedTrackColor = Accent),
            )
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { save.launch(Backups.FILE_NAME) }) { Text("Salva un backup…") }
        OutlinedButton(onClick = restore) { Text("Ripristina…") }
    }
    message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
}

/**
 * In Home quando la tua musica è vuota (app appena installata o telefono nuovo): se c'è un
 * backup, lo si ripristina da qui. Sparisce con "No, grazie" o appena hai qualcosa.
 */
@Composable
fun RestoreBanner(vm: PlayerViewModel) {
    val lib by vm.library.collectAsStateWithLifecycle()
    var dismissed by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("backup", android.content.Context.MODE_PRIVATE) }
    var message by remember { mutableStateOf<String?>(null) }
    val restore = rememberRestore { message = it }
    val empty = lib.liked.isEmpty() && lib.plays.isEmpty() && lib.playlists.none { !it.isCollection }
    if (message == null && (dismissed || !empty || prefs.getBoolean(KEY_BANNER_DISMISSED, false))) return
    Column(Modifier.padding(16.dp).clip(RoundedCornerShape(12.dp)).background(Surface2).padding(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Restore, null, tint = Accent)
            Spacer(Modifier.width(10.dp))
            Text("Hai già usato BaldMusic?", fontWeight = FontWeight.Bold, fontSize = 17.sp)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            message ?: "Se hai reinstallato l'app o cambiato telefono, riprendi preferiti, playlist e raccolte dal backup: di solito è in ${Backups.AUTO_FOLDER}.",
            color = TextMuted,
        )
        Spacer(Modifier.height(12.dp))
        if (message == null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = restore, colors = ButtonDefaults.buttonColors(containerColor = Accent)) { Text("Ripristina") }
                TextButton(onClick = {
                    dismissed = true
                    prefs.edit().putBoolean(KEY_BANNER_DISMISSED, true).apply()
                }) { Text("No, grazie", color = TextMuted) }
            }
        } else {
            TextButton(onClick = { message = null; dismissed = true }) { Text("Ok", color = Accent) }
        }
    }
}

private const val KEY_BANNER_DISMISSED = "banner_dismissed"
