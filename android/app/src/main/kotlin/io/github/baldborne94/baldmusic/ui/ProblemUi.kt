package io.github.baldborne94.baldmusic.ui

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
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import io.github.baldborne94.baldmusic.app
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Prepara il resoconto (legge il registro di Android, quindi fuori dal thread principale) e
 * apre la scelta di Android per mandarlo. Le chiusure salvate restano (al massimo 5).
 */
@Composable
fun rememberSendProblemReport(): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    return remember<() -> Unit> {
        {
            scope.launch {
                val problems = context.app.problems
                val text = withContext(Dispatchers.IO) { problems.report() }
                runCatching { problems.share(text) }
            }
        }
    }
}

/** In Home, dopo una chiusura improvvisa: proponi di mandare il resoconto, una volta. */
@Composable
fun CrashBanner() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("problemi", android.content.Context.MODE_PRIVATE) }
    var crashAt by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(Unit) {
        val last = withContext(Dispatchers.IO) { context.app.problems.lastCrashAt() }
        crashAt = last?.takeIf { it > prefs.getLong(KEY_SEEN, 0L) }
    }
    val send = rememberSendProblemReport()
    val at = crashAt ?: return
    fun seen() {
        prefs.edit { putLong(KEY_SEEN, at) }
        crashAt = null
    }
    Column(Modifier.padding(16.dp).clip(RoundedCornerShape(12.dp)).background(Surface2).padding(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.BugReport, null, tint = Accent)
            Spacer(Modifier.width(10.dp))
            Text("BaldMusic si è chiusa da sola", fontWeight = FontWeight.Bold, fontSize = 17.sp)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "Mandando il resoconto (cosa è successo, versione dell'app e telefono; niente password) si capisce cosa correggere.",
            color = TextMuted,
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { seen(); send() }, colors = ButtonDefaults.buttonColors(containerColor = Accent)) { Text("Manda il resoconto") }
            TextButton(onClick = ::seen) { Text("Non ora", color = TextMuted) }
        }
    }
}

private const val KEY_SEEN = "crash_seen_at"
