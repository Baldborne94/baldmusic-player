package io.github.baldborne94.baldmusic.core.library

import io.github.baldborne94.baldmusic.core.library.LibraryNode.Kind
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/** Un brano salvato sul dispositivo, come l'app lo legge dal telefono. */
data class LocalTrack(
    val id: Long,
    val title: String,
    val artist: String?,
    val artistId: Long,
    val album: String?,
    val albumId: Long,
    val durationMs: Long,
    /** 0 = tag assente. */
    val disc: Int,
    /** 0 = tag assente. */
    val track: Int,
    /** 0 = tag assente. */
    val year: Int,
    /** Secondi dall'epoch in cui il file è arrivato sul dispositivo. */
    val dateAdded: Long,
    /** Cartella che contiene il file, es. "Music/Queen". */
    val folder: String,
)

/**
 * Le regole della musica sul dispositivo: come i brani diventano album, artisti,
 * singoli, decenni, cartelle e radio. Logica pura, senza Android, così si testa:
 * l'app le passa i brani letti dal telefono e l'indirizzo con cui riprodurli.
 */
class LocalCatalog(
    private val streamUri: (Long) -> String,
    private val random: Random = Random.Default,
) {
    @Volatile private var shuffled: List<LocalTrack> = emptyList()

    /** Le radio già pescate: riaprendole (o ricostruendo la coda) restano uguali. */
    private val radios = ConcurrentHashMap<Long, List<LocalTrack>>()

    fun sections(): List<LibraryNode> = listOf(
        section(RECENT, "Aggiunti di recente", grid = true),
        section(ALBUMS, "Album", grid = true),
        section(SINGLES, "Singoli e brani sparsi"),
        section(ARTISTS, "Artisti"),
        section(SONGS, "Tutti i brani"),
        section(SHUFFLE, "Mix casuale"),
        section(DECADES, "Decenni"),
        section(FOLDERS, "Cartelle"),
    )

    private fun section(kind: String, title: String, grid: Boolean = false) =
        LibraryNode(MediaId(KEY, kind).toString(), title, Kind.SECTION, gridChildren = grid)

    fun children(id: MediaId, all: List<LocalTrack>): List<LibraryNode> = when (id.kind) {
        // Un "album" con un solo brano quasi sempre non è un album: è un file con il
        // tag album sbagliato (titolo al posto dell'album) o un singolo. Non lo si
        // mostra tra gli album: si raccoglie con gli altri come brano sparso.
        RECENT -> {
            val (real, singles) = all.groupBy { it.albumId }.values.partition { it.size > 1 }
            val newest = real.associate { t -> t.first().albumId to t.maxOf { it.dateAdded } }
            albums(real.flatten()).sortedByDescending { newest[MediaId.parse(it.mediaId)?.id?.toLongOrNull()] ?: 0 }.take(30) +
                singles.flatten().sortedByDescending { it.dateAdded }.take(30).map { node(it, MediaId(KEY, RECENT)) }
        }
        ALBUMS -> albums(realAlbums(all)).sortedBy { it.title.lowercase() }
        SINGLES -> singlesOf(all).sortedWith(compareBy({ it.artist?.lowercase() }, { it.year }, { it.title.lowercase() })).map { node(it, id.copy(parent = null)) }
        DECADES -> all.filter { it.year > 0 }.groupBy { it.year / 10 * 10 }.toSortedMap(compareByDescending { it }).map { (decade, tracks) ->
            LibraryNode(MediaId(KEY, DECADE, "$decade").toString(), decadeName(decade), Kind.SECTION, subtitle = count(tracks.size))
        }
        DECADE -> {
            val decade = id.id?.toIntOrNull()
            if (decade == null) emptyList() else all.filter { it.year / 10 * 10 == decade }
                .sortedWith(compareBy({ it.artist?.lowercase() }, { it.year }, { it.album?.lowercase() }, { it.disc }, { it.track }))
                .map { node(it, id.copy(parent = null)) }
        }
        FOLDERS -> all.groupBy { it.folder }.toSortedMap(String.CASE_INSENSITIVE_ORDER).map { (folder, tracks) ->
            LibraryNode(
                MediaId(KEY, FOLDER, folder).toString(),
                folder.substringAfterLast('/').ifEmpty { folder },
                Kind.FOLDER,
                subtitle = "${folder.substringBeforeLast('/', "")}${if (folder.contains('/')) " · " else ""}${count(tracks.size)}",
                artwork = "$KEY/${tracks.first().albumId}",
            )
        }
        RADIO -> {
            val seed = id.id?.toLongOrNull()?.let { sid -> all.firstOrNull { it.id == sid } }
            if (seed == null) emptyList() else radios.getOrPut(seed.id) { radio(seed, all) }.map { node(it, id.copy(parent = null)) }
        }
        ARTIST_MIX -> {
            val name = id.id
            val seed = name?.let { n -> all.filter { it.artist.equals(n, ignoreCase = true) }.randomOrNull(random) }
            if (name == null || seed == null) emptyList()
            else radios.getOrPut(-name.lowercase().hashCode().toLong() - 1) { radio(seed, all).drop(1).shuffled(random) }.map { node(it, id.copy(parent = null)) }
        }
        FOLDER -> all.filter { it.folder == id.id }
            .sortedWith(compareBy({ it.album?.lowercase() }, { it.disc }, { it.track }, { it.title.lowercase() }))
            .map { node(it, id.copy(parent = null)) }
        ARTISTS -> artists(all)
        SONGS -> all.sortedBy { it.title.lowercase() }.map { node(it, MediaId(KEY, SONGS)) }
        SHUFFLE -> {
            // Il mix si pesca entrando nella sezione; "last" lo ritrova identico per la coda.
            if (id.id == null) shuffled = all.shuffled(random)
            shuffled.map { node(it, MediaId(KEY, SHUFFLE, "last")) }
        }
        ALBUM -> {
            val albumId = id.id?.toLongOrNull()
            all.filter { it.albumId == albumId }
                .sortedWith(compareBy({ it.disc }, { it.track }, { it.title.lowercase() }))
                .map { node(it, id.copy(parent = null)) }
        }
        ARTIST -> {
            val artistId = id.id?.toLongOrNull()
            val own = all.filter { it.artistId == artistId }
            val singles = singlesOf(own)
            albums(realAlbums(own)).sortedByDescending { it.year ?: 0 } + listOfNotNull(
                singles.takeIf { it.isNotEmpty() }?.let { s ->
                    LibraryNode(MediaId(KEY, ARTIST_SINGLES, "$artistId").toString(), "Singoli e brani sparsi", Kind.SECTION, subtitle = count(s.size), artwork = "$KEY/${s.first().albumId}")
                },
            )
        }
        ARTIST_SINGLES -> {
            val artistId = id.id?.toLongOrNull()
            singlesOf(all.filter { it.artistId == artistId }).sortedWith(compareBy({ it.year }, { it.title.lowercase() })).map { node(it, id.copy(parent = null)) }
        }
        else -> emptyList()
    }

    /**
     * Una radio da un brano: lui per primo, poi un misto di brani dello stesso
     * artista e di altri artisti dello stesso periodo (o della stessa cartella,
     * che spesso vuol dire stesso genere), senza ripetere lo stesso album di fila.
     */
    private fun radio(seed: LocalTrack, all: List<LocalTrack>): List<LocalTrack> {
        val sameArtist = all.filter { it.artistId == seed.artistId && it.id != seed.id }.shuffled(random).take(15)
        val decade = seed.year / 10
        val related = all.filter {
            it.artistId != seed.artistId && (
                (seed.year > 0 && it.year / 10 == decade) || it.folder.substringBeforeLast('/') == seed.folder.substringBeforeLast('/')
                )
        }.shuffled(random).take(35)
        val pool = if (related.size < 10) related + all.filter { it.artistId != seed.artistId }.shuffled(random).take(30 - related.size) else related
        // Alterna: due brani "simili", uno dell'artista.
        val out = mutableListOf(seed)
        val a = ArrayDeque(sameArtist)
        val r = ArrayDeque(pool.distinctBy { it.id })
        while (a.isNotEmpty() || r.isNotEmpty()) {
            repeat(2) { r.removeFirstOrNull()?.let(out::add) }
            a.removeFirstOrNull()?.let(out::add)
        }
        return out.distinctBy { it.id }.take(50)
    }

    fun search(query: String, all: List<LocalTrack>): List<LibraryNode> {
        if (query.isBlank()) return emptyList()
        val q = query.trim().lowercase()
        val albums = albums(all.filter { it.album?.lowercase()?.contains(q) == true }).take(20)
        val artists = artists(all.filter { it.artist?.lowercase()?.contains(q) == true }).take(10)
        val songs = all.filter {
            it.title.lowercase().contains(q) || it.artist?.lowercase()?.contains(q) == true
        }.take(50).map { node(it, null) }
        return artists + albums + songs
    }

    /** I brani che stanno in un album con almeno due brani. */
    private fun realAlbums(songs: List<LocalTrack>): List<LocalTrack> = songs.groupBy { it.albumId }.values.filter { it.size > 1 }.flatten()

    /** I brani soli nel loro "album": singoli veri o tag sbagliati. */
    private fun singlesOf(songs: List<LocalTrack>): List<LocalTrack> = songs.groupBy { it.albumId }.values.filter { it.size == 1 }.flatten()

    private fun albums(songs: List<LocalTrack>): List<LibraryNode> = songs.groupBy { it.albumId }.map { (albumId, tracks) ->
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

    private fun artists(songs: List<LocalTrack>): List<LibraryNode> = songs.groupBy { it.artistId }.map { (artistId, tracks) ->
        val groups = tracks.groupBy { it.albumId }.values
        val real = groups.count { it.size > 1 }
        val singles = groups.size - real
        LibraryNode(
            mediaId = MediaId(KEY, ARTIST, "$artistId").toString(),
            title = tracks.first().artist ?: "Artista sconosciuto",
            kind = Kind.ARTIST,
            subtitle = listOfNotNull(
                real.takeIf { it > 0 }?.let { if (it == 1) "1 album" else "$it album" },
                singles.takeIf { it > 0 }?.let { if (it == 1) "1 singolo" else "$it singoli" },
            ).joinToString(" · "),
            artwork = "$KEY/${(groups.firstOrNull { it.size > 1 } ?: groups.first()).first().albumId}",
            gridChildren = true,
        )
    }.sortedBy { it.title.lowercase() }

    /** Il brano come nodo della libreria, col contenitore da cui è stato scelto. */
    fun node(t: LocalTrack, parent: MediaId?) = LibraryNode(
        mediaId = MediaId.track(KEY, "${t.id}", parent).toString(),
        title = t.title,
        kind = Kind.TRACK,
        subtitle = t.artist,
        artist = t.artist,
        album = t.album,
        artwork = "$KEY/${t.albumId}",
        durationMs = t.durationMs,
        trackNumber = t.track.takeIf { it > 0 },
        discNumber = t.disc.takeIf { it > 0 },
        year = t.year.takeIf { it > 0 },
        streamUri = streamUri(t.id),
        albumId = MediaId(KEY, ALBUM, "${t.albumId}").toString(),
        artistId = MediaId(KEY, ARTIST, "${t.artistId}").toString(),
    )

    companion object {
        const val KEY = "local"
        const val RECENT = "recent"
        const val DECADES = "decades"
        const val DECADE = "decade"
        const val FOLDERS = "folders"
        const val FOLDER = "folder"
        const val RADIO = "radio"
        const val ARTIST_MIX = "artistmix"
        const val ALBUMS = "albums"
        const val ARTISTS = "artists"
        const val SONGS = "songs"
        const val SHUFFLE = "shuffle"
        const val ALBUM = "album"
        const val ARTIST = "artist"
        const val ARTIST_SINGLES = "artistsingles"
        const val SINGLES = "singles"

        private fun count(n: Int) = if (n == 1) "1 brano" else "$n brani"

        private fun decadeName(decade: Int) = if (decade >= 2000) "Anni $decade" else "Anni ${decade % 100}"
    }
}
