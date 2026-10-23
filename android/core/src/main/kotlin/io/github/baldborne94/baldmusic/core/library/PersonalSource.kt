package io.github.baldborne94.baldmusic.core.library

import io.github.baldborne94.baldmusic.core.library.LibraryNode.Kind

/**
 * "La tua musica": brani che ti piacciono, playlist, ascoltati di recente e più
 * ascoltati. I brani restano della loro sorgente (telefono o server): qui ci
 * sono solo i riferimenti, e la riproduzione li risolve lì.
 */
class PersonalSource(
    private val now: () -> Long = { System.currentTimeMillis() },
    /** I decenni della musica del telefono, per i loro mix (li sa il catalogo locale). */
    private val decades: suspend () -> List<LibraryNode> = { emptyList() },
    private val library: () -> UserLibrary,
) : MusicSource {

    override val key = KEY
    override val title = "La tua musica"
    override val cacheable get() = false

    override suspend fun sections(): List<LibraryNode> {
        val lib = library()
        return listOf(
            // Prima le raccolte: le cartelle sono il modo in cui organizzi la musica (anche in auto).
            section(COLLECTIONS, "Raccolte", grid = true, subtitle = collections(lib.collections.size)),
            section(LIKED, "Brani che ti piacciono", subtitle = count(lib.liked.size)),
            // I mix: gli stessi in Home e in Android Auto.
            section(MIXES, "I tuoi mix", grid = true),
            section(PLAYLISTS, "Le tue playlist", grid = true, subtitle = playlists(lib.playlists.count { !it.isCollection })),
            section(RECENT, "Ascoltati di recente", grid = true),
            section(TOP, "I più ascoltati"),
        )
    }

    override suspend fun children(id: MediaId): List<LibraryNode> {
        val lib = library()
        return when (id.kind) {
            LIKED -> lib.liked.mapNotNull { trackNode(it, MediaId(KEY, LIKED)) }
            TOP -> lib.topTracks().mapNotNull { trackNode(it, MediaId(KEY, TOP)) }
            RECENT -> lib.recent.mapNotNull { recentNode(lib, it) }
            COLLECTIONS -> lib.collections.sortedBy { it.name.lowercase() }.map(::playlistNode)
            PLAYLISTS -> lib.playlists.filterNot { it.isCollection }.map(::playlistNode)
            PLAYLIST -> lib.playlists.firstOrNull { it.id == id.id }?.let { pl ->
                pl.tracks.mapNotNull { trackNode(it, MediaId(KEY, PLAYLIST, pl.id)) }
            }.orEmpty()
            MIXES -> mixes(lib)
            MIX -> mixTracks(lib, id.id.orEmpty()).mapNotNull { trackNode(it, MediaId(KEY, MIX, id.id)) }
            else -> emptyList()
        }
    }

    /**
     * I tuoi mix, come i Daily Mix: "Riscopri" (brani che ti piacevano e non senti da un
     * po'), "Per la macchina" (i tuoi preferiti di sempre), il mix casuale, i preferiti e i
     * più ascoltati in shuffle, un mix per ognuno dei tuoi artisti più ascoltati, uno per
     * decennio. Riscopri e Per la macchina cambiano ogni giorno, e nello stesso giorno
     * restano uguali (la coda si ricostruisce identica).
     */
    private suspend fun mixes(lib: UserLibrary): List<LibraryNode> = buildList {
        fun ours(kind: String, title: String, subtitle: String) {
            val tracks = mixTracks(lib, kind)
            if (tracks.size >= MIN_MIX) add(mixNode(MediaId(KEY, MIX, kind).toString(), title, subtitle, mosaic(tracks)))
        }
        ours(REDISCOVER, "Riscopri", "Ti piacevano, non li senti da un po'")
        ours(DRIVE, "Per la macchina", "I tuoi preferiti di sempre, mescolati")
        add(mixNode(MediaId(LocalCatalog.KEY, LocalCatalog.SHUFFLE).toString(), "Mix casuale", "Tutta la tua musica, mescolata", null))
        if (lib.liked.isNotEmpty()) add(mixNode(MediaId(KEY, LIKED).toString(), "Preferiti in shuffle", "I brani che ti piacciono", mosaic(lib.liked)))
        if (lib.plays.isNotEmpty()) add(mixNode(MediaId(KEY, TOP).toString(), "In loop", "Quelli che ascolti di più", mosaic(lib.topTracks())))
        // Un mix per ognuno dei tuoi artisti più ascoltati, tra i brani del telefono.
        lib.plays.values.filter { it.track.id.startsWith("${LocalCatalog.KEY}/") && !it.track.artist.isNullOrBlank() }
            .groupBy { ArtistNames.key(it.track.artist!!) }
            .values.sortedByDescending { list -> list.sumOf { it.count } }
            .take(TOP_ARTIST_MIXES)
            .forEach { list ->
                val name = list.groupingBy { it.track.artist!! }.eachCount().maxBy { it.value }.key
                add(mixNode(MediaId(LocalCatalog.KEY, LocalCatalog.ARTIST_MIX, name).toString(), "Mix $name", "$name e musica simile", list.first().track.artwork))
            }
        decades().forEach { d -> add(mixNode(d.mediaId, "Mix ${d.title}", d.subtitle.orEmpty(), d.artwork)) }
    }

    /** I brani di uno dei nostri mix, mescolati con il giorno: uguali per tutto il giorno. */
    private fun mixTracks(lib: UserLibrary, kind: String): List<TrackRef> {
        val today = now()
        val random = kotlin.random.Random(today / DAY_MS * 31 + kind.hashCode())
        return when (kind) {
            REDISCOVER -> {
                val old = today - REDISCOVER_AFTER_MS
                val played = lib.plays.values.filter { it.count >= 2 && it.lastPlayed < old }.map { it.track }
                val likedForgotten = lib.liked.filter { t -> lib.plays[t.id]?.let { it.lastPlayed < old } ?: true }
                (played + likedForgotten).distinctBy { it.id }.shuffled(random).take(MIX_SIZE)
            }
            DRIVE -> {
                val favourite = lib.plays.values.filter { it.count >= 3 }.sortedByDescending { it.count }.take(MIX_SIZE).map { it.track }
                (favourite + lib.liked.take(MIX_SIZE / 2)).distinctBy { it.id }.shuffled(random).take(MIX_SIZE)
            }
            else -> emptyList()
        }
    }

    private fun mosaic(tracks: List<TrackRef>): String? = artworkOf(UserPlaylist("", "", tracks))

    private fun mixNode(mediaId: String, title: String, subtitle: String, artwork: String?) =
        LibraryNode(mediaId = mediaId, title = title, kind = Kind.PLAYLIST, subtitle = subtitle, artwork = artwork)

    override suspend fun node(id: MediaId): LibraryNode? {
        val lib = library()
        return when (id.kind) {
            PLAYLIST -> lib.playlists.firstOrNull { it.id == id.id }?.let(::playlistNode)
            MIX -> mixes(lib).firstOrNull { it.mediaId == id.toString() }
            else -> sections().firstOrNull { it.mediaId == id.toString() }
        }
    }

    /** I brani appartengono alle altre sorgenti: qui non ce n'è nessuno. */
    override suspend fun track(id: MediaId): LibraryNode? = null

    override suspend fun search(query: String): List<LibraryNode> {
        // Come nel resto della ricerca: maiuscole, accenti e apostrofi diversi non contano.
        val q = ArtistNames.key(query)
        if (q.isEmpty()) return emptyList()
        return library().playlists.filter { ArtistNames.key(it.name).contains(q) }.map(::playlistNode)
    }

    private fun section(kind: String, title: String, grid: Boolean = false, subtitle: String? = null) =
        LibraryNode(MediaId(KEY, kind).toString(), title, Kind.SECTION, subtitle = subtitle, gridChildren = grid)

    private fun playlistNode(pl: UserPlaylist) = LibraryNode(
        mediaId = MediaId(KEY, PLAYLIST, pl.id).toString(),
        title = pl.name,
        kind = Kind.PLAYLIST,
        subtitle = "${if (pl.isCollection) "Raccolta" else "Playlist"} · ${count(pl.tracks.size)}",
        artwork = artworkOf(pl),
    )

    /**
     * Un ascoltato di recente com'è adesso: una raccolta o una playlist col nome e i brani di
     * oggi (cartella rinominata, nome cambiato a mano); se non c'è più, esce. Il resto (album,
     * artisti) come quando l'hai ascoltato.
     */
    private fun recentNode(lib: UserLibrary, c: ContainerRef): LibraryNode? {
        val id = MediaId.parse(c.id)
        if (id?.source == KEY && id.kind == PLAYLIST) return lib.playlists.firstOrNull { it.id == id.id }?.let(::playlistNode)
        return containerNode(c)
    }

    private fun containerNode(c: ContainerRef) = LibraryNode(
        mediaId = c.id,
        title = c.title,
        kind = runCatching { Kind.valueOf(c.kind) }.getOrDefault(Kind.ALBUM),
        subtitle = c.subtitle,
        artwork = c.artwork,
    )

    private fun trackNode(t: TrackRef, parent: MediaId): LibraryNode? {
        val base = MediaId.parse(t.id) ?: return null
        if (!base.isTrack) return null
        return LibraryNode(
            mediaId = base.withParent(parent).toString(),
            title = t.title,
            kind = Kind.TRACK,
            subtitle = t.artist,
            artist = t.artist,
            album = t.album,
            artwork = t.artwork,
            durationMs = t.durationMs,
        )
    }

    companion object {
        const val KEY = "me"
        const val LIKED = "liked"
        const val PLAYLISTS = "playlists"
        /** Le raccolte: le cartelle del telefono, modificabili come playlist. */
        const val COLLECTIONS = "collections"
        const val PLAYLIST = "playlist"
        const val RECENT = "recent"
        const val TOP = "top"
        const val MIXES = "mixes"
        const val MIX = "mix"
        const val REDISCOVER = "rediscover"
        const val DRIVE = "drive"

        private const val MIN_MIX = 8
        private const val MIX_SIZE = 60
        private const val TOP_ARTIST_MIXES = 4
        private const val DAY_MS = 24L * 3600 * 1000
        /** "Non lo senti da un po'": due mesi. */
        private const val REDISCOVER_AFTER_MS = 60L * DAY_MS

        fun count(n: Int) = if (n == 1) "1 brano" else "$n brani"

        /** Prefisso delle copertine a mosaico: l'app le compone da quattro copertine. */
        const val MOSAIC = "mosaic"

        /**
         * La copertina di una playlist o raccolta: con almeno quattro copertine diverse un
         * mosaico 2×2 ("mosaic/local/1,local/2,…"), altrimenti quella del primo brano.
         */
        fun artworkOf(pl: UserPlaylist): String? {
            val covers = pl.tracks.mapNotNull { it.artwork }.distinct()
            return if (covers.size >= 4) "$MOSAIC/${covers.take(4).joinToString(",")}" else covers.firstOrNull()
        }
        /** Le pagine della tua musica che l'app e Android Auto possono avere aperte. */
        val PAGES = listOf(KEY, "$KEY/$LIKED", "$KEY/$PLAYLISTS", "$KEY/$COLLECTIONS", "$KEY/$RECENT", "$KEY/$TOP", "$KEY/$MIXES")

        /**
         * Quello che la pagina [mediaId] mostra della tua musica: la pagina si ricarica solo
         * quando cambia questo. Un ascolto cambia gli ascolti e i recenti, non i preferiti né le
         * playlist: «Brani che ti piacciono» o una raccolta aperti restano come sono.
         * null = non è una pagina della tua musica.
         */
        fun shownBy(lib: UserLibrary, mediaId: String): Any? {
            val id = MediaId.parse(mediaId)?.takeIf { it.source == KEY } ?: return null
            return when (id.kind) {
                // Le sezioni, coi loro conteggi.
                null -> listOf(lib.collections.size, lib.liked.size, lib.playlists.count { !it.isCollection })
                LIKED -> lib.liked
                TOP -> lib.plays
                // Anche le playlist: il nome di una raccolta può cambiare senza un ascolto nuovo.
                RECENT -> lib.recent to lib.playlists
                PLAYLISTS, COLLECTIONS -> lib.playlists
                PLAYLIST -> lib.playlists.firstOrNull { it.id == id.id }
                // I mix vengono da preferiti e ascolti.
                MIXES, MIX -> lib.liked to lib.plays
                else -> lib
            }
        }

        private fun playlists(n: Int) = if (n == 1) "1 playlist" else "$n playlist"
        private fun collections(n: Int) = if (n == 1) "1 raccolta" else "$n raccolte"
    }
}
