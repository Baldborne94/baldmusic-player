package io.github.baldborne94.baldmusic.core.library

import kotlinx.serialization.Serializable

// La musica "tua": brani che ti piacciono, playlist, cronologia. Vive sul
// telefono (un file JSON) e vale per entrambe le sorgenti: un brano del server e
// uno del telefono possono stare nella stessa playlist.
//
// Qui solo dati e regole pure; il salvataggio su file lo fa l'app.

/** Un brano ricordato: l'ID senza contenitore più quanto serve a mostrarlo. */
@Serializable
data class TrackRef(
    val id: String,
    val title: String,
    val artist: String? = null,
    val album: String? = null,
    val artwork: String? = null,
    val durationMs: Long? = null,
)

/** Un album, una playlist o un artista ascoltato di recente. */
@Serializable
data class ContainerRef(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val artwork: String? = null,
    val kind: String,
)

@Serializable
data class UserPlaylist(
    val id: String,
    val name: String,
    val tracks: List<TrackRef> = emptyList(),
    val createdAt: Long = 0,
    /** Per le raccolte: la cartella del telefono da cui nasce, es. "Music/Movie Soundtracks". */
    val folder: String? = null,
    /** Brani della cartella tolti a mano: non vanno rimessi. */
    val removed: Set<String> = emptySet(),
) {
    val isCollection get() = folder != null
}

/** Una cartella del telefono vista come raccolta: nome e brani nell'ordine in cui sono arrivati. */
data class FolderCollection(val folder: String, val name: String, val tracks: List<TrackRef>)

@Serializable
data class PlayCount(val track: TrackRef, val count: Int, val lastPlayed: Long)

@Serializable
data class UserLibrary(
    /** Dal più recente. */
    val liked: List<TrackRef> = emptyList(),
    val playlists: List<UserPlaylist> = emptyList(),
    /** Dal più recente, senza doppioni. */
    val recent: List<ContainerRef> = emptyList(),
    val plays: Map<String, PlayCount> = emptyMap(),
    /** Ultime ricerche, dalla più recente. */
    val searches: List<String> = emptyList(),
    /** Cartelle la cui raccolta è stata eliminata: non si ricrea. */
    val ignoredFolders: Set<String> = emptySet(),
) {
    val collections get() = playlists.filter { it.isCollection }

    /**
     * Allinea le raccolte alle cartelle del telefono: una cartella nuova diventa una
     * raccolta, i brani nuovi di una cartella si aggiungono in fondo, quelli tolti a
     * mano restano fuori e quelli il cui file non c'è più ([present]) spariscono.
     * Le aggiunte a mano da altre cartelle o dal server restano. Restituisce la
     * stessa istanza se non cambia niente.
     */
    fun syncFolders(folders: List<FolderCollection>, present: Set<String>, now: Long, newId: () -> String): UserLibrary {
        var changed = false
        val byFolder = folders.associateBy { it.folder }
        val updated = playlists.map { pl ->
            val folder = pl.folder ?: return@map pl
            val source = byFolder[folder]
            val kept = pl.tracks.filter { t -> !t.id.startsWith(LOCAL_TRACK) || t.id in present }
            val have = kept.mapTo(HashSet()) { it.id }
            val added = source?.tracks.orEmpty().filter { it.id !in have && it.id !in pl.removed }
            if (kept.size == pl.tracks.size && added.isEmpty()) pl else {
                changed = true
                pl.copy(tracks = kept + added)
            }
        }
        val known = updated.mapNotNullTo(HashSet()) { it.folder } + ignoredFolders
        val created = folders.filter { it.folder !in known && it.tracks.isNotEmpty() }
            .map { UserPlaylist(newId(), it.name, it.tracks, now, folder = it.folder) }
        if (!changed && created.isEmpty()) return this
        return copy(playlists = updated + created)
    }

    fun isLiked(trackId: String) = liked.any { it.id == baseId(trackId) }

    fun toggleLike(track: TrackRef): UserLibrary {
        val id = baseId(track.id)
        return if (liked.any { it.id == id }) copy(liked = liked.filterNot { it.id == id })
        else copy(liked = listOf(track.copy(id = id)) + liked)
    }

    /** Un ascolto: conta il brano e mette in cima il contenitore da cui veniva. */
    fun recordPlay(track: TrackRef, container: ContainerRef?, now: Long): UserLibrary {
        val id = baseId(track.id)
        val previous = plays[id]
        val count = PlayCount(track.copy(id = id), (previous?.count ?: 0) + 1, now)
        val recent = if (container == null) recent
        else (listOf(container) + recent.filterNot { it.id == container.id }).take(MAX_RECENT)
        return copy(plays = trimPlays(plays + (id to count)), recent = recent)
    }

    /** I brani più ascoltati, a parità di conteggio il più recente prima. */
    fun topTracks(limit: Int = 50): List<TrackRef> =
        plays.values.sortedWith(compareByDescending<PlayCount> { it.count }.thenByDescending { it.lastPlayed })
            .take(limit).map { it.track }

    fun createPlaylist(id: String, name: String, now: Long, first: List<TrackRef> = emptyList()) =
        copy(playlists = playlists + UserPlaylist(id, name.trim().ifEmpty { "La mia playlist" }, first.map { it.copy(id = baseId(it.id)) }, now))

    fun renamePlaylist(id: String, name: String) =
        copy(playlists = playlists.map { if (it.id == id && name.isNotBlank()) it.copy(name = name.trim()) else it })

    fun deletePlaylist(id: String) = copy(
        playlists = playlists.filterNot { it.id == id },
        ignoredFolders = ignoredFolders + listOfNotNull(playlists.firstOrNull { it.id == id }?.folder),
        recent = recent.filterNot { it.id == MediaId(PersonalSource.KEY, PersonalSource.PLAYLIST, id).toString() },
    )

    /** Aggiunge in fondo; un brano già presente non viene duplicato. */
    fun addToPlaylist(id: String, tracks: List<TrackRef>) = copy(
        playlists = playlists.map { pl ->
            if (pl.id != id) pl
            else {
                val present = pl.tracks.map { it.id }.toSet()
                val adding = tracks.map { it.copy(id = baseId(it.id)) }.filter { it.id !in present }.distinctBy { it.id }
                // Un brano rimesso a mano in una raccolta non è più "tolto".
                pl.copy(tracks = pl.tracks + adding, removed = pl.removed - adding.map { it.id }.toSet())
            }
        },
    )

    /** Toglie un brano; da una raccolta lo toglie per sempre, anche se resta nella cartella. */
    fun removeFromPlaylist(id: String, trackId: String) = copy(
        playlists = playlists.map { pl ->
            if (pl.id != id) pl
            else {
                val base = baseId(trackId)
                pl.copy(tracks = pl.tracks.filterNot { it.id == base }, removed = if (pl.isCollection) pl.removed + base else pl.removed)
            }
        },
    )

    fun addSearch(query: String): UserLibrary {
        val q = query.trim()
        if (q.length < 2) return this
        return copy(searches = (listOf(q) + searches.filterNot { it.equals(q, ignoreCase = true) }).take(MAX_SEARCHES))
    }

    fun clearSearches() = copy(searches = emptyList())

    private fun trimPlays(map: Map<String, PlayCount>): Map<String, PlayCount> =
        if (map.size <= MAX_PLAYS) map
        else map.values.sortedByDescending { it.lastPlayed }.take(MAX_PLAYS).associateBy { it.track.id }

    companion object {
        const val MAX_RECENT = 20
        const val MAX_SEARCHES = 10
        const val MAX_PLAYS = 2000
        private const val LOCAL_TRACK = "local/track/"

        /** L'ID di un brano senza il contenitore da cui è stato scelto. */
        fun baseId(trackId: String): String = MediaId.parse(trackId)?.copy(parent = null)?.toString() ?: trackId
    }
}
