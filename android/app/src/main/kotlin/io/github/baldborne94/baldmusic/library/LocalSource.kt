package io.github.baldborne94.baldmusic.library

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import io.github.baldborne94.baldmusic.core.library.DuplicateGroup
import io.github.baldborne94.baldmusic.core.library.FileStamp
import io.github.baldborne94.baldmusic.core.library.LibraryCleanup
import io.github.baldborne94.baldmusic.core.library.LibraryNode
import io.github.baldborne94.baldmusic.core.library.LibraryNode.Kind
import io.github.baldborne94.baldmusic.core.library.LocalCatalog
import io.github.baldborne94.baldmusic.core.library.LocalTrack
import io.github.baldborne94.baldmusic.core.library.MediaId
import io.github.baldborne94.baldmusic.core.library.MusicSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * La musica salvata sul telefono. L'elenco dei file viene da MediaStore (l'indice
 * che Android tiene di tutti i file audio), i tag completi da [TagScanner]: finché
 * un file non è stato letto valgono quelli di MediaStore. Come i brani diventano
 * album, artisti e mix lo decide [LocalCatalog], in `:core` e testato.
 *
 * [onTagsRead] viene chiamato quando la lettura dei tag ha cambiato qualcosa:
 * la libreria va ricaricata (nell'app e in Android Auto). [onSongs] riceve l'elenco
 * aggiornato dei brani ogni volta che si rilegge (serve alle raccolte dalle cartelle).
 */
class LocalSource(
    private val context: Context,
    private val onTagsRead: () -> Unit,
    private val onSongs: (List<LocalTrack>) -> Unit = {},
) : MusicSource {

    override val key = LocalCatalog.KEY
    override val title = "Telefono"

    private val catalog = LocalCatalog(streamUri = { id ->
        ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id).toString()
    })

    @Volatile private var cached: Pair<Long, List<LocalTrack>>? = null

    private val scanner = TagScanner(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val scanning = AtomicBoolean(false)

    /** Un file letto da MediaStore, con quel che serve a capire se è cambiato. */
    private class Row(val track: LocalTrack, val stamp: FileStamp)

    fun hasPermission() = ContextCompat.checkSelfPermission(context, PERMISSION) == PackageManager.PERMISSION_GRANTED

    fun invalidate() {
        cached = null
    }

    override suspend fun sections(): List<LibraryNode> {
        if (!hasPermission()) {
            return listOf(LibraryNode("${LocalCatalog.KEY}/permission", "Apri BaldMusic sul telefono e consenti l'accesso alla musica", Kind.MESSAGE))
        }
        return catalog.sections()
    }

    override suspend fun children(id: MediaId): List<LibraryNode> = withContext(Dispatchers.IO) {
        if (!hasPermission()) emptyList() else catalog.children(id, songs())
    }

    override suspend fun track(id: MediaId): LibraryNode? = withContext(Dispatchers.IO) {
        val trackId = id.id?.toLongOrNull() ?: return@withContext null
        if (!hasPermission()) return@withContext null
        val all = songs()
        (all.firstOrNull { it.id == trackId } ?: query("${MediaStore.Audio.Media._ID} = ?", arrayOf("$trackId")).firstOrNull())
            ?.let { catalog.node(it, id.parent, all) }
    }

    override suspend fun search(query: String): List<LibraryNode> = withContext(Dispatchers.IO) {
        if (!hasPermission()) emptyList() else catalog.search(query, songs())
    }

    /** I dati di un brano per la scheda "Informazioni sul brano"; null se non c'è più. */
    suspend fun details(trackId: Long): List<Pair<String, String>>? = withContext(Dispatchers.IO) {
        if (!hasPermission()) return@withContext null
        val row = rows("${MediaStore.Audio.Media._ID} = ?", arrayOf("$trackId")).firstOrNull() ?: return@withContext null
        val tags = scanner.index.tags(row.stamp)
        val shown = songs().firstOrNull { it.id == trackId } ?: withTags(row)
        shown.details(tagsRead = tags != null && !tags.isEmpty)
    }

    /** Un file musicale nel cestino di Android, da poter ripescare. */
    data class TrashedFile(val id: Long, val title: String, val artist: String?, val folder: String, val durationMs: Long, val expiresAt: Long)

    /** I brani nel cestino di Android (prima di Android 11 il cestino non c'è). */
    suspend fun trashed(): List<TrashedFile> = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < 30 || !hasPermission()) return@withContext emptyList()
        val args = android.os.Bundle().apply {
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_ONLY)
            putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.Audio.Media.IS_MUSIC} != 0")
            putString(android.content.ContentResolver.QUERY_ARG_SQL_SORT_ORDER, "${MediaStore.MediaColumns.DATE_EXPIRES} DESC")
        }
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.DURATION,
            MediaStore.MediaColumns.DATE_EXPIRES,
        )
        val out = ArrayList<TrashedFile>()
        context.contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection, args, null)?.use { c ->
            while (c.moveToNext()) {
                out += TrashedFile(
                    id = c.getLong(0),
                    title = c.getString(1) ?: "Senza titolo",
                    artist = c.getString(2)?.takeUnless { it == UNKNOWN },
                    folder = folderOf(c.getString(3)),
                    durationMs = c.getLong(4),
                    expiresAt = c.getLong(5),
                )
            }
        }
        out
    }

    /** Tutti i brani del telefono, riletti adesso (dopo un ripristino dal cestino). */
    suspend fun allTracks(): List<LocalTrack> = withContext(Dispatchers.IO) {
        if (!hasPermission()) emptyList() else songs()
    }

    /** Doppioni e anteprime tra i brani del telefono, per la pagina "Pulizia della libreria". */
    suspend fun cleanup(): Pair<List<DuplicateGroup>, List<LocalTrack>> = withContext(Dispatchers.IO) {
        if (!hasPermission()) return@withContext emptyList<DuplicateGroup>() to emptyList()
        val all = songs()
        LibraryCleanup.duplicates(all) to LibraryCleanup.previews(all)
    }

    /** Tutti i brani, riletti al massimo ogni 30 secondi (Auto chiede le liste a pagine). */
    private fun songs(): List<LocalTrack> {
        cached?.let { (at, list) -> if (System.currentTimeMillis() - at < 30_000) return list }
        val rows = rows(null, null)
        val list = LocalCatalog.withoutPlaceholderYears(rows.map(::withTags))
        cached = System.currentTimeMillis() to list
        // Un elenco vuoto può voler dire permesso tolto o memoria non montata: non si tocca niente.
        if (list.isNotEmpty()) onSongs(list)
        if (scanner.index.stale(rows.map { it.stamp }).isNotEmpty() || scanner.index.size() > rows.size) scan(rows.map { it.stamp })
        return list
    }

    /** Legge in sottofondo i tag dei file nuovi o cambiati; una lettura alla volta. */
    private fun scan(files: List<FileStamp>) {
        if (!scanning.compareAndSet(false, true)) return
        scope.launch {
            try {
                val changed = scanner.refresh(files) { ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, it) }
                if (changed) {
                    cached = null
                    onTagsRead()
                }
            } finally {
                scanning.set(false)
            }
        }
    }

    private fun query(selection: String?, args: Array<String>?): List<LocalTrack> = rows(selection, args).map(::withTags)

    /** Il brano coi tag letti dal file, se l'indice li ha e il file non è cambiato. */
    private fun withTags(r: Row): LocalTrack = scanner.index.tags(r.stamp)?.let(r.track::withTags) ?: r.track

    private fun rows(selection: String?, args: Array<String>?): List<Row> {
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ARTIST_ID,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.YEAR,
            MediaStore.Audio.Media.DATE_ADDED,
            // Deprecato ma ancora leggibile: è l'unico modo, su ogni versione, per sapere la cartella.
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.DATE_MODIFIED,
            MediaStore.Audio.Media.SIZE,
        ) + if (Build.VERSION.SDK_INT >= 30) arrayOf(ALBUM_ARTIST) else emptyArray()
        val where = listOfNotNull(
            "${MediaStore.Audio.Media.IS_MUSIC} != 0",
            // I file nel cestino Android li esclude già; detto esplicitamente, un brano buttato non torna mai.
            if (Build.VERSION.SDK_INT >= 30) "${MediaStore.MediaColumns.IS_TRASHED} = 0" else null,
            selection,
        ).joinToString(" AND ") { "($it)" }
        val out = ArrayList<Row>()
        context.contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection, where, args, null)?.use { c ->
            while (c.moveToNext()) {
                // MediaStore codifica disco e traccia insieme: 2005 = disco 2, traccia 5.
                val track = c.getInt(7)
                val id = c.getLong(0)
                val song = LocalTrack(
                    id = id,
                    title = c.getString(1) ?: "Senza titolo",
                    artist = c.getString(2)?.takeUnless { it == UNKNOWN },
                    artistId = c.getLong(3),
                    album = c.getString(4)?.takeUnless { it == UNKNOWN },
                    albumId = c.getLong(5),
                    durationMs = c.getLong(6),
                    disc = track / 1000,
                    track = track % 1000,
                    year = c.getInt(8),
                    dateAdded = c.getLong(9),
                    folder = folderOf(c.getString(10)),
                    albumArtist = if (c.columnCount > 13) c.getString(13)?.takeUnless { it == UNKNOWN || it.isBlank() } else null,
                )
                out += Row(song, FileStamp(id, modified = c.getLong(11), size = c.getLong(12)))
            }
        }
        return out
    }

    companion object {
        /** "/storage/emulated/0/Music/Queen/01.mp3" → "Music/Queen". */
        private fun folderOf(path: String?): String {
            val dir = path?.substringBeforeLast('/', "") ?: return "Sconosciuta"
            return dir.replace(Regex("^/storage/(emulated/\\d+|[^/]+)/?"), "").ifEmpty { "Memoria interna" }
        }

        /** Colonna presente da sempre, ma documentata solo da Android 11. */
        private const val ALBUM_ARTIST = "album_artist"

        /** Segnaposto che MediaStore usa quando il tag manca. */
        private const val UNKNOWN = "<unknown>"

        val PERMISSION: String =
            if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
    }
}
