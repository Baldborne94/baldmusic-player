package io.github.baldborne94.baldmusic.ui

import android.content.ContentUris
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import io.github.baldborne94.baldmusic.app
import io.github.baldborne94.baldmusic.library.LocalSource
import java.text.DateFormat
import java.util.Date

/**
 * "Brani nel cestino": i file musicali nel cestino di Android (buttati da BaldMusic o da
 * altre app), da ripescare uno per uno o tutti. Tornano nella loro cartella e, nella sua
 * raccolta, al posto della copia che li aveva sostituiti.
 */
@Composable
fun TrashScreen(vm: PlayerViewModel) {
    val context = LocalContext.current
    var files by remember { mutableStateOf<List<LocalSource.TrashedFile>?>(null) }
    var restoring by remember { mutableStateOf<Set<Long>>(emptySet()) }
    LaunchedEffect(vm.libraryVersion) { files = context.app.local.trashed() }

    // La finestra di Android "Ripristinare?": come per buttarli, l'app non può da sola.
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val ids = restoring
        restoring = emptySet()
        if (result.resultCode == android.app.Activity.RESULT_OK && ids.isNotEmpty()) vm.restoreTrashed(ids)
    }
    fun restore(ids: Set<Long>) {
        if (ids.isEmpty() || Build.VERSION.SDK_INT < 30) return
        val uris = ids.map { ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, it) }
        val request = MediaStore.createTrashRequest(context.contentResolver, uris, false)
        restoring = ids
        launcher.launch(IntentSenderRequest.Builder(request.intentSender).build())
    }

    LazyColumn(
        Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color(0xFF4A3A1F), 0.3f to Background)),
        contentPadding = PaddingValues(bottom = 32.dp),
    ) {
        item { PageHeader("Brani nel cestino") }
        val list = files ?: run {
            item { Loading() }
            return@LazyColumn
        }
        if (Build.VERSION.SDK_INT < 30) {
            item { Message("Il cestino di Android c'è da Android 11.") }
            return@LazyColumn
        }
        if (list.isEmpty()) {
            item { Message("Il cestino è vuoto.") }
            return@LazyColumn
        }
        item {
            Column(
                Modifier.padding(horizontal = 16.dp, vertical = 12.dp).clip(RoundedCornerShape(12.dp)).background(Surface2).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "Restano qui 30 giorni, poi Android li cancella. Ripescati, tornano nella loro cartella e, nella sua raccolta, " +
                        "al posto della copia che li aveva sostituiti.",
                    color = TextMuted,
                )
                Button(onClick = { restore(list.mapTo(HashSet()) { it.id }) }, colors = ButtonDefaults.buttonColors(containerColor = Accent)) {
                    Text(if (list.size == 1) "Ripristina il brano" else "Ripristina tutti (${list.size})", color = Color.Black)
                }
            }
        }
        items(list, key = { it.id }) { f ->
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(f.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(f.artist, f.folder.substringAfterLast('/').ifEmpty { f.folder }).joinToString(" · "),
                        color = TextMuted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    if (f.expiresAt > 0) {
                        Text("Si cancella il ${DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(f.expiresAt * 1000))}", color = TextMuted, fontSize = 12.sp)
                    }
                }
                IconButton(onClick = { restore(setOf(f.id)) }) { Icon(Icons.Rounded.Restore, "Ripristina", tint = Accent) }
            }
        }
    }
}
