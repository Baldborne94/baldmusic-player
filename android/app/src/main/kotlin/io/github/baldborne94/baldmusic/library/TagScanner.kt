@file:OptIn(UnstableApi::class)

package io.github.baldborne94.baldmusic.library

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.Metadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.MetadataRetriever
import androidx.media3.extractor.metadata.id3.InternalFrame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import io.github.baldborne94.baldmusic.core.library.FileStamp
import io.github.baldborne94.baldmusic.core.library.FileTags
import io.github.baldborne94.baldmusic.core.library.RawTag
import io.github.baldborne94.baldmusic.core.library.TagIndex
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.withTimeout
import java.io.File

/**
 * Legge i tag completi dai file (quelli che Picard scrive e MediaStore non espone:
 * artista dell'album, artisti uno per uno, ID MusicBrainz, tipo di uscita, anno
 * originale, ReplayGain) e li tiene in un indice su disco. Ogni file si legge una
 * volta sola, poi solo se cambia. Usa gli estrattori di Media3, gli stessi che
 * suonano il file: MP3, FLAC, M4A, Ogg e Opus.
 */
class TagScanner(private val context: Context) {

    private val file = File(context.filesDir, "tags.json")

    val index: TagIndex by lazy { TagIndex.fromJson(runCatching { file.readText() }.getOrNull()) }

    /**
     * Legge i file nuovi o cambiati tra [files] e salva l'indice.
     * Restituisce true se qualcosa è cambiato.
     */
    suspend fun refresh(files: List<FileStamp>, uriOf: (Long) -> Uri): Boolean {
        val removed = index.retain(files.mapTo(HashSet()) { it.id })
        val stale = index.stale(files)
        if (stale.isEmpty()) {
            if (removed) save()
            return removed
        }
        Log.i(TAG, "Lettura dei tag: ${stale.size} file")
        // Pochi alla volta: Media3 ne legge comunque al massimo cinque insieme.
        stale.chunked(PARALLEL).forEachIndexed { i, chunk ->
            coroutineScope {
                chunk.map { stamp -> async { stamp to read(uriOf(stamp.id)) } }.awaitAll()
            }.forEach { (stamp, tags) -> index.put(stamp, tags) }
            // Si salva ogni tanto: se l'app viene chiusa a metà non si riparte da zero.
            if ((i + 1) % 25 == 0) save()
        }
        save()
        return true
    }

    private suspend fun read(uri: Uri): FileTags = runCatching {
        MetadataRetriever.Builder(context, MediaItem.fromUri(uri)).build().use { retriever ->
            val groups = withTimeout(TIMEOUT_MS) { retriever.retrieveTrackGroups().await() }
            val raw = ArrayList<RawTag>()
            for (g in 0 until groups.length) {
                val group = groups[g]
                for (f in 0 until group.length) {
                    val metadata = group.getFormat(f).metadata ?: continue
                    raw += entries(metadata)
                }
            }
            FileTags.from(raw)
        }
    }.onFailure { Log.w(TAG, "Tag illeggibili: $uri", it) }.getOrDefault(FileTags())

    /** Dalle voci di Media3 alla forma comune di [RawTag] (vedi lì le chiavi). */
    @Suppress("DEPRECATION")
    private fun entries(metadata: Metadata): List<RawTag> = (0 until metadata.length()).mapNotNull { i ->
        when (val e = metadata[i]) {
            is TextInformationFrame -> RawTag(if (e.id == "TXXX") "TXXX:${e.description.orEmpty()}" else e.id, e.values)
            is InternalFrame -> RawTag("----:${e.description}", listOf(e.text))
            // FLAC, Ogg e Opus: la classe nuova di Media3 eredita da quella vecchia.
            is androidx.media3.extractor.metadata.flac.VorbisComment -> RawTag(e.key, listOf(e.value))
            else -> null
        }
    }

    private fun save() {
        runCatching {
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(index.toJson())
            tmp.renameTo(file)
        }.onFailure { Log.w(TAG, "Indice dei tag non salvato", it) }
    }

    private companion object {
        const val TAG = "TagScanner"
        const val PARALLEL = 4
        const val TIMEOUT_MS = 15_000L
    }
}
