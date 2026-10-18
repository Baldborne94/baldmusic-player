package io.github.baldborne94.baldmusic.core.library

import io.github.baldborne94.baldmusic.core.library.LibraryNode.Kind

/**
 * "La tua musica": brani che ti piacciono, playlist, ascoltati di recente e più
 * ascoltati. I brani restano della loro sorgente (telefono o server): qui ci
 * sono solo i riferimenti, e la riproduzione li risolve lì.
 */
class PersonalSource(private val library: () -> UserLibrary) : MusicSource {

    override val key = KEY
    override val title = "La tua musica"
    override val cacheable get() = false

    override suspend fun sections(): List<LibraryNode> {
        val lib = library()
        return listOf(
            // Prima le raccolte: le cartelle sono il modo in cui organizzi la musica (anche in auto).
            section(COLLECTIONS, "Raccolte", grid = true, subtitle = collections(lib.collections.size)),
            section(LIKED, "Brani che ti piacciono", subtitle = count(lib.liked.size)),
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
            RECENT -> lib.recent.map(::containerNode)
            COLLECTIONS -> lib.collections.sortedBy { it.name.lowercase() }.map(::playlistNode)
            PLAYLISTS -> lib.playlists.filterNot { it.isCollection }.map(::playlistNode)
            PLAYLIST -> lib.playlists.firstOrNull { it.id == id.id }?.let { pl ->
                pl.tracks.mapNotNull { trackNode(it, MediaId(KEY, PLAYLIST, pl.id)) }
            }.orEmpty()
            else -> emptyList()
        }
    }

    override suspend fun node(id: MediaId): LibraryNode? {
        val lib = library()
        return when (id.kind) {
            PLAYLIST -> lib.playlists.firstOrNull { it.id == id.id }?.let(::playlistNode)
            else -> sections().firstOrNull { it.mediaId == id.toString() }
        }
    }

    /** I brani appartengono alle altre sorgenti: qui non ce n'è nessuno. */
    override suspend fun track(id: MediaId): LibraryNode? = null

    override suspend fun search(query: String): List<LibraryNode> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        return library().playlists.filter { it.name.lowercase().contains(q) }.map(::playlistNode)
    }

    private fun section(kind: String, title: String, grid: Boolean = false, subtitle: String? = null) =
        LibraryNode(MediaId(KEY, kind).toString(), title, Kind.SECTION, subtitle = subtitle, gridChildren = grid)

    private fun playlistNode(pl: UserPlaylist) = LibraryNode(
        mediaId = MediaId(KEY, PLAYLIST, pl.id).toString(),
        title = pl.name,
        kind = Kind.PLAYLIST,
        subtitle = "${if (pl.isCollection) "Raccolta" else "Playlist"} · ${count(pl.tracks.size)}",
        artwork = pl.tracks.firstNotNullOfOrNull { it.artwork },
    )

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

        fun count(n: Int) = if (n == 1) "1 brano" else "$n brani"
        private fun playlists(n: Int) = if (n == 1) "1 playlist" else "$n playlist"
        private fun collections(n: Int) = if (n == 1) "1 raccolta" else "$n raccolte"
    }
}
