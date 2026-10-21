package io.github.baldborne94.baldmusic.artwork

import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Size
import io.github.baldborne94.baldmusic.app
import io.github.baldborne94.baldmusic.core.artwork.ArtworkCheck
import io.github.baldborne94.baldmusic.core.library.LocalCatalog
import io.github.baldborne94.baldmusic.core.library.PersonalSource
import io.github.baldborne94.baldmusic.core.library.RemoteSource
import okhttp3.Request
import java.io.File
import java.io.FileNotFoundException

/**
 * Serve le copertine come content://<pacchetto>.artwork/<sorgente>/<id>.
 *
 * Android Auto non scarica immagini da internet e non può leggere MediaStore
 * per conto nostro: vuole un content:// che apriamo noi. Le immagini finiscono
 * nella cache dell'app, che Android svuota da sé quando serve spazio.
 */
class ArtworkProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        // Le copertine salvate prima del controllo sulle immagini rovinate: si rifanno.
        context?.let { ctx -> Thread { File(ctx.cacheDir, OLD_CACHE_DIR).deleteRecursively() }.start() }
        return true
    }

    override fun getType(uri: Uri) = "image/jpeg"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val ctx = context ?: throw FileNotFoundException(uri.toString())
        val source = uri.pathSegments.getOrNull(0)
        val id = uri.pathSegments.getOrNull(1)
        if (source == null || id == null) throw FileNotFoundException(uri.toString())

        val file = cached(ctx, source, id) ?: throw FileNotFoundException(uri.toString())
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    /** La copertina nella cache, scaricata o composta se manca; null se non si trova. */
    private fun cached(ctx: Context, source: String, id: String): File? {
        val dir = File(ctx.cacheDir, CACHE_DIR).apply { mkdirs() }
        val safeName = id.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val file = File(dir, "$source-$safeName.jpg")
        if (file.exists() && file.length() > 0L) return file
        if (brokenMark(file).exists()) return null
        // Chi chiama è Android Auto (un'altra app): le letture verso MediaStore
        // e la rete devono partire con l'identità di BaldMusic.
        if (source == LocalCatalog.COVER) return cover(ctx, id, file)
        val token = Binder.clearCallingIdentity()
        val ok = try {
            when (source) {
                LocalCatalog.KEY -> fetchLocal(ctx, id, file)
                RemoteSource.KEY -> fetchRemote(ctx, id, file)
                PersonalSource.MOSAIC -> fetchMosaic(ctx, id, file)
                else -> false
            }
        } catch (e: Exception) {
            false
        } finally {
            Binder.restoreCallingIdentity(token)
        }
        return file.takeIf { ok }
    }

    /**
     * La copertina vera di un brano riconosciuto: "<gruppo MusicBrainz>~<album di Android>".
     * Se Cover Art Archive non ce l'ha, si tiene per sempre quella del file; senza rete si
     * mostra quella del file ma non si ricorda, così la prossima volta si riprova.
     */
    private fun cover(ctx: Context, id: String, out: File): File? {
        val mbid = id.substringBefore('~')
        val albumId = id.substringAfter('~', "")
        val token = Binder.clearCallingIdentity()
        val found = try {
            fetchCover(ctx, mbid, out)
        } catch (e: Exception) {
            null
        } finally {
            Binder.restoreCallingIdentity(token)
        }
        return when (found) {
            true -> out
            false -> cached(ctx, LocalCatalog.KEY, albumId)?.let { local ->
                runCatching { writeAtomically(out) { stream -> local.inputStream().use { it.copyTo(stream) }; true } }
                local
            }
            null -> cached(ctx, LocalCatalog.KEY, albumId)
        }
    }

    /** true = scaricata, false = l'archivio non ce l'ha, null = non si sa (rete, errore). */
    private fun fetchCover(ctx: Context, mbid: String, out: File): Boolean? {
        if (!Regex("[0-9a-fA-F-]{36}").matches(mbid)) return false
        // Cover Art Archive chiede di dire chi siamo.
        val request = Request.Builder()
            .url("https://coverartarchive.org/release-group/$mbid/front-500")
            .header("User-Agent", "BaldMusic (https://github.com/Baldborne94/baldmusic-player)")
            .build()
        ctx.app.http.newCall(request).execute().use { res ->
            if (res.code == 404) return false
            if (!res.isSuccessful) return null
            return writeAtomically(out) { res.body.byteStream().copyTo(it); true }.takeIf { it }
        }
    }

    /** Mosaico 2×2 per playlist e raccolte: "local/1,local/2,…" sono le quattro copertine. */
    private fun fetchMosaic(ctx: Context, keys: String, out: File): Boolean {
        val tiles = keys.split(',').take(4).mapNotNull { key ->
            cached(ctx, key.substringBefore('/'), key.substringAfter('/'))?.let { android.graphics.BitmapFactory.decodeFile(it.path) }
        }
        if (tiles.isEmpty()) return false
        val half = SIZE / 2
        val mosaic = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(mosaic)
        val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
        // Se una copertina non si trova, al suo posto si ripete una delle altre.
        (0 until 4).forEach { i ->
            val x = (i % 2) * half
            val y = (i / 2) * half
            canvas.drawBitmap(tiles[i % tiles.size], null, android.graphics.Rect(x, y, x + half, y + half), paint)
        }
        return writeAtomically(out) { stream -> mosaic.compress(Bitmap.CompressFormat.JPEG, 90, stream) }
    }

    private fun fetchLocal(ctx: Context, albumId: String, out: File): Boolean {
        val id = albumId.toLongOrNull() ?: return false
        if (!ctx.app.local.hasPermission()) return false
        return if (Build.VERSION.SDK_INT >= 29) {
            val albumUri = ContentUris.withAppendedId(MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI, id)
            // Una copertina rovinata nel file (righe di colori a caso) non si mostra: si prova
            // quella di un altro brano dello stesso disco, e se nessuna va, niente copertina.
            // Senza copertina (loadThumbnail fallisce) si resta senza, come prima: niente lavoro in più.
            val thumbnail = ctx.contentResolver.loadThumbnail(albumUri, Size(SIZE, SIZE), null)
            val bitmap = if (broken(thumbnail)) {
                // Nessuna buona: lo si ricorda, così non si rifà il controllo a ogni richiesta.
                embeddedFromAlbum(ctx, id) ?: run { brokenMark(out).createNewFile(); return false }
            } else {
                thumbnail
            }
            writeAtomically(out) { stream -> bitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream) }
        } else {
            val legacy = ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), id)
            val input = ctx.contentResolver.openInputStream(legacy) ?: return false
            input.use { src -> writeAtomically(out) { src.copyTo(it); true } }
        }
    }

    /** Il segno lasciato accanto a una copertina rovinata senza alternative. */
    private fun brokenMark(file: File) = File(file.parentFile, "${file.name}.rotta")

    /** La copertina sembra rovinata ([ArtworkCheck]), guardata in piccolo. */
    private fun broken(bitmap: Bitmap): Boolean = runCatching {
        val soft = if (bitmap.config == Bitmap.Config.HARDWARE) bitmap.copy(Bitmap.Config.ARGB_8888, false) else bitmap
        val small = Bitmap.createScaledBitmap(soft, CHECK_SIZE, CHECK_SIZE, true)
        val pixels = IntArray(CHECK_SIZE * CHECK_SIZE).also { small.getPixels(it, 0, CHECK_SIZE, 0, 0, CHECK_SIZE, CHECK_SIZE) }
        ArtworkCheck.looksBroken(pixels, CHECK_SIZE, CHECK_SIZE)
    }.getOrDefault(false)

    /** La prima copertina buona tra quelle scritte nei brani del disco (al massimo qualcuno). */
    private fun embeddedFromAlbum(ctx: Context, albumId: Long): Bitmap? {
        val ids = ArrayList<Long>()
        ctx.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Audio.Media._ID),
            "${MediaStore.Audio.Media.ALBUM_ID} = ?", arrayOf("$albumId"), null,
        )?.use { c -> while (c.moveToNext() && ids.size < MAX_TRACKS_TO_TRY) ids += c.getLong(0) }
        for (trackId in ids) {
            val bytes = runCatching {
                android.media.MediaMetadataRetriever().run {
                    try {
                        setDataSource(ctx, ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, trackId))
                        embeddedPicture
                    } finally {
                        release()
                    }
                }
            }.getOrNull() ?: continue
            val bitmap = decodeSampled(bytes) ?: continue
            if (!broken(bitmap)) return bitmap
        }
        return null
    }

    /** Decodifica rimpicciolendo già durante la lettura: certe copertine nei file sono enormi. */
    private fun decodeSampled(bytes: ByteArray): Bitmap? {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= SIZE && bounds.outHeight / (sample * 2) >= SIZE) sample *= 2
        return android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun fetchRemote(ctx: Context, coverId: String, out: File): Boolean {
        val client = ctx.app.subsonic() ?: return false
        val request = Request.Builder().url(client.coverUrl(coverId, SIZE)).build()
        ctx.app.http.newCall(request).execute().use { res ->
            if (!res.isSuccessful) return false
            return writeAtomically(out) { res.body.byteStream().copyTo(it); true }
        }
    }

    /** Scrive su un file temporaneo e poi rinomina: nessuno legge mai mezza immagine. */
    private fun writeAtomically(out: File, write: (java.io.OutputStream) -> Boolean): Boolean {
        val tmp = File(out.parentFile, "${out.name}.tmp")
        val ok = tmp.outputStream().use(write)
        return ok && tmp.length() > 0 && tmp.renameTo(out)
    }

    override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?) = 0

    companion object {
        private const val SIZE = 512
        private const val CHECK_SIZE = 64
        private const val MAX_TRACKS_TO_TRY = 6

        /**
         * La cartella delle copertine. Cambia nome quando cambia come si preparano, così quelle
         * vecchie (es. rovinate, salvate prima del controllo) non si usano più.
         */
        private const val CACHE_DIR = "artwork2"
        private const val OLD_CACHE_DIR = "artwork"

        /** "remote/al-123" → content://<pacchetto>.artwork/remote/al-123 */
        fun uri(context: Context, key: String): Uri {
            val source = key.substringBefore('/')
            val id = key.substringAfter('/')
            return Uri.Builder()
                .scheme("content")
                .authority("${context.packageName}.artwork")
                .appendPath(source)
                .appendPath(id)
                .build()
        }
    }
}
