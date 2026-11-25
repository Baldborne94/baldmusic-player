package io.github.baldborne94.baldmusic.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.webkit.MimeTypeMap

/**
 * La cartella del telefono scelta per i brani scaricati dal server (Impostazioni → Server →
 * «Cartella degli scaricati»), aperta col selettore di Android: l'app ci scrive solo col
 * permesso che le hai dato. I file sono brani normali del telefono (scelta dell'utente): li
 * vedono le altre app, restano se disinstalli BaldMusic, e dentro Music ogni sottocartella
 * diventa anche una raccolta del telefono. Non sovrascrive mai un file che c'è già.
 */
class DownloadFolder(private val context: Context, val tree: Uri) {
    private val resolver = context.contentResolver
    private val root: Uri = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))

    /** Il permesso di scrivere c'è ancora (si perde se togli l'app dalle cartelle consentite). */
    fun writable(): Boolean = resolver.persistedUriPermissions.any { it.uri == tree && it.isWritePermission }

    /** Il nome da mostrare: «Music/BaldMusic» per «primary:Music/BaldMusic». */
    val label: String get() = labelOf(tree)

    /** I documenti dentro [parent], per nome. */
    fun children(parent: Uri = root): Map<String, Uri> {
        val list = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(parent))
        val out = HashMap<String, Uri>()
        resolver.query(list, arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            while (c.moveToNext()) out[c.getString(1)] = DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0))
        }
        return out
    }

    /** La sottocartella [name], creata se manca. */
    fun subfolder(name: String): Uri? =
        children()[name] ?: DocumentsContract.createDocument(resolver, root, Document.MIME_TYPE_DIR, name)

    /** Un file nuovo [name] in [dir]; il tipo dall'estensione, così Android lo riconosce come musica. */
    fun create(dir: Uri, name: String): Uri? {
        val ext = name.substringAfterLast('.', "").lowercase()
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "audio/mpeg"
        return DocumentsContract.createDocument(resolver, dir, mime, name)
    }

    /** Cancella un file lasciato a metà (scaricamento non riuscito): non è musica di nessuno. */
    fun deletePartial(uri: Uri) {
        runCatching { DocumentsContract.deleteDocument(resolver, uri) }
    }

    companion object {
        /** Il permesso di scrivere nella cartella scelta resta anche dopo un riavvio. */
        const val FLAGS = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

        fun labelOf(tree: Uri): String = runCatching {
            DocumentsContract.getTreeDocumentId(tree).substringAfter(':').ifEmpty { "Memoria del telefono" }
        }.getOrDefault(tree.toString())

        /** Il file c'è ancora (non l'hai spostato o cancellato a mano). */
        fun exists(context: Context, doc: Uri): Boolean = runCatching {
            context.contentResolver.query(doc, arrayOf(Document.COLUMN_DOCUMENT_ID), null, null, null)?.use { it.count > 0 } ?: false
        }.getOrDefault(false)

        /** La grandezza del file, in byte. */
        fun size(context: Context, doc: Uri): Long = runCatching {
            context.contentResolver.query(doc, arrayOf(Document.COLUMN_SIZE), null, null, null)?.use { if (it.moveToFirst()) it.getLong(0) else 0L } ?: 0L
        }.getOrDefault(0L)
    }
}
