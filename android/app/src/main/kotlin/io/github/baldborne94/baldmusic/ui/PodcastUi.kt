package io.github.baldborne94.baldmusic.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import io.github.baldborne94.baldmusic.core.podcast.PodcastSource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.clip
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.background
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.baldborne94.baldmusic.app
import io.github.baldborne94.baldmusic.core.podcast.Episode
import io.github.baldborne94.baldmusic.core.podcast.Podcast
import io.github.baldborne94.baldmusic.core.podcast.PodcastLibrary
import kotlinx.coroutines.launch

/**
 * Impostazioni → Podcast: incolla il link RSS (quello privato di Patreon, o di qualsiasi
 * podcast) e le puntate arrivano nella Libreria, in Home e in Android Auto.
 */
@Composable
fun PodcastSection() {
    val podcasts = LocalContext.current.app.podcasts
    val scope = rememberCoroutineScope()
    val library by podcasts.library.collectAsState()
    var shows by remember { mutableStateOf<List<Podcast>>(emptyList()) }
    LaunchedEffect(library.feeds) { shows = podcasts.shows() }
    var link by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    // Per l'avviso delle puntate nuove: Android 13+ chiede il permesso delle notifiche.
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Podcast", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(
            "Incolla il link RSS di un podcast. Per quelli di Patreon serve il link privato: sul sito o nell'app di Patreon apri il podcast, " +
                "«Il tuo abbonamento» (o il menu delle puntate audio) → «Ascolta in altre app» → «Copia link». " +
                "Le puntate arrivano in Libreria, in Home e in Android Auto, dalla più recente, e ognuna riparte da dove l'avevi lasciata. " +
                "Ogni 2 ore l'app guarda se c'è una puntata nuova e te lo dice con una notifica. " +
                "Il link è personale: resta solo sul telefono (non va nel backup né nei resoconti).",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        shows.forEach { show ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(show.title, fontWeight = FontWeight.SemiBold)
                    Text(
                        listOfNotNull(show.author, if (show.episodes.size == 1) "1 puntata" else "${show.episodes.size} puntate").joinToString(" · "),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (library.broken().any { it.id == show.id }) {
                        Text(
                            "Il link non funziona più: copialo di nuovo e incollalo qui sotto. Le puntate e dove eri arrivato restano.",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                IconButton(onClick = { podcasts.unfollow(show.id); status = "Non segui più «${show.title}»." }) {
                    Icon(Icons.Rounded.Close, "Smetti di seguire ${show.title}")
                }
            }
        }
        if (shows.isNotEmpty()) {
            val downloading by podcasts.downloading.collectAsState()
            val stored by podcasts.stored.collectAsState()
            val count = stored.size
            val bytes = remember(stored) { stored.values.sum() }
            Text("Puntate sul telefono", fontWeight = FontWeight.SemiBold)
            Text(
                "Le ultime puntate non ascoltate di ogni podcast si scaricano da sole col Wi‑Fi, così le senti anche senza rete. " +
                    "Quelle ascoltate si tolgono da sole; un'altra puntata la scarichi dal suo menu ⋮.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PodcastLibrary.AUTO_DOWNLOAD_CHOICES.forEach { n ->
                    Chip(if (n == 0) "Spento" else if (n == 1) "L'ultima" else "Le ultime $n", selected = library.autoDownload == n) { podcasts.setAutoDownload(n) }
                }
            }
            Text(
                when {
                    downloading.isNotEmpty() -> "Scarico ${if (downloading.size == 1) "una puntata" else "${downloading.size} puntate"}…"
                    count == 0 -> "Nessuna puntata sul telefono."
                    else -> "${if (count == 1) "1 puntata" else "$count puntate"} sul telefono, ${android.text.format.Formatter.formatShortFileSize(LocalContext.current, bytes)}."
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        OutlinedTextField(
            link, { link = it; status = null },
            Modifier.fillMaxWidth(),
            label = { Text("Link RSS del podcast") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )
        Button(
            enabled = !busy && link.isNotBlank(),
            colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.Black),
            onClick = {
                busy = true
                status = "Leggo il podcast…"
                scope.launch {
                    status = podcasts.follow(link).fold(
                        onSuccess = { show ->
                            link = ""
                            if (Build.VERSION.SDK_INT >= 33) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                            "Fatto: «${show.title}», ${show.episodes.size} puntate. Le trovi in Libreria → Podcast."
                        },
                        onFailure = { it.message ?: "Non sono riuscito a leggere il podcast." },
                    )
                    busy = false
                }
            },
        ) { Text(if (busy) "Leggo…" else "Aggiungi podcast") }
        status?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
    }
}

/**
 * In Home, quando il link di un podcast non funziona più ([PodcastLibrary.broken]): di solito
 * Patreon l'ha rinnovato o l'abbonamento è scaduto. Incollando il link nuovo si tiene tutto.
 */
@Composable
fun PodcastLinkBanner(onFix: () -> Unit) {
    val podcasts = LocalContext.current.app.podcasts
    val library by podcasts.library.collectAsState()
    var broken by remember { mutableStateOf<List<Podcast>>(emptyList()) }
    LaunchedEffect(library.feeds) { broken = podcasts.broken() }
    var later by rememberSaveable { mutableStateOf(false) }
    if (broken.isEmpty() || later) return
    val names = broken.joinToString(", ") { "«${it.title}»" }
    Column(Modifier.padding(16.dp).clip(RoundedCornerShape(12.dp)).background(Surface2).padding(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.LinkOff, null, tint = Accent)
            Spacer(Modifier.width(10.dp))
            Text(if (broken.size == 1) "Il link di $names non funziona più" else "I link di $names non funzionano più", fontWeight = FontWeight.Bold, fontSize = 17.sp)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "Il server non dà più le puntate con questo link: su Patreon succede quando il link viene rinnovato o l'abbonamento scade. " +
                "Copialo di nuovo («Ascolta in altre app» → «Copia link») e incollalo in Impostazioni → Podcast: puntate, ascoltate e dove eri arrivato restano.",
            color = TextMuted,
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onFix, colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.Black)) { Text("Apri le impostazioni") }
            TextButton(onClick = { later = true }) { Text("Più tardi", color = TextMuted) }
        }
    }
}

/** Gli indirizzi nel testo di una puntata, da toccare. */
private val URL = Regex("https?://[^\\s()<>]+[^\\s()<>.,;:!?»”']")

/**
 * «Informazioni sulla puntata»: il testo che il creatore scrive per ogni puntata (di cosa si
 * parla, ospiti, link), dal feed. I link si aprono nel browser.
 */
@Composable
fun EpisodeInfoDialog(mediaId: String, onDismiss: () -> Unit) {
    val app = LocalContext.current.app
    val library by app.podcasts.library.collectAsState()
    // Letta in sottofondo: la descrizione sta in un file a parte.
    var found by remember(mediaId) { mutableStateOf<Pair<Podcast, Episode>?>(null) }
    LaunchedEffect(mediaId) {
        found = app.podcasts.episodeOf(mediaId)
        // Il podcast non c'è più (smesso di seguire): niente da mostrare.
        if (found == null) onDismiss()
    }
    val (show, episode) = found ?: return
    val key = PodcastSource.keyOf(show, episode)
    val details = remember(library.progress[key], episode) { app.podcastSource.subtitle(episode, library.progress[key]) }
    val linkStyle = TextLinkStyles(SpanStyle(color = Accent, textDecoration = TextDecoration.Underline))
    val text = remember(episode.description) {
        val d = episode.description.orEmpty()
        buildAnnotatedString {
            var last = 0
            URL.findAll(d).forEach { m ->
                append(d.substring(last, m.range.first))
                withLink(LinkAnnotation.Url(m.value, linkStyle)) { append(m.value) }
                last = m.range.last + 1
            }
            append(d.substring(last))
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface1,
        title = { Text(episode.title, fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(listOf(show.title, details).filter { it.isNotEmpty() }.joinToString(" · "), color = TextMuted, style = MaterialTheme.typography.bodySmall)
                if (text.isEmpty()) Text("Per questa puntata il podcast non ha scritto una descrizione.", color = TextMuted)
                else Text(text, style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Chiudi", color = Accent) } },
    )
}
