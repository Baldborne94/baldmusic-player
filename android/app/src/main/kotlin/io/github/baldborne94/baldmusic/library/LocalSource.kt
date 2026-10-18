package io.github.baldborne94.baldmusic.library

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import io.github.baldborne94.baldmusic.core.library.LibraryNode
import io.github.baldborne94.baldmusic.core.library.LibraryNode.Kind
import io.github.baldborne94.baldmusic.core.library.LocalCatalog
import io.github.baldborne94.baldmusic.core.library.LocalTrack
import io.github.baldborne94.baldmusic.core.library.MediaId
import io.github.baldborne94.baldmusic.core.library.MusicSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * La musica salvata sul telefono, letta da MediaStore (l'indice che Android
 * tiene di tutti i file audio). Qui si leggono solo i brani: come diventano
 * album, artisti e mix lo decide [LocalCatalog], in `:core` e testato.
 */
class LocalSource(private val context: Context) : MusicSource {

    override val key = LocalCatalog.KEY
    override val title = "Telefono"

    private val catalog = LocalCatalog(streamUri = { id ->
        ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id).toString()
    })

    @Volatile private var cached: Pair<Long, List<LocalTrack>>? = null

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
        (songs().firstOrNull { it.id == trackId } ?: query("${MediaStore.Audio.Media._ID} = ?", arrayOf("$trackId")).firstOrNull())
            ?.let { catalog.node(it, id.parent) }
    }

    override suspend fun search(query: String): List<LibraryNode> = withContext(Dispatchers.IO) {
        if (!hasPermission()) emptyList() else catalog.search(query, songs())
    }

    /** Tutti i brani, riletti al massimo ogni 30 secondi (Auto chiede le liste a pagine). */
    private fun songs(): List<LocalTrack> {
        cached?.let { (at, list) -> if (System.currentTimeMillis() - at < 30_000) return list }
        val list = query(null, null)
        cached = System.currentTimeMillis() to list
        return list
    }

    private fun query(selection: String?, args: Array<String>?): List<LocalTrack> {
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
        )
        val where = listOfNotNull("${MediaStore.Audio.Media.IS_MUSIC} != 0", selection).joinToString(" AND ") { "($it)" }
        val out = ArrayList<LocalTrack>()
        context.contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection, where, args, null)?.use { c ->
            while (c.moveToNext()) {
                // MediaStore codifica disco e traccia insieme: 2005 = disco 2, traccia 5.
                val track = c.getInt(7)
                out += LocalTrack(
                    id = c.getLong(0),
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
                )
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

        /** Segnaposto che MediaStore usa quando il tag manca. */
        private const val UNKNOWN = "<unknown>"

        val PERMISSION: String =
            if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
    }
}
