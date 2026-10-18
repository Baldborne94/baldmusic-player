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
import io.github.baldborne94.baldmusic.core.library.MediaId
import io.github.baldborne94.baldmusic.core.library.MusicSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * La musica salvata sul telefono, letta da MediaStore (l'indice che Android
 * tiene di tutti i file audio). Album e artisti si ricavano dai brani: così
 * restano coerenti su ogni versione di Android ed escludono suonerie e note vocali.
 */
class LocalSource(private val context: Context) : MusicSource {

    override val key = KEY
    override val title = "Telefono"

    private data class Song(
        val id: Long,
        val title: String,
        val artist: String?,
        val artistId: Long,
        val album: String?,
        val albumId: Long,
        val durationMs: Long,
        /** MediaStore codifica disco e traccia insieme: 2005 = disco 2, traccia 5. */
        val track: Int,
        val year: Int,
    )

    @Volatile private var cached: Pair<Long, List<Song>>? = null
    @Volatile private var shuffled: List<Song> = emptyList()

    fun hasPermission() = ContextCompat.checkSelfPermission(context, PERMISSION) == PackageManager.PERMISSION_GRANTED

    fun invalidate() {
        cached = null
    }

    override suspend fun sections(): List<LibraryNode> {
        if (!hasPermission()) {
            return listOf(LibraryNode("$KEY/permission", "Apri BaldMusic sul telefono e consenti l'accesso alla musica", Kind.MESSAGE))
        }
        return listOf(
            section(ALBUMS, "Album", grid = true),
            section(ARTISTS, "Artisti"),
            section(SONGS, "Tutti i brani"),
            section(SHUFFLE, "Mix casuale"),
        )
    }

    private fun section(kind: String, title: String, grid: Boolean = false) =
        LibraryNode(MediaId(KEY, kind).toString(), title, Kind.SECTION, gridChildren = grid)

    override suspend fun children(id: MediaId): List<LibraryNode> = withContext(Dispatchers.IO) {
        if (!hasPermission()) return@withContext emptyList()
        val all = songs()
        when (id.kind) {
            ALBUMS -> albums(all).sortedBy { it.title.lowercase() }
            ARTISTS -> artists(all)
            SONGS -> all.sortedBy { it.title.lowercase() }.map { it.node(MediaId(KEY, SONGS)) }
            SHUFFLE -> {
                // Il mix si pesca entrando nella sezione; "last" lo ritrova identico per la coda.
                if (id.id == null) shuffled = all.shuffled()
                shuffled.map { it.node(MediaId(KEY, SHUFFLE, "last")) }
            }
            ALBUM -> {
                val albumId = id.id?.toLongOrNull()
                all.filter { it.albumId == albumId }
                    .sortedWith(compareBy({ it.track / 1000 }, { it.track % 1000 }, { it.title.lowercase() }))
                    .map { it.node(id.copy(parent = null)) }
            }
            ARTIST -> {
                val artistId = id.id?.toLongOrNull()
                albums(all.filter { it.artistId == artistId }).sortedByDescending { it.year ?: 0 }
            }
            else -> emptyList()
        }
    }

    override suspend fun track(id: MediaId): LibraryNode? = withContext(Dispatchers.IO) {
        val trackId = id.id?.toLongOrNull() ?: return@withContext null
        if (!hasPermission()) return@withContext null
        (songs().firstOrNull { it.id == trackId } ?: query("${MediaStore.Audio.Media._ID} = ?", arrayOf("$trackId")).firstOrNull())
            ?.node(id.parent)
    }

    override suspend fun search(query: String): List<LibraryNode> = withContext(Dispatchers.IO) {
        if (!hasPermission() || query.isBlank()) return@withContext emptyList()
        val q = query.trim().lowercase()
        val all = songs()
        val albums = albums(all.filter { it.album?.lowercase()?.contains(q) == true }).take(20)
        val artists = artists(all.filter { it.artist?.lowercase()?.contains(q) == true }).take(10)
        val songs = all.filter {
            it.title.lowercase().contains(q) || it.artist?.lowercase()?.contains(q) == true
        }.take(50).map { it.node(null) }
        artists + albums + songs
    }

    private fun albums(songs: List<Song>): List<LibraryNode> = songs.groupBy { it.albumId }.map { (albumId, tracks) ->
        val artists = tracks.mapNotNull { it.artist }.distinct()
        LibraryNode(
            mediaId = MediaId(KEY, ALBUM, "$albumId").toString(),
            title = tracks.first().album ?: "Album sconosciuto",
            kind = Kind.ALBUM,
            subtitle = if (artists.size > 1) "Artisti vari" else artists.firstOrNull(),
            artist = artists.singleOrNull(),
            artwork = "$KEY/$albumId",
            year = tracks.maxOf { it.year }.takeIf { it > 0 },
        )
    }

    private fun artists(songs: List<Song>): List<LibraryNode> = songs.groupBy { it.artistId }.map { (artistId, tracks) ->
        val albums = tracks.map { it.albumId }.distinct()
        LibraryNode(
            mediaId = MediaId(KEY, ARTIST, "$artistId").toString(),
            title = tracks.first().artist ?: "Artista sconosciuto",
            kind = Kind.ARTIST,
            subtitle = if (albums.size == 1) "1 album" else "${albums.size} album",
            artwork = "$KEY/${albums.first()}",
            gridChildren = true,
        )
    }.sortedBy { it.title.lowercase() }

    private fun Song.node(parent: MediaId?) = LibraryNode(
        mediaId = MediaId.track(KEY, "$id", parent).toString(),
        title = title,
        kind = Kind.TRACK,
        subtitle = artist,
        artist = artist,
        album = album,
        artwork = "$KEY/$albumId",
        durationMs = durationMs,
        trackNumber = (track % 1000).takeIf { it > 0 },
        discNumber = (track / 1000).takeIf { it > 0 },
        year = year.takeIf { it > 0 },
        streamUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id).toString(),
    )

    /** Tutti i brani, riletti al massimo ogni 30 secondi (Auto chiede le liste a pagine). */
    private fun songs(): List<Song> {
        cached?.let { (at, list) -> if (System.currentTimeMillis() - at < 30_000) return list }
        val list = query(null, null)
        cached = System.currentTimeMillis() to list
        return list
    }

    private fun query(selection: String?, args: Array<String>?): List<Song> {
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
        )
        val where = listOfNotNull("${MediaStore.Audio.Media.IS_MUSIC} != 0", selection).joinToString(" AND ") { "($it)" }
        val out = ArrayList<Song>()
        context.contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection, where, args, null)?.use { c ->
            while (c.moveToNext()) {
                out += Song(
                    id = c.getLong(0),
                    title = c.getString(1) ?: "Senza titolo",
                    artist = c.getString(2)?.takeUnless { it == UNKNOWN },
                    artistId = c.getLong(3),
                    album = c.getString(4)?.takeUnless { it == UNKNOWN },
                    albumId = c.getLong(5),
                    durationMs = c.getLong(6),
                    track = c.getInt(7),
                    year = c.getInt(8),
                )
            }
        }
        return out
    }

    companion object {
        const val KEY = "local"
        const val ALBUMS = "albums"
        const val ARTISTS = "artists"
        const val SONGS = "songs"
        const val SHUFFLE = "shuffle"
        const val ALBUM = "album"
        const val ARTIST = "artist"

        /** Segnaposto che MediaStore usa quando il tag manca. */
        private const val UNKNOWN = "<unknown>"

        val PERMISSION: String =
            if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
    }
}
