package io.github.baldborne94.baldmusic.core.subsonic

import io.github.baldborne94.baldmusic.core.library.ArtistNames
import kotlinx.serialization.Serializable
import java.text.Collator
import java.util.Locale
import kotlin.math.abs

/**
 * Le raccolte del server (le cartelle della musica sul NAS), con le stesse regole dell'app web
 * (`web/src/lib/collections.ts`), così si allineano anche dal telefono senza aprire il sito:
 * ogni prima cartella della musica diventa una playlist di Navidrome riconosciuta dal commento;
 * i brani nuovi della cartella entrano da soli, quelli tolti a mano non tornano; una raccolta
 * eliminata non si ricrea (l'elenco sta su Navidrome, in una playlist solo per le app); una
 * cartella rinominata si porta dietro la sua raccolta. Non si toccano mai i file.
 */
object ServerCollections {

    /** Lo stesso commento di `collectionComment` nel web. */
    fun comment(folder: String) = "${Playlist.COLLECTION_MARK}$folder» · BaldMusic"

    private val MARK = Regex("^" + Regex.escape(Playlist.COLLECTION_MARK) + "(.+)»")

    /** La cartella di una raccolta, o null se è una playlist normale. */
    fun folderOf(comment: String?): String? = comment?.let { MARK.find(it)?.groupValues?.get(1) }

    /**
     * Percorso vero del file (C:\…, /…) o quello finto «Artista/Album/brano» che Navidrome manda
     * se nel lettore non è attivo «Report Real Path».
     */
    fun isRealPath(path: String): Boolean = Regex("""^([A-Za-z]:[\\/]|[\\/])""").containsMatchIn(path)

    /** Come `Intl.Collator("it", { numeric: true, sensitivity: "base" })` del web. */
    val natural: Comparator<String> = run {
        val text = Collator.getInstance(Locale.ITALIAN).apply { strength = Collator.PRIMARY }
        val parts = Regex("""\d+|\D+""")
        Comparator { a, b ->
            val x = parts.findAll(a).map { it.value }.toList()
            val y = parts.findAll(b).map { it.value }.toList()
            for (i in 0 until minOf(x.size, y.size)) {
                val p = x[i]
                val q = y[i]
                val c = if (p[0].isDigit() && q[0].isDigit()) {
                    p.trimStart('0').length.compareTo(q.trimStart('0').length).takeIf { it != 0 } ?: p.trimStart('0').compareTo(q.trimStart('0'))
                } else {
                    text.compare(p, q)
                }
                if (c != 0) return@Comparator c
            }
            x.size.compareTo(y.size)
        }
    }

    /**
     * Le cartelle-raccolta: la prima cartella sotto quella che contiene tutta la musica, coi brani
     * in ordine di percorso (sottocartelle comprese). I file sciolti nella cartella principale non
     * sono di nessuna raccolta. Senza percorsi veri, niente.
     */
    fun folders(songs: List<Song>): Map<String, List<String>> {
        val files = songs.mapNotNull { s ->
            s.path?.takeIf(::isRealPath)?.let { p -> s.id to p.split('\\', '/').filter { it.isNotEmpty() } }
        }
        if (files.isEmpty()) return emptyMap()
        var root = files[0].second.dropLast(1)
        for ((_, parts) in files) {
            var i = 0
            while (i < root.size && i < parts.size - 1 && root[i] == parts[i]) i++
            root = root.take(i)
        }
        val byFolder = LinkedHashMap<String, MutableList<Pair<String, String>>>()
        for ((id, parts) in files) {
            val rel = parts.drop(root.size)
            if (rel.size < 2) continue
            byFolder.getOrPut(rel[0]) { mutableListOf() } += id to rel.joinToString("/")
        }
        val out = LinkedHashMap<String, List<String>>()
        for (folder in byFolder.keys.sortedWith(natural)) {
            out[folder] = byFolder.getValue(folder).sortedWith(compareBy(natural) { it.second }).map { it.first }
        }
        return out
    }

    /**
     * Cosa ricorda il telefono: i brani di ogni cartella con una raccolta all'ultimo allineamento.
     * Lo stesso formato del web (`v` 2): una cartella senza raccolta che si ricordava era una
     * raccolta eliminata altrove.
     */
    @Serializable
    data class State(val known: Map<String, List<String>> = emptyMap(), val v: Int = 2)

    data class Create(val folder: String, val songIds: List<String>)
    data class Add(val playlistId: String, val songIds: List<String>)

    data class Plan(
        /** Raccolte da creare, coi brani in ordine di percorso. */
        val create: List<Create>,
        /** Brani arrivati nella cartella dall'ultima volta (chi li ha già in playlist li salta). */
        val add: List<Add>,
        /** Cartelle delle raccolte eliminate: non si ricreano da sole. */
        val deleted: List<String>,
        /** Raccolte sparite da quando il telefono le ha viste (eliminate altrove): vanno nell'elenco su Navidrome. */
        val newlyDeleted: List<String>,
        val state: State,
    )

    /**
     * Allinea le raccolte alle cartelle (come `planSync` nel web). [deleted]: le cartelle delle
     * raccolte eliminate, dall'elenco su Navidrome. Una cartella senza raccolta diventa raccolta,
     * anche la prima volta (telefono nuovo), tranne se la sua raccolta è stata eliminata.
     */
    fun plan(folders: Map<String, List<String>>, playlists: List<Playlist>, saved: State?, deleted: Set<String>): Plan {
        val byFolder = LinkedHashMap<String, String>()
        for (p in playlists) folderOf(p.comment)?.let { f -> byFolder.putIfAbsent(f, p.id) }
        val known = LinkedHashMap<String, List<String>>()
        // Le cartelle sparite che avevano una raccolta restano ricordate: se tornano, la raccolta c'è o è stata eliminata.
        saved?.known?.forEach { (f, ids) -> if (f !in folders && f !in deleted) known[f] = ids }
        val create = mutableListOf<Create>()
        val add = mutableListOf<Add>()
        val gone = mutableListOf<String>()
        val newlyDeleted = mutableListOf<String>()
        for ((folder, ids) in folders) {
            val seen = saved?.known?.get(folder)
            val playlistId = byFolder[folder]
            when {
                playlistId != null -> if (seen != null) {
                    val before = seen.toHashSet()
                    ids.filter { it !in before }.takeIf { it.isNotEmpty() }?.let { add += Add(playlistId, it) }
                }
                folder in deleted -> { gone += folder; continue }
                // Aveva una raccolta e non c'è più: eliminata (dal sito o da un altro telefono).
                seen != null -> { newlyDeleted += folder; gone += folder; continue }
                else -> create += Create(folder, ids)
            }
            // Si ricorda solo cosa c'è ora: un file tolto e poi rimesso nella cartella rientra.
            known[folder] = ids
        }
        return Plan(create, add, gone, newlyDeleted, State(known))
    }

    /** Le cartelle eliminate scritte nella playlist dell'elenco, o null se non è quella playlist. */
    fun deletedInStore(comment: String?): List<String>? {
        if (comment == null || !comment.startsWith(Playlist.DELETED_COLLECTIONS_MARK)) return null
        return runCatching {
            kotlinx.serialization.json.Json.decodeFromString<List<String>>(comment.removePrefix(Playlist.DELETED_COLLECTIONS_MARK))
        }.getOrDefault(emptyList())
    }

    /** Il commento della playlist dell'elenco (come `deletedStoreComment` nel web: in ordine, senza doppi). */
    fun deletedStoreComment(folders: Collection<String>): String =
        Playlist.DELETED_COLLECTIONS_MARK + kotlinx.serialization.json.Json.encodeToString(folders.toSortedSet().toList())

    /** Lo stesso nome del web: la playlist dell'elenco delle raccolte eliminate. */
    const val DELETED_STORE_NAME = "BaldMusic · raccolte eliminate"

    data class Orphan(val id: String, val name: String, val folder: String, val songs: List<Song>)

    data class Follow(
        val playlistId: String,
        /** La cartella nuova. */
        val folder: String,
        /** Il nome nuovo, se la raccolta aveva ancora il nome della cartella; null = lo tieni. */
        val rename: String?,
        /** I brani della raccolta con gli ID nuovi dei file (se sono cambiati), nello stesso ordine. */
        val songIds: List<String>,
        /** ID vecchio → nuovo, per preferiti e altre playlist. */
        val newIds: Map<String, String>,
    )

    /** Stesso file: entro 2 s, come sul telefono e nel web. */
    private const val SAME_FILE_S = 2

    /**
     * Cartelle rinominate o spostate (come `planFollow` nel web): una raccolta la cui cartella non
     * c'è più ([orphans]) segue la cartella nuova, ancora senza raccolta e non eliminata, che ha
     * almeno metà dei suoi brani (stesso ID, o stesso titolo e durata entro 2 s se Navidrome ha dato
     * ID nuovi). Ogni cartella ne prende una sola, dalla più somigliante.
     */
    fun follow(folders: Map<String, List<Song>>, orphans: List<Orphan>, taken: Set<String>, deleted: Set<String>): List<Follow> {
        val fresh = folders.filter { (f, songs) -> f !in taken && f !in deleted && songs.isNotEmpty() }
        if (fresh.isEmpty() || orphans.isEmpty()) return emptyList()
        val finders = fresh.mapValues { (_, songs) ->
            val byId = songs.associateBy { it.id }
            val byTitle = songs.groupBy { ArtistNames.key(it.title) }
            val find: (Song) -> Song? = { t ->
                byId[t.id] ?: byTitle[ArtistNames.key(t.title)]?.firstOrNull { o ->
                    t.duration != null && o.duration != null && abs(t.duration - o.duration) <= SAME_FILE_S
                }
            }
            find
        }
        data class Match(val orphan: Orphan, val folder: String, val found: Map<String, String>)
        val matches = orphans.filter { it.songs.isNotEmpty() }.flatMap { o ->
            finders.mapNotNull { (f, find) ->
                val found = LinkedHashMap<String, String>()
                for (t in o.songs) find(t)?.let { found[t.id] = it.id }
                Match(o, f, found).takeIf { found.size * 2 >= o.songs.size }
            }
        }.sortedByDescending { it.found.size }
        val usedPlaylists = HashSet<String>()
        val usedFolders = HashSet<String>()
        return matches.mapNotNull { m ->
            if (m.orphan.id in usedPlaylists || m.folder in usedFolders) return@mapNotNull null
            usedPlaylists += m.orphan.id
            usedFolders += m.folder
            val newIds = m.found.filter { (a, b) -> a != b }
            Follow(
                playlistId = m.orphan.id,
                folder = m.folder,
                rename = if (m.orphan.name == m.orphan.folder) m.folder else null,
                songIds = m.orphan.songs.map { newIds[it.id] ?: it.id },
                newIds = newIds,
            )
        }
    }
}
