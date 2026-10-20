package io.github.baldborne94.baldmusic.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.foundation.background
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
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.produceState
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
import io.github.baldborne94.baldmusic.core.library.Crossfade
import io.github.baldborne94.baldmusic.core.library.MediaId
import io.github.baldborne94.baldmusic.core.library.ReplayGainMode
import io.github.baldborne94.baldmusic.core.library.RemoteSource
import io.github.baldborne94.baldmusic.core.subsonic.Credentials
import io.github.baldborne94.baldmusic.core.subsonic.SubsonicClient
import io.github.baldborne94.baldmusic.data.LyricsWorker
import io.github.baldborne94.baldmusic.library.LocalSource
import io.github.baldborne94.baldmusic.library.LoudnessWorker
import io.github.baldborne94.baldmusic.playback.kind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val ui = LocalUi.current
        var lyricsOnline by remember { mutableStateOf(vm.lyricsOnline) }
        Text("Ascolto", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        SettingsLink("Le tue statistiche", "Quanto ascolti, i tuoi artisti e brani preferiti") { ui.push(Screen.Stats) }
        SettingsLink("Pulizia della libreria", "Doppioni e anteprime di 30 secondi da buttare") { ui.push(Screen.Cleanup) }
        SettingsLink("Salute della libreria", "Quali file hanno i tag da sistemare, cartella per cartella") { ui.push(Screen.Health) }
        SettingsLink("Riconosci i brani", "Titolo, artista, album e anno veri dal suono (AcoustID), senza toccare i file") { ui.push(Screen.Recognize) }
        val sendReport = rememberSendProblemReport()
        SettingsLink("Segnala un problema", "Manda cosa è successo (errori, versione, telefono; niente password) a chi sviluppa l'app") { sendReport() }
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            SettingsLink("Brani nel cestino", "Ripesca i brani buttati negli ultimi 30 giorni") { ui.push(Screen.Trash) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Testi da internet", fontWeight = FontWeight.SemiBold)
                Text("Cerca i testi sincronizzati su LRCLIB. Spento: solo quelli già scaricati.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            androidx.compose.material3.Switch(
                checked = lyricsOnline,
                onCheckedChange = { lyricsOnline = it; vm.lyricsOnline = it },
                colors = androidx.compose.material3.SwitchDefaults.colors(checkedTrackColor = Accent),
            )
        }
        if (lyricsOnline) OfflineLyrics()
        ReplayGainSetting()
        CrossfadeSetting()
        var continueListening by remember { mutableStateOf(context.app.settings.continueListening) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Continua ad ascoltare", fontWeight = FontWeight.SemiBold)
                Text(
                    "Finita una raccolta, un album o una playlist, va avanti con musica simile invece di fermarsi (anche in Android Auto).",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            androidx.compose.material3.Switch(
                checked = continueListening,
                onCheckedChange = { continueListening = it; context.app.settings.continueListening = it },
                colors = androidx.compose.material3.SwitchDefaults.colors(checkedTrackColor = Accent),
            )
        }
        Spacer(Modifier.height(12.dp))
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
            Text("I brani arrivano dal server nella qualità originale, senza conversioni.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
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
        BackupSection()

        Spacer(Modifier.height(12.dp))
        UpdateSection(vm)

        Spacer(Modifier.height(12.dp))
        Text("Informazioni", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(
            "BaldMusic è open source: github.com/Baldborne94/baldmusic-player",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/** Dissolvenza tra i brani, come i DJ: Spenta, 3, 6, 9 o 12 secondi. */
@Composable
private fun CrossfadeSetting() {
    val settings = LocalContext.current.app.settings
    val seconds by settings.crossfade.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Dissolvenza tra i brani", fontWeight = FontWeight.SemiBold)
        Text(
            "Come i DJ: negli ultimi secondi il brano che finisce si abbassa mentre il successivo parte e sale. " +
                "Mai tra due brani dello stesso album (dal vivo e colonne sonore continue restano attaccati) e solo quando un brano finisce da solo.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            Crossfade.CHOICES.forEach { s ->
                FilterChip(
                    selected = seconds == s,
                    onClick = { settings.setCrossfade(s) },
                    label = { Text(if (s == 0) "Spenta" else "$s s") },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Accent, selectedLabelColor = androidx.compose.ui.graphics.Color.Black),
                )
            }
        }
    }
}

/** Volume uguale tra i brani: Album, Brano o Spento, e quanti brani del telefono hanno i valori. */
@Composable
private fun ReplayGainSetting() {
    val context = LocalContext.current
    val settings = context.app.settings
    val mode by settings.replayGain.collectAsState()
    // Brani senza ReplayGain nei tag: quanti ne ha già misurati l'app, su quanti.
    val measured by produceState<Pair<Int, Int>?>(null) { value = runCatching { context.app.local.loudnessStatus() }.getOrNull() }
    var measuringNow by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Volume uguale tra i brani", fontWeight = FontWeight.SemiBold)
        Text(
            "Abbassa i brani più forti allo stesso livello degli altri, con il ReplayGain scritto nei file o, se manca, il volume misurato dall'app. " +
                "Album: dentro un disco restano le differenze volute. Brano: ogni brano allo stesso livello, per mix e raccolte.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(ReplayGainMode.ALBUM to "Album", ReplayGainMode.TRACK to "Brano", ReplayGainMode.OFF to "Spento").forEach { (m, label) ->
                FilterChip(
                    selected = mode == m,
                    onClick = { settings.setReplayGain(m) },
                    label = { Text(label) },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Accent, selectedLabelColor = androidx.compose.ui.graphics.Color.Black),
                )
            }
        }
        measured?.let { (done, total) ->
            Text(
                when {
                    total == 0 -> "Tutti i brani del telefono hanno il volume nei tag."
                    done == total -> "Per i $total brani senza tag il volume l'ha misurato l'app."
                    measuringNow -> "Misura in corso: $done su $total. Puoi usare il telefono intanto."
                    else -> "Volume misurato per $done brani su $total senza tag: gli altri si misurano da soli mentre il telefono è in carica."
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            if (total > done && !measuringNow) {
                OutlinedButton(onClick = {
                    LoudnessWorker.schedule(context, now = true)
                    measuringNow = true
                }) { Text("Misura adesso (consuma batteria)") }
            }
        }
    }
}

/** I testi salvati per quando non c'è rete, e "Scarica adesso". */
@Composable
private fun OfflineLyrics() {
    val context = LocalContext.current
    val running by remember { LyricsWorker.running(context) }.collectAsState(initial = false)
    var saved by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(running) {
        // Mentre scarica il numero cresce: si ricontrolla ogni tanto.
        do {
            saved = withContext(Dispatchers.IO) { context.app.lyrics.savedCount() }
            if (running) delay(3_000)
        } while (running)
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            (saved?.let { if (it == 1) "1 testo salvato" else "$it testi salvati" } ?: "Testi salvati") +
                " sul telefono: si vedono anche senza rete. Quelli dei brani che ascolti di più, dei preferiti e delle raccolte si scaricano da soli, in carica col Wi‑Fi.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(onClick = { LyricsWorker.now(context) }, enabled = !running) {
            Text(if (running) "Scaricamento in corso…" else "Scarica adesso")
        }
    }
}

@Composable
private fun SettingsLink(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Surface1).clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = TextMuted)
    }
}
