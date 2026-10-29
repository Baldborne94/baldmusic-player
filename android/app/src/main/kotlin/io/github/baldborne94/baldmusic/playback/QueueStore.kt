package io.github.baldborne94.baldmusic.playback

import io.github.baldborne94.baldmusic.core.library.QueueSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** La coda salvata per la ripresa (`coda.json`, [QueueSnapshot]): scritta in sottofondo, o subito alla chiusura. */
internal class QueueStore(private val file: File, private val scope: CoroutineScope) {
    private var pending: Job? = null

    fun save(snapshot: QueueSnapshot, now: Boolean) {
        pending?.cancel()
        if (now) write(snapshot) else pending = scope.launch(Dispatchers.IO) { write(snapshot) }
    }

    private fun write(snapshot: QueueSnapshot) {
        runCatching {
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(snapshot.toJson())
            tmp.renameTo(file)
        }
    }

    suspend fun read(): QueueSnapshot? =
        QueueSnapshot.fromJson(withContext(Dispatchers.IO) { runCatching { file.readText() }.getOrNull() })
}
