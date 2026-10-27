package io.github.baldborne94.baldmusic.library

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import io.github.baldborne94.baldmusic.core.acoustid.Recognition
import io.github.baldborne94.baldmusic.core.acoustid.RecognitionIndex
import io.github.baldborne94.baldmusic.core.library.DuplicateGroup
import io.github.baldborne94.baldmusic.core.library.FileStamp
import io.github.baldborne94.baldmusic.core.library.LibraryCleanup
import io.github.baldborne94.baldmusic.core.library.LibraryNode
import io.github.baldborne94.baldmusic.core.library.LibraryNode.Kind
import io.github.baldborne94.baldmusic.core.library.LocalCatalog
import io.github.baldborne94.baldmusic.core.library.LocalTrack
import io.github.baldborne94.baldmusic.core.library.LoudnessIndex
import io.github.baldborne94.baldmusic.core.library.MediaId
import io.github.baldborne94.baldmusic.core.library.MusicSource
import io.github.baldborne94.baldmusic.core.library.SongsCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
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
    /** Ascolti per brano (ID di TrackRef): gli artisti più ascoltati vanno in cima. */
    private val plays: () -> Map<String, Int> = { emptyMap() },
    http: OkHttpClient = OkHttpClient(),
    /** La chiave di AcoustID, se l'utente l'ha messa: senza, niente riconoscimento dal suono. */
    private val acoustIdKey: () -> String? = { null },
) : MusicSource {

    override val key = LocalCatalog.KEY
    override val title = "Telefono"

    private val catalog = LocalCatalog(
        streamUri = { id -> ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id).toString() },
        plays = { plays() },
    )

    /** I brani letti da MediaStore: si rileggono quando Android dice che i file sono cambiati. */
    private val songsCache = SongsCache<LocalTrack>(maxAgeMs = 10 * 60_000L)

    init {
        // File aggiunti, tolti o cambiati (anche da altre app o dal PC): la prossima richiesta rilegge.
        // Anche ogni memoria per nome (scheda SD): Android avvisa per volume.
        runCatching {
            val observer = object : ContentObserver(null) {
                override fun onChange(selfChange: Boolean) = songsCache.changed()
            }
            val volumes = if (Build.VERSION.SDK_INT >= 29) MediaStore.getExternalVolumeNames(context).map { MediaStore.Audio.Media.getContentUri(it) } else emptyList()
            (listOf(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI) + volumes).distinct().forEach { uri ->
                context.contentResolver.registerContentObserver(uri, true, observer)
            }
        }
    }

    private val scanner = TagScanner(context)
    private val loudness = LoudnessScanner(context)
    @Volatile private var loudnessScheduled = false
    /** Brani riconosciuti dal suono (AcoustID): i tag proposti valgono solo quelli applicati. */
    val recognition = RecognitionScanner(context, http)
    @Volatile private var recognitionScheduled = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val scanning = AtomicBoolean(false)

    /** Un file letto da MediaStore, con quel che serve a capire se è cambiato. */
    private class Row(val track: LocalTrack, val stamp: FileStamp)

    fun hasPermission() = ContextCompat.checkSelfPermission(context, PERMISSION) == PackageManager.PERMISSION_GRANTED

    fun invalidate() = songsCache.changed()

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
        (catalog.track(trackId, all) ?: query("${MediaStore.Audio.Media._ID} = ?", arrayOf("$trackId")).firstOrNull())
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

    /** Tutti i brani, riletti solo quando i file cambiano ([SongsCache]): Auto chiede le liste a pagine. */
    // Una lettura di MediaStore alla volta: a cache vuota (ripresa, Auto che chiede più pagine)
    // le richieste in parallelo aspettano la prima invece di rileggere tutto ognuna.
    @Synchronized
    private fun songs(): List<LocalTrack> {
        songsCache.fresh()?.let { return it }
        songsCache.reading()
        val rows = rows(null, null)
        // Il volume misurato dall'app per i file che il ReplayGain nei tag non l'hanno.
        val stamps = rows.associate { it.track.id to it.stamp }
        val read = LoudnessIndex.applyTo(LocalCatalog.withoutPlaceholderYears(rows.map(::withTags))) { t -> stamps[t.id]?.let(loudness.index::measure) }
        if (!loudnessScheduled && toMeasure(rows).let(loudness.index::pending).isNotEmpty()) {
            loudnessScheduled = true
            LoudnessWorker.schedule(context)
        }
        // Brani nuovi da riconoscere: in carica e con la rete, se c'è la chiave.
        if (!recognitionScheduled && !acoustIdKey().isNullOrBlank() && recognition.index.pending(toRecognize(rows).map { it.stamp }).isNotEmpty()) {
            recognitionScheduled = true
            RecognitionWorker.schedule(context)
        }
        // Gli stessi brani di prima: lo stesso elenco, così indice ed elenchi pronti restano validi.
        val list = songsCache.store(read)
        // Un elenco vuoto può voler dire permesso tolto o memoria non montata: non si tocca niente.
        if (list.isNotEmpty()) onSongs(list)
        // Con MediaStore vuoto (memoria non ancora montata, indice che si rifà) l'indice dei tag non si svuota.
        if (rows.isNotEmpty() && (scanner.index.stale(rows.map { it.stamp }).isNotEmpty() || scanner.index.size() > rows.size)) scan(rows.map { it.stamp })
        return list
    }

    /** I file da misurare: tag già letti e senza ReplayGain (chi ce l'ha nei tag usa quello). */
    private fun toMeasure(rows: List<Row>): List<FileStamp> = rows.map { it.stamp }.filter { st ->
        val tags = scanner.index.tags(st)
        tags != null && tags.replayGain == null
    }

    /**
     * Misura il volume dei brani che ne hanno bisogno, finché [stop] non dice basta
     * (lo chiama [LoudnessWorker]). Restituisce quanti ne ha misurati e quanti mancano.
     */
    fun measureLoudness(stop: () -> Boolean): Pair<Int, Int> {
        if (!hasPermission()) return 0 to 0
        val rows = rows(null, null)
        // Come sopra: un MediaStore vuoto non deve cancellare ore di misure.
        if (rows.isEmpty()) return 0 to 0
        loudness.retain(rows.mapTo(HashSet()) { it.track.id })
        val files = toMeasure(rows)
        val done = loudness.measure(files, { ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, it) }, stop)
        if (done > 0) songsCache.changed()
        loudnessScheduled = false
        return done to loudness.index.pending(files).size
    }

    /** Per le Impostazioni: quanti brani senza ReplayGain nei tag sono già misurati, e su quanti. */
    suspend fun loudnessStatus(): Pair<Int, Int> = withContext(Dispatchers.IO) {
        if (!hasPermission()) return@withContext 0 to 0
        val files = toMeasure(rows(null, null))
        (files.size - loudness.index.pending(files).size) to files.size
    }

    /** Legge in sottofondo i tag dei file nuovi o cambiati; una lettura alla volta. */
    private fun scan(files: List<FileStamp>) {
        if (!scanning.compareAndSet(false, true)) return
        scope.launch {
            try {
                val changed = scanner.refresh(files) { ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, it) }
                if (changed) {
                    songsCache.changed()
                    onTagsRead()
                }
            } finally {
                scanning.set(false)
            }
        }
    }

    private fun query(selection: String?, args: Array<String>?): List<LocalTrack> = rows(selection, args).map(::withTags)

    /** Il brano coi tag letti dal file, se l'indice li ha e il file non è cambiato. */
    private fun fileTags(r: Row): LocalTrack = scanner.index.tags(r.stamp)?.let(r.track::withTags) ?: r.track

    /**
     * Il brano come lo mostra l'app: i tag del file e, per un file mai passato da Picard,
     * quelli riconosciuti dal suono se l'utente li ha applicati. Un file con gli ID di
     * MusicBrainz nei tag (passato da Picard dopo) usa i suoi.
     */
    private fun withTags(r: Row): LocalTrack {
        val t = fileTags(r)
        val shown = if (!untagged(t)) t else recognition.index.tagsFor(r.stamp)?.let { Recognition.applyTo(t, it) } ?: t
        // Senza numero di traccia nei tag, quello del nome del file ("03 - Titolo.mp3").
        return shown.withNumberFromName()
    }

    /** I file da riconoscere: tag già letti e niente ID di MusicBrainz. */
    private fun toRecognize(rows: List<Row>): List<Row> = rows.filter { r -> scanner.index.tags(r.stamp) != null && untagged(fileTags(r)) }

    /**
     * Riconosce dal suono i brani che ne hanno bisogno, finché [stop] non dice basta (lo
     * chiama [RecognitionWorker]). Senza chiave o senza permesso non fa niente.
     */
    suspend fun recognize(stop: () -> Boolean): RecognitionScanner.Run = withContext(Dispatchers.IO) {
        val key = acoustIdKey()?.takeIf { it.isNotBlank() }
        if (key == null || !hasPermission()) return@withContext RecognitionScanner.Run(0)
        val rows = rows(null, null)
        // Come per volume e tag: un MediaStore vuoto non deve cancellare niente.
        if (rows.isEmpty()) return@withContext RecognitionScanner.Run(0)
        recognition.retain(rows.mapTo(HashSet()) { it.track.id })
        val files = toRecognize(rows).map { it.stamp to fileTags(it) }
        val folders = files.associate { (stamp, t) -> stamp.id to t.folder }
        val run = recognition.recognize(
            files, key,
            uriOf = { ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, it) },
            folderOf = { folders[it] ?: "" },
            stop = stop,
        )
        recognitionScheduled = false
        run
    }

    /** Un file riconosciuto (o no), coi tag che ha adesso nel file. */
    data class Recognized(val track: LocalTrack, val entry: RecognitionIndex.Entry)

    /** Per la schermata: quanti file sono da riconoscere, su quanti, e le proposte. */
    data class RecognitionStatus(val toDo: Int, val total: Int, val notFound: Int, val items: List<Recognized>)

    suspend fun recognitionStatus(): RecognitionStatus = withContext(Dispatchers.IO) {
        if (!hasPermission()) return@withContext RecognitionStatus(0, 0, 0, emptyList())
        val rows = toRecognize(rows(null, null))
        val items = rows.mapNotNull { r -> recognition.index.entry(r.stamp)?.let { Recognized(fileTags(r), it) } }
        RecognitionStatus(
            toDo = recognition.index.pending(rows.map { it.stamp }).size,
            total = rows.size,
            notFound = items.count { it.entry.proposal == null },
            items = items.filter { it.entry.proposal != null },
        )
    }

    /** Applica, scarta o rimette in attesa delle proposte; la libreria si rilegge. */
    fun decideRecognized(ids: Collection<Long>, decision: RecognitionIndex.Decision) = changed { recognition.index.decide(ids, decision) }

    /** L'utente sceglie un altro disco per il file (e lo applica). */
    fun chooseRecognized(id: Long, candidate: Int) = changed { recognition.index.choose(id, candidate) }

    /** I file non riconosciuti tornano da riconoscere (AcoustID impara brani nuovi col tempo). */
    fun retryNotFound() = changed { recognition.index.forgetNotFound() }

    private fun changed(change: () -> Unit) {
        change()
        recognition.save()
        songsCache.changed()
        onTagsRead()
    }

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
        ) + if (Build.VERSION.SDK_INT >= 30) arrayOf(ALBUM_ARTIST, GENRE) else emptyArray()
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
                    fileName = c.getString(10)?.substringAfterLast('/'),
                    albumArtist = if (c.columnCount > 13) c.getString(13)?.takeUnless { it == UNKNOWN || it.isBlank() } else null,
                    genre = if (c.columnCount > 14) c.getString(14)?.takeUnless { it == UNKNOWN || it.isBlank() } else null,
                )
                out += Row(song, FileStamp(id, modified = c.getLong(11), size = c.getLong(12)))
            }
        }
        return out
    }

    companion object {
        /** Mai passato da Picard: niente ID di MusicBrainz nei tag. */
        fun untagged(t: LocalTrack) = t.albumMbid == null && t.releaseGroupMbid == null && t.artistMbids.isEmpty()

        /** "/storage/emulated/0/Music/Queen/01.mp3" → "Music/Queen". */
        private fun folderOf(path: String?): String {
            val dir = path?.substringBeforeLast('/', "") ?: return "Sconosciuta"
            return dir.replace(STORAGE_ROOT, "").ifEmpty { "Memoria interna" }
        }

        /** "/storage/emulated/0/" o "/storage/XXXX-XXXX/" (scheda SD): una volta, non per ogni brano. */
        private val STORAGE_ROOT = Regex("^/storage/(emulated/\\d+|[^/]+)/?")

        /** Colonna presente da sempre, ma documentata solo da Android 11. */
        private const val ALBUM_ARTIST = "album_artist"
        /** Il genere del file: colonna di MediaStore da Android 11. */
        private const val GENRE = "genre"

        /** Segnaposto che MediaStore usa quando il tag manca. */
        private const val UNKNOWN = "<unknown>"

        val PERMISSION: String =
            if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
    }
}
