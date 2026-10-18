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

    override fun onCreate() = true

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
        val dir = File(ctx.cacheDir, "artwork").apply { mkdirs() }
        val safeName = id.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val file = File(dir, "$source-$safeName.jpg")
        if (file.exists() && file.length() > 0L) return file
        // Chi chiama è Android Auto (un'altra app): le letture verso MediaStore
        // e la rete devono partire con l'identità di BaldMusic.
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
            val bitmap = ctx.contentResolver.loadThumbnail(albumUri, Size(SIZE, SIZE), null)
            writeAtomically(out) { stream -> bitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream) }
        } else {
            val legacy = ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), id)
            val input = ctx.contentResolver.openInputStream(legacy) ?: return false
            input.use { src -> writeAtomically(out) { src.copyTo(it); true } }
        }
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
