package io.github.baldborne94.baldmusic.core.library

import io.github.baldborne94.baldmusic.core.library.LibraryNode.Kind
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/** Un brano salvato sul dispositivo, come l'app lo legge dal telefono. */
data class LocalTrack(
    val id: Long,
    val title: String,
    /** L'artista come va mostrato, es. "Avril Lavigne feat. Chad Kroeger". */
    val artist: String?,
    /** ID dell'artista per MediaStore: serve solo a ritrovare i vecchi collegamenti. */
    val artistId: Long,
    val album: String?,
    /** ID dell'album per MediaStore: dà la copertina e ritrova i vecchi collegamenti. */
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
    // Dai tag letti nel file (vedi FileTags); vuoti finché il file non è stato letto.
    val albumArtist: String? = null,
    val artists: List<String> = emptyList(),
    val artistMbids: List<String> = emptyList(),
    val albumArtistMbids: List<String> = emptyList(),
    val albumMbid: String? = null,
    val releaseGroupMbid: String? = null,
    val releaseTypes: List<String> = emptyList(),
    val compilation: Boolean = false,
    /** Anno della prima uscita: una ristampa del 2011 di un disco del 1977 è del 1977. 0 = assente. */
    val originalYear: Int = 0,
    val discSubtitle: String? = null,
    val replayGain: ReplayGain? = null,
    /** Nomi di ordinamento di Picard ("Beatles, The"): decidono l'ordine alfabetico. */
    val artistSort: String? = null,
    val albumArtistSort: String? = null,
) {
    /** L'anno da mostrare e su cui ordinare. */
    val shownYear get() = if (originalYear > 0) originalYear else year

    /** I dati del brano come li vede l'app, per la scheda "Informazioni sul brano". */
    fun details(tagsRead: Boolean): List<Pair<String, String>> {
        fun yes(b: Boolean) = if (b) "sì" else "no"
        return listOfNotNull(
            "Titolo" to title,
            "Artista" to (artist ?: "—"),
            artists.takeIf { it.size > 1 }?.let { "Artisti" to it.joinToString(", ") },
            "Album" to (album ?: "—"),
            "Artista dell'album" to (albumArtist ?: "—"),
            "Traccia" to (if (track > 0) "$track" else "—") + (if (disc > 0) " · disco $disc" else ""),
            "Anno" to (if (year > 0) "$year" else "—"),
            originalYear.takeIf { it > 0 && it != year }?.let { "Prima uscita" to "$it" },
            releaseTypes.takeIf { it.isNotEmpty() }?.let { "Tipo di uscita" to it.joinToString(", ") },
            "Raccolta" to yes(compilation),
            "Riconosciuto da MusicBrainz" to yes(albumMbid != null || releaseGroupMbid != null || artistMbids.isNotEmpty()),
            replayGain?.let { rg -> "ReplayGain" to (listOfNotNull(rg.trackGain?.let { "brano %+.1f dB".format(it) }, rg.albumGain?.let { "album %+.1f dB".format(it) }).joinToString(" · ") + if (rg.measured) " (misurato dall'app)" else "") },
            "Durata" to "%d:%02d".format(durationMs / 60_000, durationMs / 1000 % 60),
            "Cartella" to folder,
            "Tag letti dal file" to if (tagsRead) "sì" else "non ancora (valgono quelli di Android)",
        )
    }

    /** Il brano con i tag letti dal file, che valgono più di quelli di MediaStore. */
    fun withTags(t: FileTags): LocalTrack {
        if (t.isEmpty) return this
        return copy(
            title = t.title ?: title,
            artist = t.artist ?: artist,
            // MediaStore, senza album, usa il nome della cartella: se il file l'album non ce l'ha, non c'è.
            album = t.album,
            albumArtist = t.albumArtist ?: albumArtist,
            disc = if (t.disc > 0) t.disc else disc,
            track = if (t.track > 0) t.track else track,
            // Anche l'anno: se il file non ce l'ha (o ha la data zero), non c'è.
            year = t.year,
            artists = t.artists,
            artistMbids = t.artistMbids,
            albumArtistMbids = t.albumArtistMbids,
            albumMbid = t.albumMbid,
            releaseGroupMbid = t.releaseGroupMbid,
            releaseTypes = t.releaseTypes,
            compilation = t.compilation,
            originalYear = t.originalYear,
            discSubtitle = t.discSubtitle,
            replayGain = t.replayGain,
            artistSort = t.artistSort,
            albumArtistSort = t.albumArtistSort,
        )
    }
}

/**
 * Le regole della musica sul dispositivo: come i brani diventano album, artisti,
 * singoli, decenni, cartelle e radio. Logica pura, senza Android, così si testa:
 * l'app le passa i brani letti dal telefono e l'indirizzo con cui riprodurli.
 *
 * - Un **artista** è riconosciuto dall'ID MusicBrainz quando c'è, altrimenti dal
 *   nome (maiuscole, spazi e accenti non contano). Un brano "A feat. B" è di A e
 *   compare anche da B.
 * - Un **album** è la pubblicazione MusicBrainz (edizioni diverse dello stesso disco
 *   diventano una scheda sola), altrimenti artista dell'album + titolo, e per i file
 *   senza artista dell'album il raggruppamento di Android. Appartiene al suo artista
 *   dell'album; se è di artisti vari si mostra come "Artisti vari".
 * - Tra gli album vanno solo i dischi di cui hai almeno due brani e che non sono
 *   singoli: gli altri brani stanno in "Singoli e brani sparsi".
 */
class LocalCatalog(
    private val streamUri: (Long) -> String,
    private val random: Random = Random.Default,
    /** Quante volte è stato ascoltato ogni brano, per ID di [TrackRef]: mette in cima gli artisti più ascoltati. */
    private val plays: () -> Map<String, Int> = { emptyMap() },
) {
    @Volatile private var shuffled: List<LocalTrack> = emptyList()

    /** Le radio già pescate: riaprendole (o ricostruendo la coda) restano uguali. */
    private val radios = ConcurrentHashMap<Long, List<LocalTrack>>()

    @Volatile private var indexed: Index? = null

    /** L'indice di artisti e album, ricalcolato solo quando cambia l'elenco dei brani. */
    private fun index(all: List<LocalTrack>): Index = indexed?.takeIf { it.tracks === all } ?: Index(all).also { indexed = it }

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

    fun children(id: MediaId, all: List<LocalTrack>): List<LibraryNode> {
        val ix = index(all)
        return when (id.kind) {
            RECENT -> {
                val newest = ix.shownAlbums.sortedByDescending { a -> a.tracks.maxOf { it.dateAdded } }.take(30)
                newest.map { albumNode(ix, it) } + ix.loose.sortedByDescending { it.dateAdded }.take(30).map { node(ix, it, MediaId(KEY, RECENT)) }
            }
            // Anche i dischi di artisti vari: "Raccolte" sono le cartelle (vedi folderCollections).
            // Per lettera: in Android Auto ogni lettera è un'intestazione, e l'elenco si scorre meglio.
            ALBUMS -> ix.shownAlbums.sortedBy { ArtistNames.sortKey(it.title) }.map { albumNode(ix, it).copy(group = ArtistNames.letter(it.title)) }
            SINGLES -> ix.loose.sortedWith(compareBy({ ArtistNames.sortKey(it.artist.orEmpty()) }, { it.shownYear }, { it.title.lowercase() }))
                .map { node(ix, it, id.copy(parent = null), group = ArtistNames.letter(it.artist.orEmpty())) }
            DECADES -> all.filter { it.shownYear > 0 }.groupBy { it.shownYear / 10 * 10 }.toSortedMap(compareByDescending { it }).map { (decade, tracks) ->
                LibraryNode(MediaId(KEY, DECADE, "$decade").toString(), decadeName(decade), Kind.SECTION, subtitle = count(tracks.size))
            }
            DECADE -> {
                val decade = id.id?.toIntOrNull()
                if (decade == null) emptyList() else all.filter { it.shownYear > 0 && it.shownYear / 10 * 10 == decade }
                    .sortedWith(compareBy({ it.artist?.lowercase() }, { it.shownYear }, { it.album?.lowercase() }, { it.disc }, { it.track }))
                    .map { node(ix, it, id.copy(parent = null)) }
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
                if (seed == null) emptyList() else radios.getOrPut(seed.id) { radio(ix, seed, all) }.map { node(ix, it, id.copy(parent = null)) }
            }
            ARTIST_MIX -> {
                val name = id.id
                val seed = name?.let { n ->
                    val key = ArtistNames.key(n)
                    all.filter { t -> ix.credits(t).any { ArtistNames.key(ix.name(it)) == key } || t.artist.equals(n, ignoreCase = true) }.randomOrNull(random)
                }
                if (name == null || seed == null) emptyList()
                else radios.getOrPut(-name.lowercase().hashCode().toLong() - 1) { radio(ix, seed, all).drop(1).shuffled(random) }.map { node(ix, it, id.copy(parent = null)) }
            }
            FOLDER -> all.filter { it.folder == id.id }
                .sortedWith(compareBy({ it.album?.lowercase() }, { it.disc }, { it.track }, { it.title.lowercase() }))
                .map { node(ix, it, id.copy(parent = null)) }
            ARTISTS -> {
                // In cima i tuoi più ascoltati, poi tutti per lettera.
                val top = topArtists(ix, all)
                listOfNotNull(
                    top.takeIf { it.size >= 3 }?.let { keys ->
                        LibraryNode(
                            MediaId(KEY, TOP_ARTISTS).toString(), "I tuoi più ascoltati", Kind.SECTION,
                            subtitle = keys.take(3).joinToString(", ") { ix.name(it) },
                            artwork = artists(ix, keys.take(1)).firstOrNull()?.artwork,
                            gridChildren = true,
                        )
                    },
                ) + artists(ix, ix.artistKeys)
            }
            TOP_ARTISTS -> artists(ix, topArtists(ix, all), alphabetical = false)
            SONGS -> all.sortedBy { ArtistNames.sortKey(it.title) }.map { node(ix, it, MediaId(KEY, SONGS), group = ArtistNames.letter(it.title)) }
            SHUFFLE -> {
                // Il mix si pesca entrando nella sezione; "last" lo ritrova identico per la coda.
                if (id.id == null) shuffled = all.shuffled(random)
                shuffled.map { node(ix, it, MediaId(KEY, SHUFFLE, "last")) }
            }
            ALBUM -> {
                val tracks = ix.album(id.id)?.tracks.orEmpty()
                val multiDisc = tracks.map { it.disc }.filter { it > 0 }.distinct().size > 1
                tracks.map { t -> node(ix, t, id.copy(parent = null), group = if (multiDisc && t.disc > 0) discLabel(t.disc, t.discSubtitle) else null) }
            }
            ARTIST -> {
                val key = ix.artistKey(id.id) ?: return emptyList()
                val page = ix.page(key)
                // Discografia per tipo (Album, EP, Dal vivo, Colonne sonore…), ognuno dal più recente.
                val albums = Discography.grouped(page.albums, { a -> a.tracks.flatMap { it.releaseTypes }.distinct() }, { a -> a.tracks.maxOf { it.shownYear } }, { it.title })
                    .map { (kind, a) -> albumNode(ix, a).copy(group = kind.title) }
                // Senza album, i brani si vedono subito invece che dentro una sezione.
                val own = if (albums.isEmpty()) {
                    page.loose.map { node(ix, it, MediaId(KEY, ARTIST, key)) }
                } else {
                    listOfNotNull(
                        page.loose.takeIf { it.isNotEmpty() }?.let { s ->
                            LibraryNode(MediaId(KEY, ARTIST_SINGLES, key).toString(), "Singoli e brani sparsi", Kind.SECTION, subtitle = count(s.size), artwork = "$KEY/${s.first().albumId}", group = OTHER_GROUP)
                        },
                    )
                }
                albums + own + listOfNotNull(
                    page.appearsOn.takeIf { it.isNotEmpty() }?.let { s ->
                        LibraryNode(MediaId(KEY, ARTIST_APPEARS, key).toString(), "Compare in", Kind.SECTION, subtitle = count(s.size), artwork = "$KEY/${s.first().albumId}", group = OTHER_GROUP)
                    },
                )
            }
            ARTIST_SINGLES -> ix.artistKey(id.id)?.let { key -> ix.page(key).loose.map { node(ix, it, MediaId(KEY, ARTIST_SINGLES, key)) } }.orEmpty()
            ARTIST_APPEARS -> ix.artistKey(id.id)?.let { key -> ix.page(key).appearsOn.map { node(ix, it, MediaId(KEY, ARTIST_APPEARS, key)) } }.orEmpty()
            else -> emptyList()
        }
    }

    /**
     * Una radio da un brano: lui per primo, poi un misto di brani dello stesso
     * artista e di altri artisti dello stesso periodo (o della stessa cartella,
     * che spesso vuol dire stesso genere), senza ripetere lo stesso album di fila.
     */
    private fun radio(ix: Index, seed: LocalTrack, all: List<LocalTrack>): List<LocalTrack> {
        val artist = ix.primary(seed)
        val sameArtist = all.filter { ix.primary(it) == artist && it.id != seed.id }.shuffled(random).take(15)
        val decade = seed.shownYear / 10
        val related = all.filter {
            ix.primary(it) != artist && (
                (seed.shownYear > 0 && it.shownYear / 10 == decade) || it.folder.substringBeforeLast('/') == seed.folder.substringBeforeLast('/')
                )
        }.shuffled(random).take(35)
        val pool = if (related.size < 10) related + all.filter { ix.primary(it) != artist }.shuffled(random).take(30 - related.size) else related
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
        val q = ArtistNames.key(query)
        val ix = index(all)
        val artists = artists(ix, ix.artistKeys.filter { ArtistNames.key(ix.name(it)).contains(q) }).take(10)
        val albums = ix.albums.values.filter { ArtistNames.key(it.title).contains(q) }
            .sortedByDescending { it.tracks.size }.take(20).map { albumNode(ix, it) }
        val songs = all.filter { t ->
            ArtistNames.key(t.title).contains(q) || t.artist?.let { ArtistNames.key(it).contains(q) } == true
        }.take(50).map { node(ix, it, null) }
        return artists + albums + songs
    }

    private fun albumNode(ix: Index, a: Album) = LibraryNode(
        mediaId = MediaId(KEY, ALBUM, a.key).toString(),
        title = a.title,
        kind = Kind.ALBUM,
        subtitle = if (a.various) "Artisti vari" else a.owner?.let(ix::name),
        artist = if (a.various) null else a.owner?.let(ix::name),
        artwork = "$KEY/${a.tracks.first().albumId}",
        year = a.tracks.maxOf { it.shownYear }.takeIf { it > 0 },
    )

    /** Gli artisti più ascoltati (somma degli ascolti dei loro brani), al massimo 20. */
    private fun topArtists(ix: Index, all: List<LocalTrack>): List<String> {
        val counts = plays().takeIf { it.isNotEmpty() } ?: return emptyList()
        val byArtist = HashMap<String, Int>()
        all.forEach { t ->
            val n = counts[ref(t).id] ?: return@forEach
            ix.primary(t)?.let { byArtist.merge(it, n, Int::plus) }
        }
        return byArtist.entries.filter { it.key in ix.artistKeys }.sortedByDescending { it.value }.take(20).map { it.key }
    }

    private fun artists(ix: Index, keys: Collection<String>, alphabetical: Boolean = true): List<LibraryNode> = keys.mapNotNull { key ->
        val page = ix.page(key)
        val first = page.albums.firstOrNull()?.tracks?.first() ?: page.loose.firstOrNull() ?: page.appearsOn.firstOrNull() ?: return@mapNotNull null
        val songs = page.songs
        LibraryNode(
            mediaId = MediaId(KEY, ARTIST, key).toString(),
            title = ix.name(key),
            kind = Kind.ARTIST,
            subtitle = listOfNotNull(
                page.albums.size.takeIf { it > 0 }?.let { if (it == 1) "1 album" else "$it album" },
                count(songs),
            ).joinToString(" · "),
            artwork = "$KEY/${first.albumId}",
            gridChildren = true,
            group = ArtistNames.letter(ix.sortName(key)),
        ) to ArtistNames.sortKey(ix.sortName(key))
    }.let { list -> if (alphabetical) list.sortedBy { it.second } else list }.map { it.first }

    /** Il brano come nodo della libreria, col contenitore da cui è stato scelto. */
    fun node(t: LocalTrack, parent: MediaId?, all: List<LocalTrack>): LibraryNode = node(index(all), t, parent)

    private fun node(ix: Index, t: LocalTrack, parent: MediaId?, group: String? = null) = LibraryNode(
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
        year = t.shownYear.takeIf { it > 0 },
        streamUri = streamUri(t.id),
        albumId = ix.albumKeyOf(t)?.let { MediaId(KEY, ALBUM, it).toString() },
        artistId = ix.primary(t)?.let { MediaId(KEY, ARTIST, it).toString() },
        replayGain = t.replayGain,
        group = group,
    )

    /** Un disco: i brani che hai, nell'ordine del disco. */
    private class Album(
        val key: String,
        val title: String,
        /** Chiave dell'artista dell'album; null per le raccolte di artisti vari. */
        val owner: String?,
        val various: Boolean,
        val tracks: List<LocalTrack>,
    ) {
        val isSingle get() = tracks.any { "single" in it.releaseTypes }

        /** Va tra gli album (o le raccolte): almeno due brani e non un singolo. */
        val shown get() = tracks.size > 1 && !isSingle
    }

    /** La pagina di un artista. */
    private class ArtistPage(
        /** I suoi album, dal più recente. */
        val albums: List<Album>,
        /** I suoi brani fuori da quegli album. */
        val loose: List<LocalTrack>,
        /** I brani in cui compare, su dischi di altri o raccolte. */
        val appearsOn: List<LocalTrack>,
    ) {
        val songs get() = albums.sumOf { it.tracks.size } + loose.size + appearsOn.size
    }

    /** Artisti e album ricavati dai brani, calcolati una volta per elenco. */
    private class Index(val tracks: List<LocalTrack>) {
        private val names = HashMap<String, MutableMap<String, Int>>()
        private val credits = HashMap<Long, List<String>>()
        private val albumKeys = HashMap<Long, String>()
        private val sortNames = HashMap<String, String>()
        val albums = LinkedHashMap<String, Album>()

        init {
            // Nomi che i tag dicono essere un artista solo: non vanno spezzati ("AC/DC").
            val known = HashSet<String>()
            tracks.forEach { t ->
                t.albumArtist?.let { if (t.albumArtistMbids.size <= 1) known += ArtistNames.key(it) }
                if (t.artists.size == 1) known += ArtistNames.key(t.artists[0])
            }
            // Un nome scritto senza ID si attacca all'ID che lo stesso nome ha altrove, se è uno solo.
            val mbidOfName = HashMap<String, MutableSet<String>>()
            tracks.forEach { t ->
                val named = t.artists.ifEmpty { listOfNotNull(t.artist) }
                if (named.size == t.artistMbids.size) named.zip(t.artistMbids).forEach { (n, id) -> mbidOfName.getOrPut(ArtistNames.key(n)) { HashSet() } += id }
                if (t.albumArtist != null && t.albumArtistMbids.size == 1) mbidOfName.getOrPut(ArtistNames.key(t.albumArtist)) { HashSet() } += t.albumArtistMbids[0]
            }
            fun keyOf(name: String, mbid: String?): String {
                val k = mbid?.let { "mb:$it" } ?: mbidOfName[ArtistNames.key(name)]?.singleOrNull()?.let { "mb:$it" } ?: "n:${ArtistNames.key(name)}"
                names.getOrPut(k) { HashMap() }.merge(name.trim(), 1, Int::plus)
                return k
            }

            tracks.forEach { t ->
                val named = t.artists.ifEmpty { t.artist?.let { ArtistNames.split(it, known) }.orEmpty() }
                val ids = t.artistMbids.takeIf { it.size == named.size }
                val main = named.mapIndexed { i, n -> keyOf(n, ids?.get(i)) }
                // Il nome di ordinamento vale solo per un artista da solo (con più artisti non si sa di chi è).
                if (main.size == 1) t.artistSort?.takeIf { it.isNotBlank() }?.let { sortNames.putIfAbsent(main[0], it) }
                val guests = ArtistNames.featuredInTitle(t.title).map { keyOf(it, null) }
                credits[t.id] = (main + guests).distinct()
            }

            val groups = LinkedHashMap<String, MutableList<LocalTrack>>()
            tracks.forEach { t ->
                val album = t.album ?: return@forEach
                val key = t.releaseGroupMbid?.let { "rg:$it" }
                    ?: t.albumMbid?.let { "mb:$it" }
                    ?: t.albumArtist?.let { "n:${ArtistNames.key(it)}|${ArtistNames.key(album)}" }
                    // Senza artista dell'album resta il raggruppamento di Android.
                    ?: "ms:${t.albumId}"
                albumKeys[t.id] = key
                groups.getOrPut(key) { mutableListOf() } += t
            }
            groups.forEach { (key, list) ->
                val sorted = list.sortedWith(compareBy({ it.disc }, { it.track }, { it.title.lowercase() }))
                val first = sorted.first()
                val albumArtist = sorted.firstNotNullOfOrNull { it.albumArtist }
                val primaries = sorted.mapNotNull { credits[it.id]?.firstOrNull() }.distinct()
                val various = sorted.any { it.compilation || ArtistNames.VARIOUS_MBID in it.albumArtistMbids } ||
                    ArtistNames.isVarious(albumArtist) ||
                    (albumArtist == null && primaries.size > 1)
                val owner = when {
                    various -> null
                    albumArtist != null -> keyOf(ArtistNames.split(albumArtist, known).first(), first.albumArtistMbids.firstOrNull())
                    else -> primaries.firstOrNull()
                }
                if (owner != null && albumArtist != null) sorted.firstNotNullOfOrNull { it.albumArtistSort?.takeIf(String::isNotBlank) }?.let { sortNames[owner] = it }
                albums[key] = Album(key, first.album ?: "", owner, various, sorted)
            }
        }

        val shownAlbums: List<Album> = albums.values.filter { it.shown }

        /** I brani che non stanno in un album mostrato. */
        val loose: List<LocalTrack> = tracks.filter { t -> albumKeys[t.id]?.let { albums[it]?.shown } != true }

        /** Perché un brano è tra i brani sparsi; null se sta in un album. */
        fun looseReason(t: LocalTrack): LooseReason? {
            val album = albumKeys[t.id]?.let { albums[it] } ?: return LooseReason.NO_ALBUM
            return when {
                album.isSingle -> LooseReason.SINGLE
                album.tracks.size < 2 -> LooseReason.ALONE
                else -> null
            }
        }

        /** Quanti brani hai dello stesso disco del brano (lui compreso). */
        fun albumSize(t: LocalTrack): Int = albumKeys[t.id]?.let { albums[it]?.tracks?.size } ?: 0

        private val pages = HashMap<String, ArtistPage>()

        /** Chi ha un album o compare in un brano; non "Artisti vari". */
        val artistKeys: List<String> = (albums.values.mapNotNull { it.owner } + credits.values.flatten()).distinct()
            .filterNot { it == "mb:${ArtistNames.VARIOUS_MBID}" || ArtistNames.isVarious(name(it)) }

        fun credits(t: LocalTrack): List<String> = credits[t.id].orEmpty()

        /** L'artista principale del brano: dove porta "Vai all'artista". */
        fun primary(t: LocalTrack): String? = credits[t.id]?.firstOrNull()

        fun albumKeyOf(t: LocalTrack): String? = albumKeys[t.id]

        /** Il nome più usato per quell'artista. */
        fun name(key: String): String = names[key]?.maxByOrNull { it.value }?.key ?: key.substringAfter(':')

        /** Il nome su cui ordinare: quello di ordinamento di Picard se c'è, altrimenti il nome. */
        fun sortName(key: String): String = sortNames[key] ?: name(key)

        /** Un album dal suo ID; un numero è un vecchio ID di MediaStore. */
        fun album(id: String?): Album? {
            id ?: return null
            albums[id]?.let { return it }
            val old = id.toLongOrNull() ?: return null
            return tracks.firstOrNull { it.albumId == old }?.let { t -> albumKeys[t.id]?.let { albums[it] } }
        }

        /** La chiave di un artista dal suo ID; un numero è un vecchio ID di MediaStore. */
        fun artistKey(id: String?): String? {
            id ?: return null
            if (id in names) return id
            val old = id.toLongOrNull() ?: return null
            return tracks.firstOrNull { it.artistId == old }?.let(::primary)
        }

        @Synchronized
        fun page(key: String): ArtistPage = pages.getOrPut(key) {
            val own = shownAlbums.filter { it.owner == key }.sortedByDescending { a -> a.tracks.maxOf { it.shownYear } }
            val inOwn = own.flatMapTo(HashSet()) { a -> a.tracks.map { it.id } }
            // I brani in cui canta, più quelli dei dischi di cui è artista dell'album anche se
            // non canta (Hans Zimmer artista di "Gladiator", brano di "Hans Zimmer & Lisa Gerrard").
            val credited = tracks.filter { t -> t.id !in inOwn && (key in credits(t) || albumKeys[t.id]?.let { albums[it]?.owner } == key) }
            val (mine, others) = credited.partition { t ->
                val album = albumKeys[t.id]?.let { albums[it] }
                // Suo se l'album è suo, o se non sta in un album mostrato di altri ed è lui il primo artista.
                album?.owner == key || ((album == null || !album.shown) && primary(t) == key)
            }
            val order = compareBy<LocalTrack>({ it.shownYear }, { it.title.lowercase() })
            ArtistPage(own, mine.sortedWith(order), others.sortedWith(order))
        }
    }

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
        const val ARTIST_APPEARS = "artistappears"
        const val TOP_ARTISTS = "topartists"

        /** Intestazione di "Singoli e brani sparsi" e "Compare in" nella pagina di un artista. */
        const val OTHER_GROUP = "Altro"

        /** L'intestazione di un disco in un album su più dischi: "Disco 2 · The Two Towers". */
        fun discLabel(disc: Int, title: String?) = "Disco $disc" + (title?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")
        const val SINGLES = "singles"

        private fun count(n: Int) = if (n == 1) "1 brano" else "$n brani"

        /**
         * Toglie il 1970 quando è chiaramente un segnaposto: è l'anno della data zero dei
         * computer, e certi programmi per scaricare musica lo scrivono quando non sanno la
         * data. Se un brano datato su cinque (e almeno trenta) è "del 1970", non è vero:
         * senza, i decenni si riempirebbero di brani che con gli anni 70 non c'entrano.
         */
        fun withoutPlaceholderYears(tracks: List<LocalTrack>): List<LocalTrack> {
            val dated = tracks.count { it.shownYear > 0 }
            val epoch = tracks.count { it.shownYear == EPOCH_YEAR }
            if (epoch < 30 || epoch * 5 < dated) return tracks
            return tracks.map { t ->
                if (t.shownYear != EPOCH_YEAR) t
                else t.copy(year = if (t.year == EPOCH_YEAR) 0 else t.year, originalYear = if (t.originalYear == EPOCH_YEAR) 0 else t.originalYear)
            }
        }

        private const val EPOCH_YEAR = 1970

        /** Per ogni brano: perché è tra gli sparsi (o null) e quanti brani hai del suo disco. Per [LibraryHealth]. */
        fun albumFacts(tracks: List<LocalTrack>): Map<Long, AlbumFact> {
            val ix = Index(tracks)
            return tracks.associate { it.id to AlbumFact(ix.looseReason(it), ix.albumSize(it)) }
        }

        /**
         * Le cartelle del telefono come raccolte: la prima cartella dentro quella principale
         * ("Music/Movie Soundtracks", anche per i file in "Music/Movie Soundtracks/CD1"), con
         * i brani nell'ordine in cui sono arrivati sul telefono, che per una playlist
         * scaricata è di solito il suo. I file sciolti in "Music" o "Download" non fanno raccolta.
         */
        fun folderCollections(tracks: List<LocalTrack>): List<FolderCollection> =
            tracks.mapNotNull { t -> collectionFolder(t.folder)?.let { it to t } }
                .groupBy({ it.first }, { it.second })
                .map { (folder, list) ->
                    val ordered = list.sortedWith(compareBy({ it.dateAdded }, { it.folder }, { it.disc }, { it.track }, { it.title.lowercase() }))
                    FolderCollection(folder, folder.substringAfterLast('/'), ordered.map(::ref))
                }
                .sortedBy { it.name.lowercase() }

        /** "Music/Disney/CD1" → "Music/Disney"; null per i file sciolti e per la cartella di sistema Android. */
        fun collectionFolder(folder: String): String? {
            val parts = folder.split('/').filter { it.isNotEmpty() }
            return if (parts.size >= 2 && parts[0] != "Android") "${parts[0]}/${parts[1]}" else null
        }

        /** Il brano come lo ricordano playlist e raccolte. */
        fun ref(t: LocalTrack) = TrackRef(
            id = MediaId.track(KEY, "${t.id}", null).toString(),
            title = t.title,
            artist = t.artist,
            album = t.album,
            artwork = "$KEY/${t.albumId}",
            durationMs = t.durationMs,
        )

        private fun decadeName(decade: Int) = if (decade >= 2000) "Anni $decade" else "Anni ${decade % 100}"
    }
}
